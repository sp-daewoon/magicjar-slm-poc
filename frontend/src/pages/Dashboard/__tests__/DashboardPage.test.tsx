import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from '../DashboardPage'
import { useOnboardingStore } from '../../../stores/onboarding'
import { useStompAccountBalance } from '../../../api/hooks/useStompAccountBalance'
import { useTrendPreferenceSubscription } from '../../../api/hooks/useTrendPreference'

vi.mock('../../../api/hooks/useStompAccountBalance', () => ({
  useStompAccountBalance: vi.fn(),
}))

vi.mock('../../../api/hooks/useTrendPreference', () => ({
  useTrendPreferenceSubscription: vi.fn(),
}))

vi.mock('../../../api/hooks/usePreferredAccountType', () => ({
  usePreferredAccountType: () => ({
    accountType: 'MOCK',
    setAccountType: vi.fn(),
    isReady: true,
  }),
}))

vi.mock('../../../features/market/components/MarketIndexStrip', () => ({
  MarketIndexStrip: () => <div>시장 지수 위젯</div>,
}))

vi.mock('../../../features/performance/components/PerformancePanel', () => ({
  PerformancePanel: () => <div aria-label="성과 대시보드">성과 패널</div>,
}))

vi.mock('../AccountSummaryCard', () => ({
  AccountSummaryCard: () => <div>계좌 요약 위젯</div>,
}))

vi.mock('../HoldingsTable', () => ({
  HoldingsTable: () => <div>보유 종목 위젯</div>,
}))

vi.mock('../BalanceCard', () => ({
  BalanceCard: () => <div>잔고 상세 위젯</div>,
}))

vi.mock('../AccountTypeToggle', () => ({
  AccountTypeToggle: () => <div>계좌 타입 토글</div>,
}))

function renderPage(initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
  })

  afterEach(() => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: null })
    })
  })

  it('기본 대시보드는 계좌 현황만 마운트하고 성과 패널은 마운트하지 않는다', () => {
    renderPage('/')

    expect(screen.getByRole('tab', { name: '계좌 현황' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('계좌 요약 위젯')).toBeInTheDocument()
    expect(screen.queryByLabelText('성과 대시보드')).not.toBeInTheDocument()
    expect(useStompAccountBalance).toHaveBeenCalledWith({
      userId: 74683223,
      enabled: true,
    })
    expect(useTrendPreferenceSubscription).toHaveBeenCalledWith(74683223)
  })

  it('성과 탭 URL에서는 성과 패널만 마운트하고 계좌 구독은 비활성화한다', () => {
    renderPage('/?view=performance')

    expect(screen.getByRole('tab', { name: '성과' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('성과 대시보드')).toBeInTheDocument()
    expect(screen.getByText('계좌 타입 토글')).toBeInTheDocument()
    expect(screen.queryByText('계좌 요약 위젯')).not.toBeInTheDocument()
    expect(useStompAccountBalance).toHaveBeenCalledWith({
      userId: 74683223,
      enabled: false,
    })
    expect(useTrendPreferenceSubscription).toHaveBeenCalledWith(null)
  })
})
