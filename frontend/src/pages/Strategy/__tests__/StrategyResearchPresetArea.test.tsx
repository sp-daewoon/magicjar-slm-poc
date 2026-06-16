import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyResearchPage } from '../StrategyResearchPage'
import { useOnboardingStore } from '../../../stores/onboarding'

vi.mock('../../../lib/stomp', () => ({
  useStompTopic: () => {},
  getStompClient: () => ({ connected: false, active: false }),
}))

vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addCandlestickSeries: () => ({ setData: vi.fn(), setMarkers: vi.fn() }),
    remove: vi.fn(),
    subscribeCrosshairMove: vi.fn(),
    timeScale: () => ({ fitContent: vi.fn(), subscribeVisibleLogicalRangeChange: vi.fn() }),
  }),
}))

const RECENT_KEY = 'magicjar.discovery.presetRecentIds'

function preset(id: string, label: string, sortOrder: number, active = true) {
  return {
    id,
    label,
    description: `${label} 설명`,
    promptText: `${label} 프롬프트`,
    sortOrder,
    active,
    builtIn: true,
    updatedAt: '2026-06-06T00:00:00Z',
  }
}

// 4개 활성 프리셋 (sortOrder 오름차순) — 버튼 3 + 콤보 1 구성을 만든다.
const FOUR_PRESETS = [
  preset('p_a', '알파 프리셋', 10),
  preset('p_b', '베타 프리셋', 20),
  preset('p_c', '감마 프리셋', 30),
  preset('p_d', '델타 프리셋', 40),
]

function presetsHandler(items: ReturnType<typeof preset>[]) {
  return http.get('/api/strategy-research/ai-discovery/presets', () => HttpResponse.json(items))
}

const server = setupServer(
  http.get('/api/instruments/search', ({ request }) => {
    const query = new URL(request.url).searchParams.get('q') ?? ''
    return HttpResponse.json({ query, results: [], totalMatched: 0, limit: 10 })
  }),
  presetsHandler(FOUR_PRESETS),
  http.get('/api/strategy-research/ai-discovery/latest', () => HttpResponse.json({ report: null })),
  http.get('/api/strategy-research/ai-discovery/core-prompt', () =>
    HttpResponse.json({ promptText: '기본 Core Wrapper 지시문', isDefault: true, updatedAt: null }),
  ),
  http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
    const body = (await request.json()) as { query: string; presetId?: string | null; limit: number; market?: string | null }
    return HttpResponse.json({
      query: body.query,
      presetId: body.presetId ?? null,
      market: body.market ?? null,
      limit: body.limit,
      provider: 'claude-cli-bridge',
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: [],
      items: [],
    })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
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

function presetRow(): HTMLElement {
  // "프리셋" 라벨이 들어있는 선택 행 컨테이너
  return screen.getByText('프리셋', { selector: 'span' }).parentElement as HTMLElement
}

describe('StrategyResearchPage 프리셋 선택 영역', () => {
  it('활성 프리셋이 4개 이상이면 버튼 3개 + 콤보박스를 노출한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    // 이력 없으면 sortOrder 상위 3개가 버튼
    expect(await screen.findByRole('button', { name: '알파 프리셋' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '베타 프리셋' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '감마 프리셋' })).toBeInTheDocument()
    // 4번째는 버튼이 아니라 콤보 옵션
    expect(screen.queryByRole('button', { name: '델타 프리셋' })).not.toBeInTheDocument()

    const combo = screen.getByRole('combobox', { name: '다른 프리셋' })
    expect(within(combo).getByRole('option', { name: '델타 프리셋' })).toBeInTheDocument()
  })

  it('콤보 선택 시 해당 프리셋이 활성화되고 localStorage 최근 이력에 등록돼 다음 렌더에서 버튼으로 승격된다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    const { unmount } = renderPage()

    const combo = await screen.findByRole('combobox', { name: '다른 프리셋' })
    await user.selectOptions(combo, 'p_d')

    // 선택 즉시 활성 — 콤보가 active 스타일(indigo)로 식별되거나 버튼으로 승격
    await waitFor(() => {
      const stored = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]')
      expect(stored[0]).toBe('p_d')
    })
    expect(screen.getByText('델타 프리셋 설명')).toBeInTheDocument()

    // 재마운트(목록 refetch와 동일 효과) 시 델타가 버튼 3개에 들어가야 한다
    unmount()
    renderPage()
    expect(await screen.findByRole('button', { name: '델타 프리셋' })).toBeInTheDocument()
  })

  it('"직접 입력" 버튼이 프리셋 선택 행의 마지막(최우측) 요소다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await screen.findByRole('button', { name: '알파 프리셋' })
    const directInput = screen.getByRole('button', { name: '직접 입력' })
    const row = presetRow()
    const buttons = within(row).getAllByRole('button')
    expect(buttons[buttons.length - 1]).toBe(directInput)
    // 최우측 정렬용 ml-auto 적용
    expect(directInput.className).toContain('ml-auto')
  })

  it('활성 프리셋이 3개 이하면 콤보박스를 숨기고 버튼만 노출한다', async () => {
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(presetsHandler(FOUR_PRESETS.slice(0, 3)))

    renderPage()

    expect(await screen.findByRole('button', { name: '알파 프리셋' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '베타 프리셋' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '감마 프리셋' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '다른 프리셋' })).not.toBeInTheDocument()
  })

  it('버튼 3개는 라벨 글자순으로 고정 — 셋 안에서 클릭해도 순서가 바뀌지 않는다 (2026-06-08 사용자 지시)', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    // 최근 사용 이력: 감마 → 알파 → 베타 (recency 순서가 뒤죽박죽이어도)
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(['p_c', 'p_a', 'p_b']))

    renderPage()

    const labelsOf = () => {
      const row = presetRow()
      return within(row)
        .getAllByRole('button')
        .map((b) => b.textContent)
        .filter((t) => t !== '직접 입력')
    }

    await screen.findByRole('button', { name: '알파 프리셋' })
    // 표시는 recency가 아니라 라벨 글자순(ko) 고정
    const initial = labelsOf()
    expect(initial).toEqual([...initial].sort((a, b) => (a ?? '').localeCompare(b ?? '', 'ko')))

    // 셋 안의 프리셋을 클릭(최근 이력 bump)해도 버튼 순서 불변
    await user.click(screen.getByRole('button', { name: '베타 프리셋' }))
    expect(labelsOf()).toEqual(initial)
    await user.click(screen.getByRole('button', { name: '감마 프리셋' }))
    expect(labelsOf()).toEqual(initial)
  })
})
