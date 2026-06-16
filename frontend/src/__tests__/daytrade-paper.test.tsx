/**
 * Daytrade Paper R3 test — 페이퍼 승급 + 대시보드 + 모의 배지 (ADR 0122 R3)
 *
 * 검증 시나리오:
 *  A. PaperDashboardPage
 *   1. userId null → 셋업 안내
 *   2. 전략 2건 + 포지션 1건 응답 → 카드·row 표시 + 모의 배지 노출
 *   3. 빈 응답 → "활성 모의 전략이 없습니다" / "모의 포지션이 없습니다" (mock 합성 0)
 *   4. 전략 중단 버튼 → DELETE /api/daytrade/paper/{id} 호출
 *  B. DaytradeMatchTable 다중선택
 *   5. selectionMode=true → row 체크박스 노출 + 토글 콜백
 *
 * 모의 배지(LOCK): "모의 · 실거래 아님" 텍스트 — 실거래 오인 0 보증.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { PaperDashboardPage } from '../pages/Strategy/PaperDashboardPage'
import { DaytradeMatchTable } from '../features/screener/components/DaytradeMatchTable'
import type { DaytradeMatchDto } from '../features/screener/api/daytradeDiscoverApi'
import { useOnboardingStore } from '../stores/onboarding'
import { ToastProvider } from '../components/ui/ToastProvider'

// 대시보드 시그널 섹션(useStompSignal → useStompTopic)이 jsdom에서 실 WebSocket을
// 열지 않도록 STOMP 모듈 mock (useStompAccountBalance.test 패턴 정합)
vi.mock('../lib/stomp', () => ({
  useStompTopic: vi.fn(),
  isStompConnected: () => false,
}))

// ──────────────────────────────────────────────
// msw — /api/daytrade/paper/* 스텁
// ──────────────────────────────────────────────

let strategiesResponse: 'two' | 'empty' = 'two'
let positionsResponse: 'one' | 'empty' = 'one'
let lastDeleteUrl = ''

const TWO_STRATEGIES = [
  {
    strategyId: 42,
    origin: 'PAPER_DISCOVERY',
    presetKey: 'DAYTRADE_MOMENTUM_BREAKOUT',
    presetTitle: '모멘텀 돌파 단타',
    codes: ['005930', '000660'],
    intervals: ['1d'],
    virtualCapital: 10_000_000,
    status: 'ACTIVE',
    updatedAt: '2026-06-10T09:30:00Z',
  },
  {
    strategyId: 43,
    origin: 'PAPER_DISCOVERY',
    presetKey: 'DAYTRADE_GAP_RVOL_TURNOVER',
    presetTitle: '갭·RVOL 단타',
    codes: ['035720'],
    intervals: ['1d'],
    virtualCapital: 5_000_000,
    status: 'ACTIVE',
    updatedAt: '2026-06-10T09:35:00Z',
  },
]

const ONE_POSITION = [
  {
    strategyId: 42,
    userId: '74683223',
    code: '005930',
    codeName: '삼성전자',
    quantity: 10,
    avgPrice: 71_000,
    filledLots: 1,
    realizedPnl: 12_000,
    isClosed: false,
    updatedAt: '2026-06-10T10:05:00Z',
  },
]

const server = setupServer(
  http.get('/api/daytrade/paper/strategies', () =>
    HttpResponse.json(strategiesResponse === 'two' ? TWO_STRATEGIES : []),
  ),
  http.get('/api/daytrade/paper/positions', () =>
    HttpResponse.json(positionsResponse === 'one' ? ONE_POSITION : []),
  ),
  http.delete('/api/daytrade/paper/:strategyId', ({ request, params }) => {
    lastDeleteUrl = `${params.strategyId}?${new URL(request.url).search}`
    return new HttpResponse(null, { status: 204 })
  }),
  // 대시보드 시그널 섹션(SignalListPanel)의 조회 — 빈 페이지 (mock 합성 0, 빈 상태 검증용)
  http.get('/api/signals', () =>
    HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
  ),
  http.get('/api/strategies', () =>
    HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
  ),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  strategiesResponse = 'two'
  positionsResponse = 'one'
  lastDeleteUrl = ''
})
afterAll(() => server.close())

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ToastProvider>
          <PaperDashboardPage />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function setUserId(id: number | null) {
  useOnboardingStore.setState({ registeredUserId: id })
}

// ──────────────────────────────────────────────
// A. PaperDashboardPage
// ──────────────────────────────────────────────

describe('PaperDashboardPage — 페이퍼 대시보드 (ADR 0122 R3)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    setUserId(74683223)
  })

  it('userId 미등록 시 셋업 안내', () => {
    setUserId(null)
    renderDashboard()
    expect(screen.getByText('사용자 설정이 완료되지 않았습니다.')).toBeInTheDocument()
  })

  it('전략 2건 + 포지션 1건 → 카드·row + 모의 배지(실거래 아님) 표시', async () => {
    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText('모멘텀 돌파 단타')).toBeInTheDocument()
    })
    expect(screen.getByText('갭·RVOL 단타')).toBeInTheDocument()
    // 가상 자본 표기 (가상 ₩)
    expect(screen.getByText('가상 ₩10,000,000')).toBeInTheDocument()
    // 포지션 row — 삼성전자
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('10주')).toBeInTheDocument()
    // 실현손익 +12,000
    expect(screen.getByText('+12,000')).toBeInTheDocument()
    // 모의 배지(LOCK) — 실거래 오인 0 보증
    expect(screen.getAllByText('모의 · 실거래 아님').length).toBeGreaterThan(0)
    expect(screen.queryByText('모의(페이퍼) 트레이딩 — 실거래 아님')).not.toBeInTheDocument()
  })

  it('활성 모의 전략 카드의 제목은 전략 목록, #ID는 전략 빌더로 연결한다', async () => {
    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText('모멘텀 돌파 단타')).toBeInTheDocument()
    })

    const card = screen.getByLabelText('모의 전략 모멘텀 돌파 단타')
    expect(within(card).getByRole('link', { name: '모멘텀 돌파 단타' })).toHaveAttribute(
      'href',
      '/strategy/list',
    )
    expect(within(card).getByRole('link', { name: '#42' })).toHaveAttribute(
      'href',
      '/strategy/builder?strategyId=42',
    )
  })

  it('빈 응답 → "활성 모의 전략이 없습니다" / "모의 포지션이 없습니다" (mock 합성 0)', async () => {
    strategiesResponse = 'empty'
    positionsResponse = 'empty'
    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText('활성 모의 전략이 없습니다.')).toBeInTheDocument()
    })
    expect(screen.getByText('모의 포지션이 없습니다.')).toBeInTheDocument()
  })

  it('전략 중단 버튼 → DELETE /api/daytrade/paper/{id}?userId 호출', async () => {
    renderDashboard()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('모멘텀 돌파 단타')).toBeInTheDocument()
    })

    const card = screen.getByLabelText('모의 전략 모멘텀 돌파 단타')
    await user.click(within(card).getByRole('button', { name: '전략 중단' }))

    await waitFor(() => {
      expect(lastDeleteUrl).toContain('42?')
      expect(lastDeleteUrl).toContain('userId=74683223')
    })
  })
})

// ──────────────────────────────────────────────
// B. DaytradeMatchTable 다중선택 (페이퍼 승격 입력)
// ──────────────────────────────────────────────

const MATCHES: DaytradeMatchDto[] = [
  {
    rank: 1,
    instrumentCode: '005930',
    instrumentName: '삼성전자',
    score: 0.82,
    scoreComponents: { signal: 0.8, volume: 0.7, atr: 0.6, cap: 0.9 },
    reason:
      '[DAYTRADE_GAP_AND_GO] 갭 앤 고 — {trigger=COMPOSITE, operator=AND, left={trigger=THRESHOLD, operator=>=, lhs=open, lhsValue=70500, rhs=prev_close, rhsValue=69000}, right={trigger=BREAKOUT, lhs=close, lhsValue=71800, rhs=high(20), rhsValue=71200}}',
  },
  {
    rank: 2,
    instrumentCode: '000660',
    instrumentName: 'SK하이닉스',
    score: 0.71,
    scoreComponents: { signal: 0.7, volume: 0.6, atr: 0.5, cap: 0.8 },
    reason: 'BUY 강도 충족',
  },
]

describe('DaytradeMatchTable — 페이퍼 승격 다중선택 (ADR 0122 §8-1)', () => {
  it('selectionMode=true → row 체크박스 노출 + 토글 콜백', async () => {
    const selected = new Set<string>()
    const toggled: string[] = []
    render(
      <MemoryRouter>
        <DaytradeMatchTable
          matches={MATCHES}
          selectionMode
          selectedCodes={selected}
          onToggleSelect={(code) => toggled.push(code)}
        />
      </MemoryRouter>,
    )
    const user = userEvent.setup()

    const checkbox = screen.getByLabelText('삼성전자 모의 승격 선택')
    expect(checkbox).toBeInTheDocument()
    await user.click(checkbox)
    expect(toggled).toContain('005930')
  })

  it('selectionMode=false(default) → 체크박스 미표시', () => {
    render(
      <MemoryRouter>
        <DaytradeMatchTable matches={MATCHES} />
      </MemoryRouter>,
    )
    expect(screen.queryByLabelText('삼성전자 모의 승격 선택')).not.toBeInTheDocument()
  })

  it('row 펼침 → 로컬 신호 해석 + AI 해석 버튼 표시', async () => {
    render(
      <MemoryRouter>
        <DaytradeMatchTable matches={MATCHES} userId={74683223} />
      </MemoryRouter>,
    )
    const user = userEvent.setup()

    await user.click(screen.getByText('삼성전자'))

    expect(screen.getByText('신호 해석')).toBeInTheDocument()
    expect(screen.getByText(/갭 앤 고 조건에서 BUY 후보/)).toBeInTheDocument()
    expect(screen.getByText(/AND 조합/)).toBeInTheDocument()
    expect(screen.getByText('시그널 원문 사유')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '시그널 해석 요청' })).toBeInTheDocument()
  })
})
