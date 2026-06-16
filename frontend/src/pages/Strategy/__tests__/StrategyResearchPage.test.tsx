import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyResearchPage } from '../StrategyResearchPage'
import { useOnboardingStore } from '../../../stores/onboarding'
import { kstInputToIso } from '../../../components/scenario/scenarioReplayTime'
import type {
  StrategyResearchAiDiscoveryRequestDto,
  StrategyResearchRequestDto,
} from '../../../api/strategyResearch'

vi.mock('../../../lib/stomp', () => ({
  useStompTopic: () => {},
  getStompClient: () => ({ connected: false, active: false }),
}))

vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addCandlestickSeries: () => ({
      setData: vi.fn(),
      setMarkers: vi.fn(),
    }),
    remove: vi.fn(),
    subscribeCrosshairMove: vi.fn(),
    timeScale: () => ({
      fitContent: vi.fn(),
      subscribeVisibleLogicalRangeChange: vi.fn(),
    }),
  }),
}))

let lastResearchBody: StrategyResearchRequestDto | null = null
let lastDiscoveryBody: StrategyResearchAiDiscoveryRequestDto | null = null
let lastRecollectJobUrl: string | null = null
const ONE_DAY_MS = 24 * 60 * 60 * 1000
const RESEARCH_FORM_STORAGE_KEY = 'magicjar.strategyResearch.form.v1'

