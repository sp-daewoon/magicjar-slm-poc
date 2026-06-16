import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import apiClient from '../../../lib/axios'
import { useInstrumentSearch } from '../../../api/hooks/useInstrumentSearch'
import { MarketCalendarPage } from '../pages/MarketCalendarPage'

vi.mock('../../../api/hooks/useInstrumentSearch', () => ({
  useInstrumentSearch: vi.fn(),
}))

function iso(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function buildDays(from: string, to: string) {
  const days = []
  let cursor = new Date(`${from}T00:00:00`)
  const end = new Date(`${to}T00:00:00`)
  while (cursor <= end) {
    const date = iso(cursor)
    const isWeekend = cursor.getDay() === 0 || cursor.getDay() === 6
    days.push({
      date,
      isBusinessDay: date === '2026-06-03' ? false : !isWeekend,
      isHoliday: date === '2026-06-03',
      holidayName: date === '2026-06-03' ? '지방선거일' : null,
      source: date === '2026-06-03' ? 'STATIC_SEED' : null,
      events:
        date === '2026-06-03'
          ? [
              {
                id: 1,
                date,
                market: 'KRX',
                eventType: 'IPO',
                title: '지방 선거일',
                instrumentCode: '123456',
                description: null,
                source: 'MANUAL',
                createdAt: '2026-06-03T00:00:00Z',
                updatedAt: '2026-06-03T00:00:00Z',
              },
            ]
          : [],
    })
    cursor.setDate(cursor.getDate() + 1)
  }
  return days
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MarketCalendarPage />
    </QueryClientProvider>,
  )
}

describe('MarketCalendarPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 5, 6, 9, 0, 0))
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [],
      isLoading: false,
      isError: false,
      isServerError: false,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('일요일 시작 월간 그리드로 휴장일과 사용자 시장 이벤트를 표시한다', async () => {
    vi.spyOn(apiClient, 'get').mockImplementation(async (_url, config) => {
      const params = config?.params as { from: string; to: string }
      return {
        data: {
          from: params.from,
          to: params.to,
          days: buildDays(params.from, params.to),
        },
      }
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('지방선거일').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText('지방 선거일').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: '시장 캘린더' })).toBeInTheDocument()
    expect(screen.getByText('2026년 6월')).toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledWith('/market/calendar', {
      params: { from: '2026-05-31', to: '2026-07-11' },
    })
    expect(screen.getByRole('button', { name: '이전 달' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음 달' })).toBeInTheDocument()
    expect(screen.getByTestId('calendar-day-2026-05-31')).toHaveClass('text-gray-600')
    expect(screen.getByTestId('calendar-day-2026-07-01')).toHaveClass('text-gray-600')
  })

  it('날짜 칸 클릭으로 선택 상태와 폼 날짜를 변경한다', async () => {
    const user = userEvent.setup()
    vi.spyOn(apiClient, 'get').mockImplementation(async (_url, config) => {
      const params = config?.params as { from: string; to: string }
      return {
        data: {
          from: params.from,
          to: params.to,
          days: buildDays(params.from, params.to),
        },
      }
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('2026년 6월')).toBeInTheDocument()
    })

    const dateInput = screen.getByLabelText('날짜')
    const dayCell = screen.getByTestId('calendar-day-2026-06-03')
    await user.click(dayCell)

    expect(dateInput).toHaveValue('2026-06-03')
    expect(dayCell.className).toContain('ring-blue')
  })

  it('한글 종목명 자동완성 선택 후 이벤트 저장 시 6자리 코드만 보낸다', async () => {
    const user = userEvent.setup()
    vi.spyOn(apiClient, 'get').mockImplementation(async (_url, config) => {
      const params = config?.params as { from: string; to: string }
      return {
        data: {
          from: params.from,
          to: params.to,
          days: buildDays(params.from, params.to),
        },
      }
    })
    const postSpy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: {
        id: 2,
        date: '2026-06-06',
        market: 'KRX',
        eventType: 'EARNINGS',
        title: '삼성전자 실적',
        instrumentCode: '005930',
        description: null,
        source: 'MANUAL',
        createdAt: '2026-05-25T00:00:00Z',
        updatedAt: '2026-05-25T00:00:00Z',
      },
    })
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [{ code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1 }],
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    renderPage()

    await user.type(screen.getByLabelText('제목'), '삼성전자 실적')
    await user.type(screen.getByRole('combobox', { name: '종목 검색' }), '삼성전자')
    await user.click(await screen.findByRole('option', { name: /삼성전자/ }))
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => expect(postSpy).toHaveBeenCalled())
    expect(postSpy.mock.calls[0][1]).toMatchObject({
      title: '삼성전자 실적',
      instrumentCode: '005930',
    })
  })
})
