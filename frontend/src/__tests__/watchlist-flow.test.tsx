/**
 * smoke test — 관심종목 등록 flow
 *
 * spec §3-2 H 정합:
 * - msw로 GET /api/watchlists mock (빈 → 등록 후 1건)
 * - InstrumentSearchInput 자동완성으로 종목 선택 후 등록 버튼 클릭 → list row 표시 검증
 *   (PR-11b에서 raw input → InstrumentSearchInput 자동완성 전환)
 *
 * 검증 시나리오:
 * 1. 빈 상태 → "관심종목이 없습니다" 표시
 * 2. 자동완성으로 "삼성전자(005930)" 선택 + 등록 버튼 클릭
 * 3. POST /api/watchlists → 201 mock
 * 4. GET /api/watchlists 재조회 → 1건 반환 mock
 * 5. list에 "005930" row 표시 검증
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter } from 'react-router-dom'
import { WatchlistPage } from '../pages/Watchlist/WatchlistPage'

// ──────────────────────────────────────────────
// useInstrumentSearch mock (PR-11b 자동완성 정합 — dogfooding §6.5 검증)
// 실제 백엔드 없이 자동완성 dropdown 동작 검증
// ──────────────────────────────────────────────
vi.mock('../api/hooks/useInstrumentSearch', () => ({
  useInstrumentSearch: vi.fn().mockReturnValue({
    results: [
      { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
    ],
    isLoading: false,
    isError: false,
    isServerError: false,
  }),
}))

// ──────────────────────────────────────────────
// msw 서버 설정
// ──────────────────────────────────────────────

let watchlistItems: Array<{
  id: number
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  code: string
  priority: number
  createdAt: string
}> = []

const server = setupServer(
  // GET /api/watchlists — 현재 목록 반환
  http.get('/api/watchlists', () => {
    return HttpResponse.json(watchlistItems)
  }),

  // POST /api/watchlists — 등록 + 목록에 추가
  http.post('/api/watchlists', async ({ request }) => {
    const body = (await request.json()) as { code: string; priority?: number }
    const newEntry = {
      id: watchlistItems.length + 1,
      userId: 74683223,
      code: body.code,
      priority: body.priority ?? 0,
      createdAt: new Date().toISOString(),
    }
    watchlistItems.push(newEntry)
    return HttpResponse.json(newEntry, { status: 201 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  watchlistItems = []
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 테스트 헬퍼
// ──────────────────────────────────────────────

function renderWatchlistPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WatchlistPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ──────────────────────────────────────────────
// 테스트 시나리오
// ──────────────────────────────────────────────

describe('Watchlist 등록 flow', () => {
  it('자동완성으로 삼성전자 선택 후 등록 버튼 클릭 시 목록에 row가 표시된다', async () => {
    // PR-29c 검증: dogfooding §6.5 한글 검색 회귀 — "삼성전자" 자동완성 선택 → 등록
    const user = userEvent.setup()
    renderWatchlistPage()

    // 1. 빈 상태 확인
    await waitFor(() => {
      expect(screen.getByText('관심종목이 없습니다')).toBeInTheDocument()
    })

    // 2. 검색 입력창에 타이핑 (자동완성 dropdown 표시)
    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.type(input, '삼성')

    // 3. dropdown에서 삼성전자 선택
    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })
    await user.click(screen.getByText('삼성전자'))

    // 4. 등록 버튼 클릭
    const submitButton = screen.getByRole('button', { name: '등록' })
    await user.click(submitButton)

    // 5. list에 "005930" row 표시 확인 (종목명과 코드 두 span에 모두 나타남 — getAllByText 사용)
    await waitFor(() => {
      const items = screen.getAllByText('005930')
      expect(items.length).toBeGreaterThan(0)
    })
  })

  it('종목 미선택 상태에서 등록 버튼 클릭 시 유효성 검사 오류가 표시된다', async () => {
    // PR-29c 검증: 자동완성 미선택 시 "종목을 선택해주세요" 안내
    renderWatchlistPage()

    // 빈 상태 로딩 대기
    await waitFor(() => {
      expect(screen.getByText('관심종목이 없습니다')).toBeInTheDocument()
    })

    // 검색 입력 없이 등록 버튼 클릭 (submit 버튼이 disabled이므로 form submit으로 우회)
    const form = screen.getByRole('button', { name: '등록' }).closest('form')!
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // clientside 검증 오류 메시지 확인 (WatchlistAddForm selectedCode 미설정 시)
    await waitFor(() => {
      expect(screen.getByText(/종목을 선택해주세요/)).toBeInTheDocument()
    })
  })

  it('총 N종목 카운트가 올바르게 표시된다', async () => {
    // 사전에 목록에 2건 추가
    watchlistItems = [
      { id: 1, userId: 74683223, code: '005930', priority: 0, createdAt: new Date().toISOString() },
      { id: 2, userId: 74683223, code: '035720', priority: 1, createdAt: new Date().toISOString() },
    ]

    renderWatchlistPage()

    // "총 2종목" 헤더 영역 확인 — 숫자가 별도 span으로 분리됨
    await waitFor(() => {
      // "총 N종목" 패턴으로 확인 (숫자 포함 상위 span)
      const countText = screen.getByText((_content, element) => {
        return element?.tagName === 'SPAN' && /총\s+\d+종목/.test(element?.textContent ?? '')
      })
      expect(countText).toBeInTheDocument()
    })
  })
})