const server = setupServer(
  http.get('/api/instruments/search', ({ request }) => {
    const query = new URL(request.url).searchParams.get('q') ?? ''
    return HttpResponse.json({
      query,
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
      ],
      totalMatched: 1,
      limit: 10,
    })
  }),
  http.post('/api/strategy-research/proposals', async ({ request }) => {
    lastResearchBody = await request.json() as StrategyResearchRequestDto
    return HttpResponse.json({
      request: lastResearchBody,
      warnings: [],
      marketRegime: {
        type: 'UPTREND',
        trendPct: 1.2,
        volatilityPct: 2.1,
        explanation: '상승 추세',
      },
      candleCount: 1,
      candles: [
        {
          ts: '2026-06-02T00:00:00Z',
          open: 70000,
          high: 71000,
          low: 69000,
          close: 70500,
          volume: 1000,
        },
      ],
      candidates: [],
    })
  }),
  http.get('/api/strategy-research/ai-discovery/presets', () => {
    return HttpResponse.json([
      {
        id: 'scalping_momentum',
        label: '단기 거래대금 폭발',
        description: '5분봉 검증 후보',
        promptText: '거래대금과 일중 변동성이 큰 후보를 찾아라.',
        sortOrder: 10,
        active: true,
        builtIn: true,
        updatedAt: '2026-06-06T00:00:00Z',
      },
      {
        id: 'sector_rotation_swing',
        label: '섹터 순환매 대기',
        description: '스윙 검증 후보',
        promptText: '주도 섹터 후속 순환매 후보를 찾아라.',
        sortOrder: 20,
        active: true,
        builtIn: true,
        updatedAt: '2026-06-06T00:00:00Z',
      },
    ])
  }),
  http.post('/api/strategy-research/ai-discovery/prompt-preview', async ({ request }) => {
    const body = await request.json() as { query: string; presetId?: string | null }
    return HttpResponse.json({
      systemPrompt: 'core wrapper',
      preset: body.presetId
        ? {
            id: body.presetId,
            label: '단기 거래대금 폭발',
            description: '5분봉 검증 후보',
            promptText: '거래대금과 일중 변동성이 큰 후보를 찾아라.',
            sortOrder: 10,
            active: true,
            builtIn: true,
            updatedAt: '2026-06-06T00:00:00Z',
          }
        : null,
      userQuery: body.query,
      finalPrompt: `core wrapper\n${body.query}`,
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: [],
    })
  }),
  http.put('/api/strategy-research/ai-discovery/presets/:presetId', async ({ params, request }) => {
    const body = await request.json() as {
      label: string
      description: string
      promptText: string
      sortOrder: number
      active: boolean
    }
    return HttpResponse.json({
      id: String(params.presetId),
      ...body,
      builtIn: true,
      updatedAt: '2026-06-06T00:00:00Z',
    })
  }),
  http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
    lastDiscoveryBody = await request.json() as StrategyResearchAiDiscoveryRequestDto
    return HttpResponse.json({
      query: lastDiscoveryBody.query,
      presetId: lastDiscoveryBody.presetId ?? null,
      market: lastDiscoveryBody.market ?? null,
      limit: lastDiscoveryBody.limit,
      provider: 'claude-cli-bridge',
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: ['AI 후보는 검증용입니다.'],
      items: [
        {
          itemKey: 'CODE:005930',
          code: '005930',
          name: '삼성전자',
          market: 'KOSPI',
          sector: '전기전자',
          theme: '반도체',
          aiReason: '반도체와 AI 인프라 후보',
          themes: ['반도체', 'AI 인프라'],
          confidence: 0.82,
          matched: true,
          rawName: '삼성전자',
          rawCode: '005930',
          tradingHalt: false,
          managementStock: false,
          status: 'NEW',
        },
      ],
    })
  }),
  http.get('/api/strategy-research/ai-discovery/latest', () => {
    return HttpResponse.json({ report: null })
  }),
  http.patch('/api/strategy-research/ai-discovery/item-status', async ({ request }) => {
    const body = await request.json() as { itemKey: string; status: 'NEW' | 'KEPT' | 'DISMISSED' }
    return HttpResponse.json({
      query: '반도체 후보',
      presetId: 'scalping_momentum',
      market: null,
      limit: 6,
      provider: 'claude-cli-bridge',
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: [],
      items: [
        {
          itemKey: body.itemKey,
          code: '005930',
          name: '삼성전자',
          market: 'KOSPI',
          sector: '전기전자',
          theme: '반도체',
          aiReason: '반도체와 AI 인프라 후보',
          themes: ['반도체', 'AI 인프라'],
          confidence: 0.82,
          matched: true,
          rawName: '삼성전자',
          rawCode: '005930',
          tradingHalt: false,
          managementStock: false,
          status: body.status,
        },
      ],
    })
  }),
  http.delete('/api/strategy-research/ai-discovery/latest', () => {
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/strategy-research/ai-discovery/core-prompt', () => {
    return HttpResponse.json({
      promptText: '기본 Core Wrapper 지시문',
      isDefault: true,
      updatedAt: null,
    })
  }),
  http.post('/api/symbols/:code/candles/recollect/jobs', ({ request, params }) => {
    lastRecollectJobUrl = request.url
    return HttpResponse.json(
      {
        jobId: 'job-1',
        code: String(params.code),
        intervals: ['1d'],
        status: 'QUEUED',
        topic: `/topic/candle.recollect.${String(params.code)}.job-1`,
        submittedAt: '2026-06-06T00:00:00Z',
      },
      { status: 202 },
    )
  }),
  http.get('/api/symbols/:code/candles/recollect/jobs/:jobId', ({ params }) => {
    return HttpResponse.json({
      jobId: String(params.jobId),
      code: String(params.code),
      intervals: ['1d'],
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      processedDays: null,
      totalDays: null,
      deletedCount: 0,
      insertedCount: 1,
      rebuiltCount: 0,
      message: '캔들 재수집 완료',
      elapsedMs: 100,
      error: null,
      updatedAt: '2026-06-06T00:00:01Z',
    })
  }),
  http.get('/api/strategy-research/candidates', () =>
    HttpResponse.json([
      {
        id: 1,
        key: 'MA10_RECLAIM_SCALP',
        title: 'MA10 회복 반등 스캘프',
        marketFit: '하락 추세 중 단기 평균선 회복',
        entryRules: ['직전 종가가 MA10 아래'],
        entryDsl: 'close crosses_above ma(10) and close >= open',
        targetProfitPct: '1.5000',
        stopLossPct: '0.8000',
        maxHoldBars: 12,
        sortOrder: 10,
        active: true,
        builtIn: true,
        updatedAt: '2026-06-06T00:00:00Z',
      },
    ]),
  ),
  http.get('/api/strategy', () => HttpResponse.json([])),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  lastResearchBody = null
  lastDiscoveryBody = null
  lastRecollectJobUrl = null
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
      <MemoryRouter initialEntries={['/strategy/research']}>
        <StrategyResearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function datePartToUtcDay(value: string): number {
  const [year, month, day] = value.slice(0, 10).split('-').map(Number)
  return Date.UTC(year, month - 1, day)
}

