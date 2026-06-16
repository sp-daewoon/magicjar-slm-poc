import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { ScenarioReplayPage } from '../ScenarioReplayPage'
import { useOnboardingStore } from '../../../stores/onboarding'
import type { ScenarioReplayRunRequestDto } from '../../../api/scenarioReplay'

vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addCandlestickSeries: () => ({
      setData: vi.fn(),
      setMarkers: vi.fn(),
    }),
    addLineSeries: () => ({
      setData: vi.fn(),
    }),
    applyOptions: vi.fn(),
    remove: vi.fn(),
    subscribeCrosshairMove: vi.fn(),
    timeScale: () => ({
      fitContent: vi.fn(),
      subscribeVisibleLogicalRangeChange: vi.fn(),
    }),
  }),
}))

let lastCreateBody: ScenarioReplayRunRequestDto | null = null

const detailResponse = {
  run: {
    id: 900,
    userId: '74683223',
    strategyId: 43,
    strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
    code: '005930',
    interval: '1m',
    rangeFrom: '2026-06-02T04:00:00Z',
    rangeTo: '2026-06-02T05:10:00Z',
    initialCash: '1000000.0000',
    fillModel: 'NEXT_CANDLE_OPEN',
    status: 'SUCCESS',
    eventCount: 5,
    finalCash: '646000.0000',
    abortReason: null,
    createdAt: '2026-06-03T01:00:00Z',
  },
  events: [
    {
      id: 1,
      sequenceNo: 0,
      virtualTime: '2026-06-02T04:00:00Z',
      type: 'CANDLE',
      severity: 'INFO',
      title: '리플레이 candle',
      message: '가상 시각 2026-06-02T04:00:00Z candle을 평가했습니다.',
      strategyId: 43,
      strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
      code: '005930',
      payload: { open: 351500, high: 352000, low: 351000, close: 351500 },
    },
    {
      id: 2,
      sequenceNo: 1,
      virtualTime: '2026-06-02T04:00:00Z',
      type: 'SIGNAL_HOLD',
      severity: 'INFO',
      title: '리플레이 보류 신호',
      message: '전략 상태가 BUY_HOLD라 리플레이 가상 BUY 주문을 만들지 않았습니다.',
      strategyId: 43,
      strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
      code: '005930',
      payload: {},
    },
    {
      id: 3,
      sequenceNo: 2,
      virtualTime: '2026-06-02T04:01:00Z',
      type: 'SIGNAL_BUY',
      severity: 'INFO',
      title: '리플레이 매수 신호',
      message: '전략 조건이 가상 BUY 신호를 만들었습니다.',
      strategyId: 43,
      strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
      code: '005930',
      payload: {},
    },
    {
      id: 4,
      sequenceNo: 3,
      virtualTime: '2026-06-02T04:02:00Z',
      type: 'ORDER_FILLED',
      severity: 'INFO',
      title: '가상 주문 체결',
      message: '리플레이 가상 BUY 주문이 다음 candle open으로 체결되었습니다.',
      strategyId: 43,
      strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
      code: '005930',
      payload: { side: 'BUY', quantity: 1, fillPrice: '353000' },
    },
    {
      id: 5,
      sequenceNo: 4,
      virtualTime: '2026-06-02T04:03:00Z',
      type: 'POSITION_UPDATED',
      severity: 'INFO',
      title: '가상 포지션 갱신',
      message: '리플레이 포지션이 갱신되었습니다.',
      strategyId: 43,
      strategyName: '모의 학습용 1분봉 MA10 터치 삼성전자',
      code: '005930',
      payload: {},
    },
  ],
  positions: [
    {
      id: 7,
      code: '005930',
      quantity: 1,
      averagePrice: '353000.0000',
      virtualTime: '2026-06-02T04:02:00Z',
    },
  ],
}

const server = setupServer(
  http.get('/api/strategies', () => {
    return HttpResponse.json({
      content: [
        {
          id: 43,
          userId: 74683223,
          name: 'ma_touch_samsung',
          nameKo: '모의 학습용 1분봉 MA10 터치 삼성전자',
          universe: 'KOSPI',
          tf: '1m',
          codes: ['005930'],
          codeNames: { '005930': '삼성전자' },
          intervals: ['1m'],
          buyCondition: 'ma_touch',
          sellCondition: 'profit_take',
          status: 'ACTIVE',
          createdAt: '2026-06-01T00:00:00Z',
        },
      ],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      last: true,
    })
  }),
  http.get('/api/scenario-replay/runs/:runId', () => {
    return HttpResponse.json(detailResponse)
  }),
  http.post('/api/scenario-replay/runs', async ({ request }) => {
    lastCreateBody = await request.json() as ScenarioReplayRunRequestDto
    return HttpResponse.json(detailResponse, { status: 201 })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  lastCreateBody = null
  window.localStorage.clear()
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
      <MemoryRouter initialEntries={['/strategy/scenario-replay']}>
        <ScenarioReplayPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ScenarioReplayPage', () => {
  it('전략 리플레이를 생성하고 가상 체결 타임라인을 표시한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await screen.findByText(/모의 학습용 1분봉 MA10/)
    await user.selectOptions(screen.getByLabelText('전략 선택'), '43')
    await user.click(screen.getByRole('button', { name: '리플레이 실행' }))

    await waitFor(() => expect(lastCreateBody).not.toBeNull())
    expect(lastCreateBody?.userId).toBe('74683223')
    expect(lastCreateBody?.strategyId).toBe(43)
    expect(lastCreateBody?.code).toBe('005930')
    expect(lastCreateBody?.faults).toEqual([])

    expect(await screen.findByText('가상 체결')).toBeInTheDocument()
    expect(screen.getByText('리플레이 차트')).toBeInTheDocument()
    expect(screen.getByText(/리플레이 가상 BUY 주문/)).toBeInTheDocument()
    expect(screen.getByText('가상 포지션')).toBeInTheDocument()
    expect(screen.queryByText('리플레이 candle')).not.toBeInTheDocument()
    expect(screen.queryByText(/BUY_HOLD/)).not.toBeInTheDocument()
    expect(screen.queryByText('HOLD 1')).not.toBeInTheDocument()
    expect(screen.queryByText('보류 요약')).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('보류·캔들 평가 표시'))

    expect(screen.getByText('리플레이 candle')).toBeInTheDocument()
    expect(screen.getByText(/BUY_HOLD/)).toBeInTheDocument()
    expect(screen.getByText('HOLD 1')).toBeInTheDocument()
    expect(screen.getByText('보류 요약')).toBeInTheDocument()

    await user.click(screen.getByLabelText('보류·캔들 평가 표시'))

    expect(screen.queryByText('리플레이 candle')).not.toBeInTheDocument()
    expect(screen.queryByText(/BUY_HOLD/)).not.toBeInTheDocument()
    expect(screen.queryByText('HOLD 1')).not.toBeInTheDocument()
    expect(screen.queryByText('보류 요약')).not.toBeInTheDocument()
  })
})
