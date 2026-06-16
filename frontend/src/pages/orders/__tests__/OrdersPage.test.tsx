import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OrdersPage } from '../OrdersPage'
import { useOnboardingStore } from '../../../stores/onboarding'
import { useOrders } from '../../../api/hooks/useOrders'
import { useInstrumentSearch } from '../../../api/hooks/useInstrumentSearch'
import { useStompOrder } from '../../../api/hooks/useStompOrder'
import { useRealTradingConsent } from '../../../api/hooks/useUpdateRealTradingConsent'
import { useRuntimeFlags } from '../../../api/hooks/useRuntimeFlags'

vi.mock('../../../api/hooks/useOrders', () => ({
  useOrders: vi.fn(),
}))

vi.mock('../../../api/hooks/useInstrumentSearch', () => ({
  useInstrumentSearch: vi.fn(),
}))

vi.mock('../../../api/hooks/useStompOrder', () => ({
  useStompOrder: vi.fn(),
}))

vi.mock('../../../api/hooks/useUpdateRealTradingConsent', () => ({
  useRealTradingConsent: vi.fn(),
}))

vi.mock('../../../api/hooks/useRuntimeFlags', () => ({
  useRuntimeFlags: vi.fn(),
}))

const emptyOrdersPage = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
  last: true,
}

function renderPage() {
  return render(
    <MemoryRouter>
      <OrdersPage />
    </MemoryRouter>,
  )
}

describe('OrdersPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 5, 6, 9, 0, 0))
    vi.mocked(useOrders).mockReturnValue({
      data: emptyOrdersPage,
      isLoading: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useOrders>)
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [],
      isLoading: false,
      isError: false,
      isServerError: false,
    })
    vi.mocked(useStompOrder).mockReturnValue({
      latestOrderEvent: null,
      orderEventQueue: [],
      clearQueue: vi.fn(),
    })
    vi.mocked(useRealTradingConsent).mockReturnValue({
      data: null,
    } as unknown as ReturnType<typeof useRealTradingConsent>)
    vi.mocked(useRuntimeFlags).mockReturnValue({
      data: { mode: 'MOCK', allowReal: false, userConsented: false },
    } as unknown as ReturnType<typeof useRuntimeFlags>)
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: null })
    })
  })

  it('기본 기간은 오늘부터 7일 전으로 표시하고 주문 조회 요청은 오래된 날짜부터 보낸다', async () => {
    renderPage()

    expect(screen.getByLabelText('기간 시작')).toHaveValue('2026-06-06')
    expect(screen.getByLabelText('기간 종료')).toHaveValue('2026-05-30')

    await waitFor(() => {
      expect(useOrders).toHaveBeenLastCalledWith(
        expect.objectContaining({
          userId: 74683223,
          from: '2026-05-29T15:00:00.000Z',
          to: '2026-06-06T14:59:59.999Z',
          page: 0,
          size: 20,
        }),
      )
    })
  })

  it('한글 종목명 검색 결과가 하나면 Enter 선택으로 코드 필터를 적용한다', async () => {
    const user = userEvent.setup()
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1 },
      ],
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    renderPage()

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.type(input, '삼성전자')

    await waitFor(() => {
      expect(screen.getByRole('option', { name: /삼성전자/ })).toBeInTheDocument()
    })
    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(input).toHaveValue('삼성전자 (005930)')
      expect(useOrders).toHaveBeenLastCalledWith(
        expect.objectContaining({
          code: '005930',
        }),
      )
    })
  })
})
