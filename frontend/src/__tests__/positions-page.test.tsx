/**
 * PositionsPage test — hotfix /positions 빈 화면 (사용자 dogfooding 2026-05-21)
 *
 * 검증 시나리오:
 * 1. userId null → 셋업 안내 화면
 * 2. /api/account/positions 가 3건 반환 → 카드 3개 + KIS 매트릭스 셀 표시
 * 3. /api/account/positions 빈 배열 → "보유 포지션이 없습니다" 안내
 * 4. 412 응답 → "KIS 키 미등록" 안내 (실전·모의 동적 분기)
 * 5. accountType 토글(MOCK→REAL) 시 새 endpoint 호출 (?accountType=REAL)
 *
 * 회귀 방지: 기존 PositionsPage 는 usePositions (DB position) 호출 → 사용자 실제 보유와 무관 → 빈 결과.
 * 본 test 는 useAccountPositions (KIS inquire-balance) 로 정합됐는지 보증.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { PositionsPage } from '../pages/Risk/PositionsPage'
import { useOnboardingStore } from '../stores/onboarding'
import { ToastProvider } from '../components/ui/ToastProvider'

vi.mock('../api/hooks/useStompAccountBalance', () => ({
  useStompAccountBalance: () => ({ latestEvent: null }),
}))

// ──────────────────────────────────────────────
// msw — /api/account/positions 분기 스텁
// ──────────────────────────────────────────────

let lastQueryString = ''
let positionsResponse: 'three' | 'empty' | 'missing-credential' | 'error' = 'three'
let lastManualOrderRequest: Record<string, unknown> | null = null

const THREE_HOLDINGS = [
  {
    code: '000660',
    name: 'SK하이닉스',
    quantity: 1,
    avgPrice: 1_840_000,
    currentPrice: 1_900_000,
    evaluationAmount: 1_900_000,
    profitLoss: 60_000,
    profitLossRate: 3.26,
  },
  {
    code: '004090',
    name: '한국석유',
    quantity: 5,
    avgPrice: 12_000,
    currentPrice: 11_500,
    evaluationAmount: 57_500,
    profitLoss: -2_500,
    profitLossRate: -4.17,
  },
  {
    code: '005930',
    name: '삼성전자',
    quantity: 1,
    avgPrice: 78_000,
    currentPrice: 78_000,
    evaluationAmount: 78_000,
    profitLoss: 0,
    profitLossRate: 0,
  },
]

const REFRESHED_HOLDINGS = THREE_HOLDINGS.map((holding) =>
  holding.code === '004090'
    ? {
        ...holding,
        quantity: 6,
        evaluationAmount: 69_000,
      }
    : holding,
)

const server = setupServer(
  http.get('/api/runtime/flags', () =>
    HttpResponse.json({
      mode: 'MOCK',
      allowReal: false,
      killSwitchEnabled: false,
      killSwitchReason: null,
      userConsented: false,
      fetchedAt: '2026-05-26T00:00:00Z',
    }),
  ),
  http.get('/api/account/positions', ({ request }) => {
    const url = new URL(request.url)
    lastQueryString = url.search
    if (url.searchParams.get('refresh') === 'true') return HttpResponse.json(REFRESHED_HOLDINGS)
    if (positionsResponse === 'three') return HttpResponse.json(THREE_HOLDINGS)
    if (positionsResponse === 'empty') return HttpResponse.json([])
    if (positionsResponse === 'missing-credential')
      return new HttpResponse(JSON.stringify({ message: '키 미등록' }), {
        status: 412,
        headers: { 'Content-Type': 'application/json' },
      })
    return new HttpResponse('upstream error', { status: 502 })
  }),
  http.post('/api/orders/manual', async ({ request }) => {
    lastManualOrderRequest = (await request.json()) as Record<string, unknown>
    return HttpResponse.json(
      {
        orderId: null,
        status: 'ACCEPTED',
        idempotencyKey: 'manual:test',
        message: '수동 주문이 접수되었습니다.',
      },
      { status: 202 },
    )
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  lastQueryString = ''
  lastManualOrderRequest = null
  positionsResponse = 'three'
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ToastProvider>
          <PositionsPage />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function setUserId(id: number | null) {
  useOnboardingStore.setState({ registeredUserId: id })
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('PositionsPage — /api/account/positions 정합 (hotfix 2026-05-21)', () => {
  beforeEach(() => {
    // 각 테스트마다 깨끗한 상태 — 기본은 등록된 userId
    window.localStorage.clear()
    window.sessionStorage.clear()
    setUserId(74683223)
  })

  it('userId 미등록 시 셋업 안내 화면', () => {
    setUserId(null)
    renderPage()

    expect(screen.getByText('보유 포지션')).toBeInTheDocument()
    expect(screen.getByText(/KIS 계좌 등록을 완료하면/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '계좌 등록하러 가기' })).toBeInTheDocument()
  })

  it('3건 응답 → 카드 3개 + 종목명/평가금액/수익률 셀 표시 + 호출 횟수 검증', async () => {
    positionsResponse = 'three'
    renderPage()

    // 카드 로드 대기
    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })

    expect(screen.getByText('한국석유')).toBeInTheDocument()
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('총 3개 종목')).toBeInTheDocument()
    // 매트릭스 셀 — SK하이닉스 현재가/평가금 1,900,000원 둘 다 표시 (getAllByText 2건)
    expect(screen.getAllByText('1,900,000원').length).toBeGreaterThanOrEqual(2)
    // 수익률 부호 검증 — 한국 표준 색상 분기 cover
    expect(screen.getByText('+3.26%')).toBeInTheDocument()
    expect(screen.getByText('-4.17%')).toBeInTheDocument()
    expect(screen.getByText('0.00%')).toBeInTheDocument()
    // 평가손익 부호 — SK하이닉스 +60,000원
    expect(screen.getByText('+60,000원')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '추가 매수' })).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: '일부 매도' })).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: '전량 청산' })).toHaveLength(3)
    // 런타임 플래그/저장값이 없으면 안전 기본값 MOCK
    expect(lastQueryString).toContain('accountType=MOCK')
    expect(lastQueryString).toContain('userId=74683223')
  })

  it('빈 배열 → "보유 포지션이 없습니다" 안내', async () => {
    positionsResponse = 'empty'
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('보유 포지션이 없습니다.')).toBeInTheDocument()
    })
  })

  it('412 응답 → "KIS 키 미등록" 안내 + accountType 분기 메시지', async () => {
    positionsResponse = 'missing-credential'
    renderPage()

    await waitFor(() => {
      // 기본 MOCK → "모의계좌 KIS 키가 등록되지 않았습니다"
      expect(screen.getByText(/모의계좌 KIS 키가 등록되지 않았습니다/)).toBeInTheDocument()
    })
    expect(screen.getByText(/모의\(VPS\) 키를 등록한 뒤/)).toBeInTheDocument()
  })

  it('accountType 토글 MOCK→REAL 시 새 endpoint 호출(?accountType=REAL)', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })
    expect(lastQueryString).toContain('accountType=MOCK')

    // 실전 버튼 클릭
    await user.click(screen.getByRole('button', { name: '실전' }))

    await waitFor(() => {
      expect(lastQueryString).toContain('accountType=REAL')
    })
  })

  it('새로고침 버튼은 Redis cache를 우회해 KIS 보유 수량을 다시 읽는다', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('한국석유')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '포지션 목록 새로고침' }))

    await waitFor(() => {
      expect(lastQueryString).toContain('refresh=true')
    })

    const oilCard = screen.getByLabelText('보유 종목 한국석유')
    expect(within(oilCard).getByText('6주')).toBeInTheDocument()
  })

  it('주문 런타임과 선택 계좌가 다르면 포지션 직접 주문을 차단한다', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '실전' }))

    await waitFor(() => {
      expect(
        screen.getAllByText('현재 주문 모드가 MOCK라 REAL 포지션 주문을 막았습니다.').length,
      ).toBeGreaterThan(0)
    })

    for (const button of screen.getAllByRole('button', { name: '추가 매수' })) {
      expect(button).toBeDisabled()
    }
    for (const button of screen.getAllByRole('button', { name: '일부 매도' })) {
      expect(button).toBeDisabled()
    }
    for (const button of screen.getAllByRole('button', { name: '전량 청산' })) {
      expect(button).toBeDisabled()
    }
  })

  it('추가 매수 모달에서 보유 수량보다 큰 BUY 시장가 주문을 접수한다', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('한국석유')).toBeInTheDocument()
    })

    const oilCard = screen.getByLabelText('보유 종목 한국석유')

    await user.click(within(oilCard).getByRole('button', { name: '추가 매수' }))
    expect(screen.getByRole('dialog', { name: '보유 종목 추가 매수' })).toBeInTheDocument()

    const quantityInput = screen.getByLabelText('매수 수량')
    await user.clear(quantityInput)
    await user.type(quantityInput, '7')
    await user.click(screen.getByLabelText('포지션 추가 매수 주문 내용 확인'))
    await user.click(screen.getByRole('button', { name: '추가 매수 접수' }))

    await waitFor(() => {
      expect(lastManualOrderRequest).toMatchObject({
        userId: 74683223,
        code: '004090',
        side: 'BUY',
        priceType: 'MARKET',
        quantity: 7,
        limitPrice: null,
      })
    })
    await waitFor(() => {
      expect(lastQueryString).toContain('refresh=true')
    })
  })

  it('일부 매도 모달에서 보유 수량 이하 SELL 시장가 주문을 접수한다', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('한국석유')).toBeInTheDocument()
    })

    const oilCard = screen.getByLabelText('보유 종목 한국석유')

    await user.click(within(oilCard).getByRole('button', { name: '일부 매도' }))
    expect(screen.getByRole('dialog', { name: '보유 종목 매도' })).toBeInTheDocument()

    const quantityInput = screen.getByLabelText('매도 수량')
    await user.clear(quantityInput)
    await user.type(quantityInput, '2')
    await user.click(screen.getByLabelText('포지션 매도 주문 내용 확인'))
    await user.click(screen.getByRole('button', { name: '매도 주문 접수' }))

    await waitFor(() => {
      expect(lastManualOrderRequest).toMatchObject({
        userId: 74683223,
        code: '004090',
        side: 'SELL',
        priceType: 'MARKET',
        quantity: 2,
        limitPrice: null,
      })
    })
  })
})
