/**
 * SymbolDetailPage smoke test — PR-10a (룰 4 정합: smoke 1건)
 *
 * spec §11-2 정합:
 * - vitest mock useSymbol + useSymbolCandles → 카드 렌더링 검증
 * - msw로 GET /api/symbols/{code} mock 응답
 * - SymbolHeader · SymbolPriceCard · SymbolMetaCard 렌더링 확인
 * - HTTP 503 에러 시 SymbolError 표시 확인
 *
 * 주의: LWC v4 (lightweight-charts)는 jsdom 환경에서 canvas 미지원 → chart 영역은 mock
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SymbolDetailPage } from '../SymbolDetailPage'
import { ToastProvider } from '../../../components/ui/ToastProvider'
import { useOnboardingStore } from '../../../stores/onboarding'
import type { DaytradeSignalNavigationState } from '../../../lib/daytrade/signalInterpretation'

// ──────────────────────────────────────────────
// LWC canvas mock (jsdom 미지원 — graceful)
// ──────────────────────────────────────────────

vi.mock('../LightweightChart', () => ({
  LightweightChart: () => <div data-testid="mock-lightweight-chart" aria-label="종목 차트" />,
}))

// ──────────────────────────────────────────────
// STOMP mock (WebSocket 미지원 환경)
// ──────────────────────────────────────────────

vi.mock('../../../lib/stomp', () => {
  const client = {
    connected: false,
    onConnect: undefined,
    onDisconnect: undefined,
    onWebSocketClose: undefined,
    onStompError: undefined,
  }
  const orderbookPayload = {
    code: '005930',
    ts: '2026-05-26T10:10:00+09:00',
    asks: [
      { price: '61000', quantity: 1200 },
      { price: '61100', quantity: 1800 },
    ],
    bids: [
      { price: '60900', quantity: 2200 },
      { price: '60800', quantity: 1500 },
    ],
  }
  return {
    getStompClient: () => client,
    useStompTopic: vi.fn((topic: string, handler: (payload: unknown) => void, enabled?: boolean) => {
      if (enabled && topic === '/topic/stomp.api.market.orderbook.005930') {
        window.setTimeout(() => handler(orderbookPayload), 0)
      }
    }),
    isStompConnected: () => false,
  }
})

// ──────────────────────────────────────────────
// msw 서버 설정
// ──────────────────────────────────────────────

const mockSymbolData = {
  code: '005930',
  name: '삼성전자',
  market: 'KOSPI',
  industry: '전기전자',
  currentPrice: '60900',
  previousClose: '60500',
  change: '400',
  changeRate: '0.66',
  high: '61000',
  low: '60750',
  open: '60800',
  volume: 12345678,
  marketCap: '4800000',
  per: '12.5',
  pbr: '1.3',
  eps: '4872',
  bps: '46850',
  asOf: '2026-05-07T14:23:45.123Z',
  source: 'live' as const,
  stale: false,
}

const mockCandlesData = {
  code: '005930',
  interval: '1d',
  candles: [
    { ts: '2026-05-06T15:30:00Z', open: '60500', high: '61200', low: '60400', close: '60900', volume: 12345678 },
  ],
  hasMore: false,
}

let accountPositionsResponse: 'holding' | 'empty' = 'holding'
let lastManualOrderRequest: Record<string, unknown> | null = null

const mockAccountHoldings = [
  {
    code: '005930',
    name: '삼성전자',
    quantity: 2,
    avgPrice: 60000,
    currentPrice: 60900,
    evaluationAmount: 121800,
    profitLoss: 1800,
    profitLossRate: 1.5,
  },
]

const server = setupServer(
  http.get('/api/symbols/005930', () => {
    return HttpResponse.json(mockSymbolData)
  }),

  http.get('/api/symbols/005930/candles', () => {
    return HttpResponse.json(mockCandlesData)
  }),

  http.get('/api/watchlists', () => {
    return HttpResponse.json([])
  }),

  // 캔들 수집 건강도 (readiness P1) — STALE/NO_TICK 1줄 표시
  http.get('/api/symbols/005930/candle-health', () => {
    return HttpResponse.json({
      code: '005930',
      interval: '1m',
      lastCandleAt: '2026-05-26T10:05:00+09:00',
      staleSeconds: 320,
      status: 'STALE',
      reason: 'NO_TICK',
      reasonDetail: '최근 체결이 수신되지 않아 캔들이 갱신되지 않았습니다',
      checkedAt: '2026-05-26T10:10:20+09:00',
    })
  }),

  // orderbook subscribe — graceful (백엔드 미완 시 무시)
  http.post('/api/symbols/005930/orderbook/subscribe', () => {
    return HttpResponse.json({ subscribed: true, slot: 'live' })
  }),

  // realtime(현재가+호가) subscribe/unsubscribe — "표시 중에만" 라이프사이클
  // mount → subscribe(orderbookSlot 통보) / unmount·종목전환 → unsubscribe(이탈 해제)
  http.post('/api/symbols/005930/realtime/subscribe', () => {
    return HttpResponse.json({ subscribed: true, priceSlot: 'live', orderbookSlot: 'live' })
  }),
  http.post('/api/symbols/005930/realtime/unsubscribe', () => {
    return HttpResponse.json({ unsubscribed: true })
  }),

  // instrument 마스터 (active=true 정상 종목)
  http.get('/api/instruments/005930', () => {
    return HttpResponse.json({
      code: '005930', isin: 'KR7005930003', name: '삼성전자', nameEn: 'Samsung Electronics',
      market: 'KOSPI', type: 'STOCK', sector: '전기전자', industryMedium: null, theme: null,
      listedAt: '1975-06-11', delistedAt: null, active: true, aliases: [],
      tradingHalt: false, managementStock: false, marketCapPrev: '4800000', isKospi200: true,
    })
  }),

  http.get('/api/runtime/flags', () => {
    return HttpResponse.json({
      mode: 'MOCK',
      allowReal: false,
      killSwitchEnabled: false,
      killSwitchReason: null,
      userConsented: false,
      fetchedAt: '2026-05-26T00:00:00Z',
    })
  }),

  http.get('/api/account/positions', () => {
    return HttpResponse.json(accountPositionsResponse === 'holding' ? mockAccountHoldings : [])
  }),

  http.post('/api/orders/manual', async ({ request }) => {
    lastManualOrderRequest = (await request.json()) as Record<string, unknown>
    return HttpResponse.json(
      {
        orderId: null,
        status: 'ACCEPTED',
        idempotencyKey: 'manual:symbol-detail-test',
        message: '수동 주문이 접수되었습니다.',
      },
      { status: 202 },
    )
  }),

  // 상장폐지 종목 instrument (active=false)
  http.get('/api/instruments/004530', () => {
    return HttpResponse.json({
      code: '004530', isin: null, name: '한국화장품', nameEn: null,
      market: 'KOSPI', type: 'STOCK', sector: null, industryMedium: null, theme: null,
      listedAt: null, delistedAt: '2024-03-15', active: false, aliases: [],
      tradingHalt: null, managementStock: null, marketCapPrev: null, isKospi200: null,
    })
  }),

  // 잘못된 코드 → 404
  http.get('/api/symbols/999999', () => {
    return HttpResponse.json(
      { error: 'DATA_UNAVAILABLE', message: '종목 데이터를 가져올 수 없습니다' },
      { status: 503 },
    )
  }),

  http.get('/api/symbols/999999/candles', () => {
    return HttpResponse.json(
      { error: 'DATA_UNAVAILABLE', message: '종목 데이터를 가져올 수 없습니다' },
      { status: 503 },
    )
  }),

  http.get('/api/instruments/999999', () => {
    return HttpResponse.json(
      { error: 'NOT_FOUND', message: '종목을 찾을 수 없습니다' },
      { status: 404 },
    )
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
beforeEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
  useOnboardingStore.setState({ registeredUserId: 74683223 })
})
afterEach(() => {
  server.resetHandlers()
  accountPositionsResponse = 'holding'
  lastManualOrderRequest = null
  useOnboardingStore.setState({ registeredUserId: null })
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderSymbolDetailPage(code: string, state?: DaytradeSignalNavigationState) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[state ? { pathname: `/symbol/${code}`, state } : `/symbol/${code}`]}>
          <Routes>
            <Route path="/symbol/:code" element={<SymbolDetailPage />} />
            <Route path="/instruments" element={<div>종목검색 페이지</div>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('SymbolDetailPage smoke', () => {
  it('종목 데이터 로드 후 헤더·현재가 카드·메타 카드가 표시된다', async () => {
    renderSymbolDetailPage('005930')

    // 로딩 스피너 표시 후 데이터 로드
    await waitFor(() => {
      // 종목명 헤더
      expect(screen.getByRole('heading', { name: '삼성전자' })).toBeInTheDocument()
    })

    // 종목 코드
    expect(screen.getByText('(005930)')).toBeInTheDocument()

    // 시장 배지
    expect(screen.getByText('KOSPI')).toBeInTheDocument()

    // 산업 배지
    expect(screen.getByText('전기전자')).toBeInTheDocument()

    // 현재가 (천 단위 콤마 포맷: 60,900)
    expect(screen.getAllByText('60,900').length).toBeGreaterThan(0)

    // 메타 카드: PER
    expect(screen.getByText('PER')).toBeInTheDocument()
    expect(screen.getByText('12.5')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: /종목검색 화면으로 돌아가기/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /삼성전자 관심종목 등록/ })).toBeInTheDocument()
  })

  it('단타 발굴에서 넘어온 종목은 발굴 신호 요약과 AI 시그널 해석 진입점을 표시한다', async () => {
    renderSymbolDetailPage('005930', {
      daytradeSignal: {
        source: 'DAYTRADE_DISCOVER',
        code: '005930',
        name: '삼성전자',
        rank: 1,
        score: 0.82,
        scoreComponents: { signal: 0.9, volume: 0.7, atr: 0.5, cap: 1 },
        reason: '[DAYTRADE_GAP_AND_GO] 갭 앤 고 — {trigger=THRESHOLD}',
        presetKey: 'DAYTRADE_GAP_AND_GO',
        presetTitle: '갭 앤 고',
        summary: '삼성전자는 갭 앤 고 조건에서 BUY 후보로 잡혔습니다.',
        reasonComments: ['시가가 전일 종가 이상 조건을 통과했습니다.'],
        scoreComments: ['시그널 축이 가장 크게 기여했습니다.'],
      },
    })

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '삼성전자' })).toBeInTheDocument()
    })

    expect(screen.getByText('단타 발굴 신호')).toBeInTheDocument()
    expect(screen.getByText(/갭 앤 고 조건에서 BUY 후보/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '시그널 해석 요청' })).toBeInTheDocument()
  })

  it('캔들이 stale일 때 차트 영역에 수집 사유 1줄이 표시된다 (readiness P1)', async () => {
    renderSymbolDetailPage('005930')

    await waitFor(() => {
      const badge = screen.getByTestId('candle-health-badge')
      expect(badge).toHaveAttribute('data-tone', 'amber')
      expect(badge).toHaveTextContent('최근 체결이 수신되지 않아 캔들이 갱신되지 않았습니다')
    })
  })

  it('잘못된 종목 코드 진입 시 SymbolError가 표시된다 (DoD #12)', async () => {
    renderSymbolDetailPage('999999')

    // retry: false 설정이지만 axios 에러 응답 처리 대기
    await waitFor(
      () => {
        expect(screen.getByText('종목 정보를 찾을 수 없습니다')).toBeInTheDocument()
      },
      { timeout: 5000 },
    )

    // 종목 코드 표시
    expect(screen.getByText('999999')).toBeInTheDocument()
  })

  it('상장폐지 종목(004530) 진입 시 SymbolError variant=delisted + delistedAt 표시 (PR-29c §6.5 회귀)', async () => {
    // PR-29c 검증: dogfooding §6.5 delisted 표시 검증 (9dde856 산출물)
    // instrument.active=false → 가격·차트·호가 차단 + "상장폐지된 종목입니다" 표시
    renderSymbolDetailPage('004530')

    await waitFor(
      () => {
        expect(screen.getByText('상장폐지된 종목입니다')).toBeInTheDocument()
      },
      { timeout: 5000 },
    )

    // 상장폐지일 표시
    expect(screen.getByText('2024-03-15')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: /종목검색으로/ })).toBeInTheDocument()
  })

  it('화면 이탈(unmount) 시 실시간 구독을 해제한다 — 호가 "표시 중에만" 라이프사이클', async () => {
    // cleanup은 navigator.sendBeacon(/realtime/unsubscribe)을 우선 호출 (탭 닫기 대비)
    const sendBeaconSpy = vi.fn((_url: string, _data?: BodyInit | null) => true)
    vi.stubGlobal('navigator', {
      ...window.navigator,
      sendBeacon: sendBeaconSpy,
    })

    const { unmount } = renderSymbolDetailPage('005930')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '삼성전자' })).toBeInTheDocument()
    })

    // 진입 직후엔 해제 호출 0 (표시 중 — 구독 유지)
    expect(sendBeaconSpy).not.toHaveBeenCalled()

    // 화면 이탈 → cleanup → unsubscribe(beacon) 발동
    unmount()

    expect(sendBeaconSpy).toHaveBeenCalledTimes(1)
    expect(sendBeaconSpy).toHaveBeenCalledWith(
      '/api/symbols/005930/realtime/unsubscribe',
      expect.anything(),
    )

    vi.unstubAllGlobals()
  })

  it('호가 가격을 지정가로 가져와 종목상세에서 BUY LIMIT 주문을 접수한다', async () => {
    renderSymbolDetailPage('005930')
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '삼성전자' })).toBeInTheDocument()
    })

    await user.click(await screen.findByRole('button', { name: '60,900원 매수호가 지정가 선택' }))

    const priceInput = screen.getByLabelText('주문 가격')
    // 천단위 콤마 표시 포맷 (2026-06-05 가격 input 포맷팅 도입 정합)
    expect(priceInput).toHaveValue('60,900')

    const quantityInput = screen.getByLabelText('주문 수량')
    await user.clear(quantityInput)
    await user.type(quantityInput, '3')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '미리보기' })).toBeEnabled()
    })
    await user.click(screen.getByRole('button', { name: '미리보기' }))
    await user.click(screen.getByLabelText('주문 내용 확인'))
    await user.click(screen.getByRole('button', { name: 'MOCK 주문 발행' }))

    await waitFor(() => {
      expect(lastManualOrderRequest).toMatchObject({
        userId: 74683223,
        code: '005930',
        side: 'BUY',
        priceType: 'LIMIT',
        quantity: 3,
        limitPrice: '60900',
      })
    })
  })

  it('보유가 없어도 종목상세 SELL 주문 입력과 접수가 가능하다', async () => {
    accountPositionsResponse = 'empty'
    renderSymbolDetailPage('005930')
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('보유 없음')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '매도' }))
    await user.click(await screen.findByRole('button', { name: '61,000원 매도호가 지정가 선택' }))

    expect(screen.getByText(/보유 수량을 초과하거나 보유가 확인되지 않은 매도/)).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '미리보기' })).toBeEnabled()
    })
    await user.click(screen.getByRole('button', { name: '미리보기' }))
    await user.click(screen.getByLabelText('주문 내용 확인'))
    await user.click(screen.getByRole('button', { name: 'MOCK 주문 발행' }))

    await waitFor(() => {
      expect(lastManualOrderRequest).toMatchObject({
        userId: 74683223,
        code: '005930',
        side: 'SELL',
        priceType: 'LIMIT',
        quantity: 1,
        limitPrice: '61000',
      })
    })
  })
})
