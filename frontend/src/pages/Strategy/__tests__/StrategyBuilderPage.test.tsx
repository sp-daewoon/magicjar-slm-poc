/**
 * StrategyBuilderPage 통합 테스트
 *
 * 검증:
 * - 페이지 렌더 (팔레트 + 캔버스 + 결과 영역)
 * - 팔레트에서 블록 선택 → DSL 미리보기 갱신
 * - 검증 버튼 흐름 (MSW mock)
 * - 모바일 안내 메시지 (768px)
 * - T-SC Phase 3: "이 전략으로 종목 찾기" 버튼 — strategyId 없을 때 미표시
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, beforeAll, afterAll, afterEach, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyBuilderPage } from '../StrategyBuilderPage'
import { useOnboardingStore } from '../../../stores/onboarding'

// MSW 서버 설정
const server = setupServer(
  http.get('/api/instruments/search', () =>
    HttpResponse.json({
      query: '005930',
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
      ],
      totalMatched: 1,
      limit: 10,
    }),
  ),
  http.post('/api/strategy/validate', () => {
    return HttpResponse.json({
      astDepth: 2,
      nodeCount: 3,
      indicatorCallCount: 1,
      estimatedEvalTimeMs: 5,
      complexityClass: 'LOW',
    })
  }),
  http.post('/api/strategy', () => {
    return HttpResponse.json({
      strategyId: 'test-strategy-id',
      complexity: {
        astDepth: 2,
        nodeCount: 3,
        indicatorCallCount: 1,
        estimatedEvalTimeMs: 5,
        complexityClass: 'LOW',
      },
    })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  window.localStorage.clear()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

function renderWithRoute(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <StrategyBuilderPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('StrategyBuilderPage', () => {
  it('데스크톱 레이아웃 — 팔레트 + 캔버스 렌더', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    // 팔레트 탭 확인
    expect(screen.getByLabelText('블록 팔레트')).toBeInTheDocument()
    // 캔버스 탭 확인
    expect(screen.getByText('매수 조건 (Entry)')).toBeInTheDocument()
    expect(screen.getByText('매도 조건 (Exit)')).toBeInTheDocument()
  })

  it('빈 상태에서 안내 메시지 표시', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('팔레트 지표 탭 렌더', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    expect(screen.getByLabelText('지표')).toBeInTheDocument()
  })

  it('팔레트 선택은 현재 트리의 첫 빈 슬롯을 채운다', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByLabelText('연산자/상수'))
    fireEvent.click(screen.getByText('비교: >'))
    fireEvent.click(screen.getByLabelText('지표'))
    fireEvent.click(screen.getByText('이동평균(MA)'))
    fireEvent.click(screen.getByLabelText('캔들'))
    fireEvent.click(screen.getByText('종가 (Close)'))

    expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toBe('ma(20) > close')
  })

  it('자연어 입력 버튼 클릭 → 모달 오픈', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    const nlBtn = screen.getByText('자연어로 입력하기')
    fireEvent.click(nlBtn)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('저장 버튼 — 빈 entry 시 disabled', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    const saveBtn = screen.getByText('저장')
    expect(saveBtn).toBeDisabled()
  })

  it('T-SC Phase 3 — 전략 미저장 상태에서 "이 전략으로 종목 찾기" 버튼 미표시', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    // strategyId가 없으면 버튼이 렌더되지 않아야 함 (ADR 0071 §8 H-1)
    expect(screen.queryByText('이 전략으로 종목 찾기')).not.toBeInTheDocument()
  })

  it('미너비니 4가지 전략 즐겨찾기를 기본 표시한다', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByLabelText('즐겨찾기'))

    expect(screen.getByRole('button', { name: 'FVG 매수 후보 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'OB 매수 후보 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'FVG 매도 후보 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'OB 매도 후보 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '미너비니 추세 템플릿 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '미너비니 신고가 근접 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '미너비니 VCP 수축 적용' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '미너비니 돌파 거래량 적용' })).toBeInTheDocument()
  })

  it('즐겨찾기 적용 시 사용자가 고른 기준봉을 유지한다', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    const intervalSelect = screen.getByLabelText('평가 기준봉') as HTMLSelectElement
    fireEvent.change(intervalSelect, { target: { value: '4h' } })
    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: '미너비니 VCP 수축 적용' }))

    expect(intervalSelect.value).toBe('4h')
    expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toContain('natr(14)')
  })

  it('완성 즐겨찾기 이후 빈 슬롯이 없으면 팔레트 선택 시 교체 확인 후 반영한다', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: 'OB 매수 후보 적용' }))
    fireEvent.click(screen.getByLabelText('지표'))
    fireEvent.click(screen.getByText('이동평균(MA)'))

    expect(confirmSpy).toHaveBeenCalled()
    expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toBe('ma(20)')
  })

  it('즐겨찾기 탭에서 OB 매수 후보 만들기 가이드를 연다', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: '가이드' }))

    expect(screen.getByRole('dialog', { name: 'OB 매수 후보 만들기 가이드' })).toBeInTheDocument()
    expect(screen.getByText(/ob_bullish_active/)).toBeInTheDocument()
    expect(screen.getByText(/FVG와 OB는 재료/)).toBeInTheDocument()
  })

  it('기본 즐겨찾기도 삭제하면 목록에서 숨긴다', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: 'FVG 매수 후보 즐겨찾기 삭제' }))

    expect(screen.queryByRole('button', { name: 'FVG 매수 후보 적용' })).not.toBeInTheDocument()
  })

  it('AI 전략 생성 버튼 → 팝업 오픈 → 생성 → valid 후보 불러오기 시 폼 주입(자동 저장 0)', async () => {
    let createCalls = 0
    server.use(
      http.post('/api/strategies/ai-generate', () =>
        HttpResponse.json({
          provider: 'claude-cli',
          generatedAt: '2026-06-08T12:00:00Z',
          warnings: [],
          items: [
            {
              name: 'AI 단타 후보',
              description: '20일선 돌파',
              entryDsl: 'close > ma(20)',
              exitDsl: null,
              riskPlan: { takeProfitPct: 1.5, fixedStopLossPct: 0.8, minHoldBars: 2 },
              rationale: '추세 확인',
              dslValid: true,
              dslError: null,
            },
          ],
        }),
      ),
      // 자동 저장 0 검증용 — 호출되면 createCalls 증가
      http.post('/api/strategies', () => {
        createCalls += 1
        return HttpResponse.json({ id: 99 })
      }),
    )
    useOnboardingStore.getState().setRegistered(74683223)

    render(<StrategyBuilderPage />, { wrapper: Wrapper })

    fireEvent.click(screen.getByRole('button', { name: 'AI 전략 생성' }))
    expect(screen.getByRole('dialog', { name: 'AI 전략 생성' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('전략 의도 (필수)'), {
      target: { value: '거래량 터지며 20일선 돌파 단타' },
    })
    fireEvent.click(screen.getByRole('button', { name: '생성하기' }))

    const loadButton = await screen.findByRole('button', { name: '빌더로 불러오기' })
    fireEvent.click(loadButton)

    // 폼 주입 — DSL 미리보기에 entry 반영
    await waitFor(() => {
      expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toBe(
        'close > ma(20)',
      )
    })
    // AI 생성 후보 인디케이터 표시
    expect(screen.getByText('AI 생성 후보')).toBeInTheDocument()
    // 자동 저장 0 — 저장 endpoint 미호출
    expect(createCalls).toBe(0)
  })

  it('평가 실행 전 캔들이 없으면 refresh 후 평가한다', async () => {
    let candleReads = 0
    let refreshCalls = 0
    let evaluateCalls = 0

    server.use(
      http.get('/api/strategies/:id', () =>
        HttpResponse.json({
          id: 42,
          userId: 1,
          name: 'candle_refresh_test',
          nameKo: '캔들 갱신 테스트',
          entry: 'close > ma(20)',
          exit: null,
          codes: ['005930'],
          intervals: ['1d'],
          status: 'PAUSED',
          createdAt: '2026-06-01T00:00:00+09:00',
        }),
      ),
      http.get('/api/symbols/:code/candles', () => {
        candleReads += 1
        return HttpResponse.json({
          candles:
            candleReads === 1
              ? []
              : [{ code: '005930', interval: '1d', ts: '2026-06-01T00:00:00+09:00', close: 293000 }],
        })
      }),
      http.post('/api/symbols/:code/candles/refresh', ({ request }) => {
        refreshCalls += 1
        expect(new URL(request.url).searchParams.get('intervals')).toBe('1d')
        return HttpResponse.json({ code: '005930', results: [] })
      }),
      http.post('/api/strategy/:id/evaluate', () => {
        evaluateCalls += 1
        return HttpResponse.json({
          strategyId: 42,
          signal: 'HOLD',
          strength: 0,
          reason: {},
          evalTimeMs: 1,
        })
      }),
    )

    renderWithRoute('/strategy/builder?strategyId=42')

    const evaluateButton = await screen.findByRole('button', { name: '평가 실행' })
    await waitFor(() => expect(evaluateButton).not.toBeDisabled())
    fireEvent.click(evaluateButton)

    await waitFor(() => {
      expect(refreshCalls).toBe(1)
      expect(evaluateCalls).toBe(1)
    })
  })
})
