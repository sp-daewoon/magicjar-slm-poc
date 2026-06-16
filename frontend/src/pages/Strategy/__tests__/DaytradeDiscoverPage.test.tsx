import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { act } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { ToastProvider } from '../../../components/ui/ToastProvider'
import { useOnboardingStore } from '../../../stores/onboarding'
import { DaytradeDiscoverPage } from '../DaytradeDiscoverPage'

const activeVolumeBreakout = {
  id: 240,
  key: 'DAYTRADE_VOLUME_BREAKOUT',
  title: '거래량 폭발 20봉 신고가 돌파 setup',
  marketFit: '최근 20봉 채널 상단을 거래량 폭발과 함께 돌파한 후보입니다.',
  entryRules: ['최근 20봉 신고가 돌파', '거래량이 최근 20봉 평균의 2배 이상'],
  entryDsl: 'close > donchian_upper(20) and volume > volume_ma(20) * 2.0',
  targetProfitPct: '2.0000',
  stopLossPct: '1.2000',
  maxHoldBars: 8,
  sortOrder: 240,
  active: true,
  builtIn: true,
  updatedAt: '2026-06-10T00:00:00Z',
}

const inactiveVolatility = {
  id: 210,
  key: 'DAYTRADE_VOLATILITY_EXPANSION',
  title: '장전 변동성 확장·거래량 setup',
  marketFit: '비활성 프리셋입니다.',
  entryRules: ['일중 변동성 확장'],
  entryDsl: 'atr(14) > close * 0.015',
  targetProfitPct: '1.8000',
  stopLossPct: '1.0000',
  maxHoldBars: 8,
  sortOrder: 210,
  active: false,
  builtIn: true,
  updatedAt: '2026-06-10T00:00:00Z',
}

let lastDiscoverBody: Record<string, unknown> | null = null

const server = setupServer(
  http.get('/api/strategy-research/candidates', () =>
    HttpResponse.json([inactiveVolatility, activeVolumeBreakout]),
  ),
  http.get('/api/watchlists', () => HttpResponse.json([])),
  http.post('/api/screener/daytrade-discover', async ({ request }) => {
    lastDiscoverBody = await request.json() as Record<string, unknown>
    return HttpResponse.json({
      presets: [
        {
          presetKey: lastDiscoverBody.presetKey,
          title: '거래량 폭발 20봉 신고가 돌파 setup',
          entryRules: ['최근 20봉 신고가 돌파'],
        },
      ],
      isBuiltin: true,
      universeSize: 0,
      evaluatedCount: 0,
      matchedCount: 0,
      skippedReasons: {},
      matches: [],
      elapsedMs: 12,
      realtimeWatchRegistered: 0,
    })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  lastDiscoverBody = null
  window.localStorage.clear()
  act(() => {
    useOnboardingStore.setState({ registeredUserId: null })
  })
})
afterAll(() => server.close())

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  act(() => {
    useOnboardingStore.setState({ registeredUserId: 74683223 })
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/strategy/daytrade-discover']}>
          <DaytradeDiscoverPage />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('DaytradeDiscoverPage', () => {
  it('DB 활성 DAYTRADE 프리셋만 버튼으로 노출한다', async () => {
    renderPage()

    expect(
      await screen.findByRole('button', { name: '거래량 폭발 20봉 신고가 돌파' }),
    ).toBeInTheDocument()
    expect(screen.queryByText(/변동성 확장/)).not.toBeInTheDocument()
  })

  it('선택한 활성 프리셋 key로 후보 발굴을 요청한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole('button', { name: '거래량 폭발 20봉 신고가 돌파' }),
    )
    await user.click(screen.getByRole('button', { name: '후보 발굴' }))

    await waitFor(() =>
      expect(lastDiscoverBody?.presetKey).toBe('DAYTRADE_VOLUME_BREAKOUT'),
    )
    expect(lastDiscoverBody?.userId).toBe(74683223)
  })
})
