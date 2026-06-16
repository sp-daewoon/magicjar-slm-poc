import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { RiskEventsPage } from '../RiskEventsPage'
import { useOnboardingStore } from '../../../stores/onboarding'

let lastRiskEventsQuery: URLSearchParams | null = null

const server = setupServer(
  http.get('/api/risk-events', ({ request }) => {
    const url = new URL(request.url)
    lastRiskEventsQuery = url.searchParams
    return HttpResponse.json({
      content: [
        {
          id: 154,
          userId: '74683223',
          signalId: 270,
          rule: 'POSITION_LIMIT',
          verdict: 'BLOCKED',
          reason: 'Unit price 351500 exceeds remaining position budget 297000 (budget=1000000, existing=703000, min(abs=1000000, pct=1192172.5000))',
          createdAt: '2026-06-02T04:30:01Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  lastRiskEventsQuery = null
  window.localStorage.clear()
  act(() => {
    useOnboardingStore.setState({ registeredUserId: null })
  })
})
afterAll(() => server.close())

function renderPage(initialEntry = '/risk-events') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <RiskEventsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RiskEventsPage', () => {
  it('최근 기간 파라미터를 보내고 리스크 계산식을 표시한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage('/risk-events?verdict=BLOCKED&range=7d')

    await waitFor(() => {
      expect(lastRiskEventsQuery?.get('from')).toBeTruthy()
      expect(lastRiskEventsQuery?.get('to')).toBeTruthy()
    })
    expect(lastRiskEventsQuery?.get('verdict')).toBe('BLOCKED')

    expect(await screen.findByText('A. 포지션 한도 초과')).toBeInTheDocument()
    expect(screen.getByText(/신호 단가 351,500원 > 남은 종목 한도 297,000원/)).toBeInTheDocument()
  })
})