describe('StrategyResearchPage', () => {
  it('리서치 종목 입력은 비어 있고 기본 리서치 조건은 1주일·익절 3%·손절 3%로 시작한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    expect(await screen.findByRole('combobox', { name: '종목 검색' })).toHaveValue('')
    expect(screen.queryByText(/선택:/)).not.toBeInTheDocument()
    const fromInput = screen.getByLabelText('시작 시각 (KST)') as HTMLInputElement
    const toInput = screen.getByLabelText('종료 시각 (KST)') as HTMLInputElement
    expect((datePartToUtcDay(toInput.value) - datePartToUtcDay(fromInput.value)) / ONE_DAY_MS).toBe(7)
    expect(fromInput.value.endsWith('T09:00')).toBe(true)
    expect(toInput.value.endsWith('T15:30')).toBe(true)
    expect(screen.getByLabelText('익절 %')).toHaveValue(3)
    expect(screen.getByLabelText('손절 %')).toHaveValue(3)
  })

  it('저장된 리서치 조건을 다음 진입 때 복원한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    window.localStorage.setItem(
      RESEARCH_FORM_STORAGE_KEY,
      JSON.stringify({
        interval: '5m',
        fromKst: '2026-06-01T09:00',
        toKst: '2026-06-05T15:30',
        targetProfitPct: 4,
        stopLossPct: 2.5,
        maxHoldBars: 8,
      }),
    )

    renderPage()

    expect(await screen.findByLabelText('캔들')).toHaveValue('5m')
    expect(screen.getByLabelText('시작 시각 (KST)')).toHaveValue('2026-06-01T09:00')
    expect(screen.getByLabelText('종료 시각 (KST)')).toHaveValue('2026-06-05T15:30')
    expect(screen.getByLabelText('익절 %')).toHaveValue(4)
    expect(screen.getByLabelText('손절 %')).toHaveValue(2.5)
    expect(screen.getByLabelText('보유')).toHaveValue(8)
  })

  it('종목명 자동완성 선택 후 리서치 요청에는 6자리 코드만 보낸다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.clear(input)
    await user.type(input, '삼성전자')

    await user.click(await screen.findByRole('option', { name: /삼성전자/ }))
    await user.click(screen.getByRole('button', { name: '리서치' }))

    await waitFor(() => expect(lastResearchBody).not.toBeNull())
    expect(lastResearchBody?.code).toBe('005930')
    expect(lastResearchBody?.userId).toBe('74683223')
  })

  it('AI 종목발굴 후보 선택 후 기존 리서치 요청으로 이어진다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '선택' }))
    await user.click(screen.getByRole('button', { name: '리서치' }))

    await waitFor(() => expect(lastResearchBody).not.toBeNull())
    const fromInput = screen.getByLabelText('시작 시각 (KST)') as HTMLInputElement
    const toInput = screen.getByLabelText('종료 시각 (KST)') as HTMLInputElement
    expect(lastDiscoveryBody?.userId).toBe('74683223')
    expect(lastDiscoveryBody?.interval).toBe('1d')
    expect(lastDiscoveryBody?.from).toBe(kstInputToIso(fromInput.value))
    expect(lastDiscoveryBody?.to).toBe(kstInputToIso(toInput.value))
    expect(lastResearchBody?.code).toBe('005930')
  })

  it('예상 candle 수가 2만 개를 넘으면 리서치 요청 전에 차단한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.type(input, '삼성전자')
    await user.click(await screen.findByRole('option', { name: /삼성전자/ }))
    await user.selectOptions(screen.getByLabelText('캔들'), '1m')
    fireEvent.change(screen.getByLabelText('시작 시각 (KST)'), { target: { value: '2026-03-02T09:00' } })
    fireEvent.change(screen.getByLabelText('종료 시각 (KST)'), { target: { value: '2026-06-02T15:30' } })
    await user.click(screen.getByRole('button', { name: '리서치' }))

    expect(await screen.findByText(/예상 candle 수가 너무 많습니다/)).toBeInTheDocument()
    expect(lastResearchBody).toBeNull()
  })

  it('AI 종목발굴 후보에 캔들이 없으면 선택을 막는다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
        lastDiscoveryBody = await request.json() as StrategyResearchAiDiscoveryRequestDto
        return HttpResponse.json({
          query: lastDiscoveryBody.query,
          presetId: lastDiscoveryBody.presetId ?? null,
          market: lastDiscoveryBody.market ?? null,
          limit: lastDiscoveryBody.limit,
          provider: 'claude-cli-bridge',
          generatedAt: '2026-06-06T00:00:00Z',
          warnings: ['선택한 interval·기간에 리서치 가능한 candle이 부족한 후보 1건은 캔들 가져오기 후 리서치할 수 있습니다.'],
          items: [
            {
              itemKey: 'CODE:028300',
              code: '028300',
              name: 'HLB',
              market: 'KOSDAQ',
              sector: '1024',
              theme: '바이오',
              aiReason: '검증 후보',
              themes: ['바이오'],
              confidence: 0.6,
              matched: true,
              rawName: 'HLB',
              rawCode: '028300',
              tradingHalt: false,
              managementStock: false,
              status: 'NEW',
              candleCount: 0,
              hasResearchCandles: false,
            },
          ],
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByText('캔들 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택' })).toBeDisabled()
  })

  it('AI 종목발굴 진행 중 로딩 상태를 보여준다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
        lastDiscoveryBody = await request.json() as StrategyResearchAiDiscoveryRequestDto
        await new Promise((resolve) => window.setTimeout(resolve, 80))
        return HttpResponse.json({
          query: lastDiscoveryBody.query,
          presetId: lastDiscoveryBody.presetId ?? null,
          market: lastDiscoveryBody.market ?? null,
          limit: lastDiscoveryBody.limit,
          provider: 'claude-cli-bridge',
          generatedAt: '2026-06-06T00:00:00Z',
          warnings: [],
          items: [
            {
              itemKey: 'CODE:005930',
              code: '005930',
              name: '삼성전자',
              market: 'KOSPI',
              sector: '전기전자',
              theme: '반도체',
              aiReason: '반도체와 AI 인프라 후보',
              themes: ['반도체'],
              confidence: 0.82,
              matched: true,
              rawName: '삼성전자',
              rawCode: '005930',
              tradingHalt: false,
              managementStock: false,
              status: 'NEW',
            },
          ],
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Claude CLI가 후보를 찾는 중입니다')
    expect(screen.getByRole('button', { name: '발굴 중' })).toBeDisabled()
    expect(screen.getByLabelText('프롬프트')).toBeDisabled()

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()
  })

  it('AI 종목발굴 후보를 유지하거나 제외할 수 있다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '유지' }))
    expect(await screen.findByText('유지 해제')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '제외' }))
    await waitFor(() => expect(screen.queryByText('반도체와 AI 인프라 후보')).not.toBeInTheDocument())
  })

  it('유지 후보가 표시 개수보다 많으면 AI 발굴 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', () => {
        return HttpResponse.json({
          report: {
            query: '한국 주식중 가장 핫한 주식을 찾아줘',
            presetId: 'scalping_momentum',
            market: null,
            limit: 3,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: [],
            items: [
              {
                itemKey: 'CODE:005930',
                code: '005930',
                name: '삼성전자',
                market: 'KOSPI',
                sector: '전기전자',
                theme: '반도체',
                aiReason: '첫 번째 유지 후보',
                themes: ['반도체'],
                confidence: 0.8,
                matched: true,
                rawName: '삼성전자',
                rawCode: '005930',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
              {
                itemKey: 'CODE:042700',
                code: '042700',
                name: '한미반도체',
                market: 'KOSPI',
                sector: '기계',
                theme: 'HBM',
                aiReason: '두 번째 유지 후보',
                themes: ['HBM'],
                confidence: 0.8,
                matched: true,
                rawName: '한미반도체',
                rawCode: '042700',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
              {
                itemKey: 'CODE:058470',
                code: '058470',
                name: '리노공업',
                market: 'KOSDAQ',
                sector: '반도체',
                theme: '테스트',
                aiReason: '세 번째 유지 후보',
                themes: ['테스트'],
                confidence: 0.8,
                matched: true,
                rawName: '리노공업',
                rawCode: '058470',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('첫 번째 유지 후보')).toBeInTheDocument()
    const limitInput = screen.getByLabelText('표시 개수')
    await user.clear(limitInput)
    await user.type(limitInput, '2')
    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByText(/유지 후보가 3건이라 표시 개수는 최소 3건입니다/)).toBeInTheDocument()
    expect(lastDiscoveryBody).toBeNull()
  })

  it('매칭 후보에는 "매칭" 뱃지를 표시하지 않고, 비매칭 후보에만 "확인필요"를 표시한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', () => {
        return HttpResponse.json({
          report: {
            query: '반도체 후보',
            market: null,
            limit: 6,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: [],
            items: [
              {
                itemKey: 'CODE:005930',
                code: '005930',
                name: '삼성전자',
                market: 'KOSPI',
                sector: '전기전자',
                theme: '반도체',
                aiReason: '매칭된 후보',
                themes: ['반도체'],
                confidence: 0.8,
                matched: true,
                rawName: '삼성전자',
                rawCode: '005930',
                tradingHalt: false,
                managementStock: false,
                status: 'NEW',
                candleCount: 1200,
                hasResearchCandles: true,
              },
              {
                itemKey: 'RAW:미상장종목',
                code: null,
                name: '미상장종목',
                market: null,
                sector: null,
                theme: null,
                aiReason: '비매칭 후보',
                themes: [],
                confidence: 0.5,
                matched: false,
                rawName: '미상장종목',
                rawCode: null,
                tradingHalt: false,
                managementStock: false,
                status: 'NEW',
                candleCount: null,
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('매칭된 후보')).toBeInTheDocument()
    expect(screen.queryByText('매칭')).not.toBeInTheDocument()
    expect(screen.getByText('확인필요')).toBeInTheDocument()
    expect(screen.queryByText('캔들 1,200')).not.toBeInTheDocument()
    expect(screen.queryByText('캔들 있음')).not.toBeInTheDocument()
    expect(screen.queryByText('캔들 없음')).not.toBeInTheDocument()
  })

  it('캔들 0건 매칭 후보에는 "캔들 가져오기" 버튼과 전체 가져오기 버튼을 노출하고 선택을 막는다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', () => {
        return HttpResponse.json({
          report: {
            query: '반도체 후보',
            market: null,
            limit: 6,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: [],
            items: [
              {
                itemKey: 'CODE:005930',
                code: '005930',
                name: '삼성전자',
                market: 'KOSPI',
                sector: '전기전자',
                theme: '반도체',
                aiReason: '캔들 없는 후보',
                themes: ['반도체'],
                confidence: 0.8,
                matched: true,
                rawName: '삼성전자',
                rawCode: '005930',
                tradingHalt: false,
                managementStock: false,
                status: 'NEW',
                candleCount: 0,
                hasResearchCandles: false,
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('캔들 없는 후보')).toBeInTheDocument()
    expect(screen.getByText('캔들 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '캔들 가져오기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '부족 후보 전체 가져오기' })).toBeInTheDocument()
    expect(screen.getByText(/리서치 캔들이 부족한 매칭 후보 1건/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택' })).toBeDisabled()

    const fromInput = screen.getByLabelText('시작 시각 (KST)') as HTMLInputElement
    const toInput = screen.getByLabelText('종료 시각 (KST)') as HTMLInputElement
    await user.click(screen.getByRole('button', { name: '캔들 가져오기' }))
    await waitFor(() => expect(lastRecollectJobUrl).not.toBeNull())
    const url = new URL(lastRecollectJobUrl!)
    expect(url.searchParams.get('intervals')).toBe('1d')
    expect(url.searchParams.get('from')).toBe(kstInputToIso(fromInput.value))
    expect(url.searchParams.get('to')).toBe(kstInputToIso(toInput.value))
    expect(url.searchParams.get('paddingTradingDays')).toBe('5')
  })

  it('캔들이 일부 있지만 40개 미만이면 "캔들 부족"으로 표시한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', () => {
        return HttpResponse.json({
          report: {
            query: '부족 후보',
            market: null,
            limit: 6,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: [],
            items: [
              {
                itemKey: 'CODE:038110',
                code: '038110',
                name: '에코플라스틱',
                market: 'KOSDAQ',
                sector: '운송장비·부품',
                theme: '자동차 부품',
                aiReason: '일부 캔들은 있지만 리서치 최소 수량은 부족한 후보',
                themes: ['자동차 부품'],
                confidence: 0.8,
                matched: true,
                rawName: '에코플라스틱',
                rawCode: '038110',
                tradingHalt: false,
                managementStock: false,
                status: 'NEW',
                candleCount: 30,
                hasResearchCandles: false,
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('에코플라스틱')).toBeInTheDocument()
    expect(screen.getByText('캔들 부족')).toBeInTheDocument()
    expect(screen.queryByText('캔들 없음')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '캔들 가져오기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택' })).toBeDisabled()
  })

  it('발굴 영역을 사용자가 건드린 뒤 interval을 바꿔도 최신 캔들 가용 여부만 병합한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', ({ request }) => {
        const interval = new URL(request.url).searchParams.get('interval')
        const hasResearchCandles = interval === '5m'
        return HttpResponse.json({
          report: {
            query: '전력기기 후보',
            market: null,
            limit: 6,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: hasResearchCandles ? [] : ['선택한 interval·기간에 리서치 가능한 candle이 부족한 후보 1건은 캔들 가져오기 후 리서치할 수 있습니다.'],
            items: [
              {
                itemKey: 'CODE:267260',
                code: '267260',
                name: 'HD현대일렉트릭',
                market: 'KOSPI',
                sector: '전기전자',
                theme: '전력기기',
                aiReason: '전력기기 검증 후보',
                themes: ['전력기기'],
                confidence: 0.8,
                matched: true,
                rawName: 'HD현대일렉트릭',
                rawCode: '267260',
                tradingHalt: false,
                managementStock: false,
                status: 'NEW',
                candleCount: hasResearchCandles ? 556 : 0,
                hasResearchCandles,
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('HD현대일렉트릭')).toBeInTheDocument()
    expect(screen.getByText('캔들 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '부족 후보 전체 가져오기' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '직접 입력' }))
    await user.selectOptions(screen.getByLabelText('캔들'), '5m')

    await waitFor(() => expect(screen.queryByText('캔들 없음')).not.toBeInTheDocument())
    expect(screen.queryByText('캔들 부족')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '부족 후보 전체 가져오기' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택' })).toBeEnabled()
  })

  it('candleCount 정보가 없으면(undefined) 캔들 가져오기 UI를 노출하지 않는다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    // 기본 ai-discovery POST 핸들러는 candleCount 미포함 → 가져오기 UI 없음, 선택 활성
    await screen.findByRole('button', { name: '발굴' })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '발굴' }))
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '캔들 가져오기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '부족 후보 전체 가져오기' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택' })).toBeEnabled()
  })

  it('프리셋 설정 팝업에서 Core Wrapper를 수정·저장하고 사용자 수정본 뱃지를 보여준다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    const coreBodyRef: { value: { promptText: string } | null } = { value: null }
    server.use(
      http.put('/api/strategy-research/ai-discovery/core-prompt', async ({ request }) => {
        coreBodyRef.value = (await request.json()) as { promptText: string }
        return HttpResponse.json({
          promptText: coreBodyRef.value.promptText,
          isDefault: false,
          updatedAt: '2026-06-06T01:00:00Z',
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '프리셋 설정' }))
    const coreTextarea = await screen.findByLabelText('Core Wrapper 프롬프트')
    await waitFor(() => expect(coreTextarea).toHaveValue('기본 Core Wrapper 지시문'))

    await user.clear(coreTextarea)
    await user.type(coreTextarea, '수정된 전역 지시문')
    await user.click(screen.getByRole('button', { name: 'Core Wrapper 저장' }))

    await waitFor(() => expect(coreBodyRef.value).not.toBeNull())
    expect(coreBodyRef.value?.promptText).toBe('수정된 전역 지시문')
    expect(await screen.findByText('사용자 수정본')).toBeInTheDocument()
    expect(screen.getByText('Core Wrapper를 저장했습니다')).toBeInTheDocument()

    // 회귀(2026-06-06 사용자 보고): 저장 직후 stale GET 캐시가 draft를 기본값으로 되돌려
    // textarea에 기본값이 다시 보이고, 그 상태로 재저장하면 수정본이 유실되던 버그.
    // 저장 후에도 textarea는 저장본을 유지해야 한다.
    await waitFor(() => expect(coreTextarea).toHaveValue('수정된 전역 지시문'))
    expect(coreTextarea).not.toHaveValue('기본 Core Wrapper 지시문')
  })

  it('프리셋 설정 팝업을 ESC로 닫는다 (useModalBehavior 공용 훅 — 2026-06-07 모달 UX 통일)', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await user.click(screen.getByRole('button', { name: '프리셋 설정' }))
    const dialog = await screen.findByRole('dialog', { name: 'AI 종목발굴 프리셋 설정' })
    expect(dialog).toBeInTheDocument()
    // 팝업 열림 동안 배경 스크롤 잠금
    expect(document.body.style.overflow).toBe('hidden')

    await user.keyboard('{Escape}')

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'AI 종목발굴 프리셋 설정' })).not.toBeInTheDocument(),
    )
    // 닫힌 뒤 스크롤 잠금 해제
    expect(document.body.style.overflow).toBe('')
  })

  it('Core Wrapper를 빈 값으로 저장하면 클라이언트에서 차단한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    let putCalled = false
    server.use(
      http.put('/api/strategy-research/ai-discovery/core-prompt', () => {
        putCalled = true
        return HttpResponse.json({ promptText: '', isDefault: false, updatedAt: null })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '프리셋 설정' }))
    const coreTextarea = await screen.findByLabelText('Core Wrapper 프롬프트')
    await waitFor(() => expect(coreTextarea).toHaveValue('기본 Core Wrapper 지시문'))

    await user.clear(coreTextarea)
    await user.click(screen.getByRole('button', { name: 'Core Wrapper 저장' }))

    expect(await screen.findByText('Core Wrapper 프롬프트를 입력해주세요')).toBeInTheDocument()
    expect(putCalled).toBe(false)
  })

  it('Core Wrapper 기본값 복원은 confirm 후 reset을 호출한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    let resetCalled = false
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    server.use(
      http.post('/api/strategy-research/ai-discovery/core-prompt/reset', () => {
        resetCalled = true
        return HttpResponse.json({
          promptText: '기본 Core Wrapper 지시문',
          isDefault: true,
          updatedAt: null,
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '프리셋 설정' }))
    await screen.findByLabelText('Core Wrapper 프롬프트')
    await user.click(screen.getByRole('button', { name: '기본값 복원' }))

    await waitFor(() => expect(resetCalled).toBe(true))
    expect(await screen.findByText('Core Wrapper를 기본값으로 복원했습니다')).toBeInTheDocument()
    confirmSpy.mockRestore()
  })

  it('프리셋 삭제는 confirm 후 DELETE를 호출하고 목록을 다시 불러온다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    const deletedIds: string[] = []
    let presetsFetchCount = 0
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    server.use(
      http.get('/api/strategy-research/ai-discovery/presets', () => {
        presetsFetchCount += 1
        const items = [
          {
            id: 'scalping_momentum',
            label: '단기 거래대금 폭발',
            description: '5분봉 검증 후보',
            promptText: '거래대금과 일중 변동성이 큰 후보를 찾아라.',
            sortOrder: 10,
            active: true,
            builtIn: true,
            updatedAt: '2026-06-06T00:00:00Z',
          },
        ]
        return HttpResponse.json(
          deletedIds.includes('scalping_momentum') ? [] : items,
        )
      }),
      http.delete('/api/strategy-research/ai-discovery/presets/:presetId', ({ params }) => {
        deletedIds.push(String(params.presetId))
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '프리셋 설정' }))
    await screen.findByRole('button', { name: '삭제' })
    const fetchesBeforeDelete = presetsFetchCount
    await user.click(screen.getByRole('button', { name: '삭제' }))

    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining("'단기 거래대금 폭발' 프리셋을 삭제합니다. 되돌릴 수 없습니다."),
    )
    await waitFor(() => expect(deletedIds).toContain('scalping_momentum'))
    await waitFor(() => expect(presetsFetchCount).toBeGreaterThan(fetchesBeforeDelete))
    confirmSpy.mockRestore()
  })

  it('직접 입력 모드에서 프롬프트가 비면 발굴 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    // 직접 입력 모드로 전환
    await user.click(screen.getByRole('button', { name: '직접 입력' }))
    const promptInput = screen.getByLabelText('프롬프트')
    await user.clear(promptInput)
    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByText('직접 입력 모드에서는 프롬프트를 입력해주세요')).toBeInTheDocument()
    expect(lastDiscoveryBody).toBeNull()
  })

  it('AI 프리셋 추천 → 팝업 → 추천 결과 카드 → 추가 클릭 시 createPreset 호출 + 추가됨 상태', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    const recommendRef: { value: { userId: string; hint: string | null; count: number } | null } = {
      value: null,
    }
    const createdBodies: Array<{ label: string; promptText: string; active: boolean }> = []
    server.use(
      http.post('/api/strategy-research/ai-discovery/presets/recommend', async ({ request }) => {
        recommendRef.value = (await request.json()) as {
          userId: string
          hint: string | null
          count: number
        }
        return HttpResponse.json({
          provider: 'claude-cli-bridge',
          generatedAt: '2026-06-06T00:00:00Z',
          warnings: [],
          items: [
            {
              label: '수급 집중 단타',
              description: '기관·외국인 동반 순매수 후보',
              promptText: '최근 3일 기관·외국인 동반 순매수가 강한 후보를 찾아라.',
              rationale: 'Screener·Backtest 검증 흐름과 맞물려 단타 검증에 적합',
            },
            {
              label: '낙폭 과대 반등',
              description: '단기 급락 후 거래량 회복 후보',
              promptText: '52주 고점 대비 큰 폭으로 하락한 뒤 거래량이 회복되는 후보를 찾아라.',
              rationale: '기존 모멘텀 프리셋과 겹치지 않는 역추세 방향',
            },
          ],
        })
      }),
      http.post('/api/strategy-research/ai-discovery/presets', async ({ request }) => {
        const body = (await request.json()) as { label: string; promptText: string; active: boolean }
        createdBodies.push(body)
        return HttpResponse.json({
          id: 'custom_recommended',
          label: body.label,
          description: '기관·외국인 동반 순매수 후보',
          promptText: body.promptText,
          sortOrder: 30,
          active: body.active,
          builtIn: false,
          updatedAt: '2026-06-06T00:00:00Z',
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: 'AI 프리셋 추천' }))

    const hint = await screen.findByLabelText('원하는 방향 (선택)')
    await user.type(hint, '수급 기반 단타')
    await user.click(screen.getByRole('button', { name: '추천받기' }))

    expect(await screen.findByText('수급 집중 단타')).toBeInTheDocument()
    expect(screen.getByText('낙폭 과대 반등')).toBeInTheDocument()
    await waitFor(() => expect(recommendRef.value).not.toBeNull())
    expect(recommendRef.value?.hint).toBe('수급 기반 단타')

    const addButtons = screen.getAllByRole('button', { name: '프리셋으로 추가' })
    expect(addButtons).toHaveLength(2)
    await user.click(addButtons[0])

    await waitFor(() => expect(createdBodies).toHaveLength(1))
    expect(createdBodies[0].label).toBe('수급 집중 단타')
    expect(createdBodies[0].active).toBe(true)
    expect(await screen.findByRole('button', { name: '추가됨' })).toBeDisabled()
    // 나머지 카드는 여전히 추가 가능
    expect(screen.getByRole('button', { name: '프리셋으로 추가' })).toBeEnabled()
  })

  it('프리셋 모드에서는 빈 프롬프트로도 발굴 요청을 허용한다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    // 프리셋이 자동 선택될 때까지 대기
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '단기 거래대금 폭발' })).toHaveClass('border-indigo-500'),
    )
    const promptInput = screen.getByLabelText('프롬프트')
    await user.clear(promptInput)
    await user.click(screen.getByRole('button', { name: '발굴' }))

    await waitFor(() => expect(lastDiscoveryBody).not.toBeNull())
    expect(lastDiscoveryBody?.presetId).toBe('scalping_momentum')
    expect(lastDiscoveryBody?.query).toBe('')
  })
})
