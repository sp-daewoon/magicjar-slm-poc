import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { act } from 'react'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { TradingJournalPage } from '../TradingJournalPage'
import { useOnboardingStore } from '../../stores/onboarding'

let lastJournalParams: URLSearchParams | null = null

const emptyJournalPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
}

const server = setupServer(
  http.get('/api/journal/entries', ({ request }) => {
    lastJournalParams = new URL(request.url).searchParams
    return HttpResponse.json(emptyJournalPage)
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  vi.useRealTimers()
  lastJournalParams = null
  act(() => {
    useOnboardingStore.setState({ registeredUserId: null })
  })
})
afterAll(() => server.close())

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <TradingJournalPage />
    </QueryClientProvider>,
  )
}

describe('TradingJournalPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 5, 6, 9, 0, 0))
    useOnboardingStore.setState({ registeredUserId: 74683223 })
  })

  it('검색조건은 최신일에서 7일 전 방향으로 표시하고 조회 파라미터는 서버 범위 조건에 맞게 정렬한다', async () => {
    renderPage()

    const fromInput = screen.getByLabelText('기간 시작')
    const toInput = screen.getByLabelText('기간 종료')

    expect(fromInput).toHaveValue('2026-06-06')
    expect(toInput).toHaveValue('2026-05-30')

    await waitFor(() => {
      expect(lastJournalParams?.get('from')).toBe('2026-05-30T00:00:00+09:00')
      expect(lastJournalParams?.get('to')).toBe('2026-06-06T23:59:59+09:00')
    })
    await waitFor(() => {
      expect(screen.getByText('매매일지가 비어있습니다.')).toBeInTheDocument()
    })
  })

  it('종목 검색어는 검색 버튼 클릭 시 instrumentQuery 파라미터로 적용한다', async () => {
    renderPage()

    fireEvent.change(screen.getByRole('searchbox', { name: '종목 검색' }), {
      target: { value: '에코' },
    })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(lastJournalParams?.get('instrumentQuery')).toBe('에코')
      expect(lastJournalParams?.get('instrumentCode')).toBeNull()
    })
  })
})
