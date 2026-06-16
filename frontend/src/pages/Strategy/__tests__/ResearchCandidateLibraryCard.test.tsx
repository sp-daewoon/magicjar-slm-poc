import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import ResearchCandidateLibraryCard from '../ResearchCandidateLibraryCard'

const builtinCandidate = {
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
}

let candidateStore = [builtinCandidate]
let lastAddBody: unknown = null

const server = setupServer(
  http.get('/api/strategy-research/candidates', () => HttpResponse.json(candidateStore)),
  http.get('/api/strategy', () =>
    HttpResponse.json([
      { id: '31', userId: 74683223, name: '내 눌림목 전략', nameKo: '내 눌림목 전략', entry: 'close > ma(20)', enabled: true, createdAt: '2026-06-01T00:00:00Z' },
    ]),
  ),
  http.post('/api/strategy-research/candidates/from-strategy', async ({ request }) => {
    lastAddBody = await request.json()
    const created = {
      ...builtinCandidate,
      id: 2,
      key: 'USER_31',
      title: '내 눌림목 전략',
      builtIn: false,
      updatedAt: '2026-06-08T00:00:00Z',
    }
    candidateStore = [builtinCandidate, created]
    return HttpResponse.json(created)
  }),
  http.delete('/api/strategy-research/candidates/:id', () => {
    candidateStore = [builtinCandidate]
    return new HttpResponse(null, { status: 204 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  candidateStore = [builtinCandidate]
  lastAddBody = null
})
afterAll(() => server.close())

function renderCard(registeredUserId: number | null = 74683223) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ResearchCandidateLibraryCard registeredUserId={registeredUserId} />
    </QueryClientProvider>,
  )
}

// 진입 버튼 클릭 → 모달 오픈 헬퍼.
async function openModal(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/기본 \d+개/)
  await user.click(screen.getByTestId('research-candidate-library-open'))
  // 모달 헤더 노출 대기
  await screen.findByRole('dialog', { name: '후보 전략 라이브러리 관리' })
}

describe('ResearchCandidateLibraryCard', () => {
  it('인라인은 요약+진입 버튼만 노출하고 후보 목록은 감춘다', async () => {
    renderCard()
    // 요약 라인(내 전략/기본 카운트)이 보인다.
    expect(await screen.findByText(/기본 1개/)).toBeInTheDocument()
    // 진입 버튼은 보이되, 후보 카드(entry DSL)는 모달 열기 전 노출되지 않는다.
    expect(screen.getByTestId('research-candidate-library-open')).toBeInTheDocument()
    expect(screen.queryByText('close crosses_above ma(10) and close >= open')).not.toBeInTheDocument()
  })

  it('진입 버튼 클릭 시 모달이 열리고 ESC로 닫힌다', async () => {
    const user = userEvent.setup()
    renderCard()
    await openModal(user)

    // 모달 안에서 후보 카드(entry DSL)와 기본 뱃지가 보인다.
    expect(await screen.findByText('close crosses_above ma(10) and close >= open')).toBeInTheDocument()
    expect(screen.getByText('기본')).toBeInTheDocument()

    // ESC 닫기 (useModalBehavior)
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '후보 전략 라이브러리 관리' })).not.toBeInTheDocument(),
    )
  })

  it('모달 내에서 내 전략을 비-builtin 후보로 추가하고 내 후보 섹션 최상단에 정렬한다', async () => {
    const user = userEvent.setup()
    renderCard()
    await openModal(user)
    await screen.findByText('MA10 회복 반등 스캘프')

    await user.selectOptions(screen.getByLabelText('내 전략을 후보로 추가'), '31')
    await user.click(screen.getByRole('button', { name: '후보로 추가' }))

    await waitFor(() => expect(lastAddBody).toEqual({ userId: '74683223', strategyId: 31 }))
    expect(await screen.findByText('내 전략')).toBeInTheDocument()

    // 정렬 검증: 첫 후보 row가 내 후보(builtIn=false)여야 한다.
    const rows = screen.getAllByTestId('research-candidate-row')
    expect(rows[0]).toHaveTextContent('내 눌림목 전략')
    expect(rows[0]).toHaveTextContent('내 전략')
  })

  it('모달 내 삭제 동작이 유지된다', async () => {
    const user = userEvent.setup()
    candidateStore = [
      builtinCandidate,
      {
        ...builtinCandidate,
        id: 2,
        key: 'USER_31',
        title: '내 눌림목 전략',
        builtIn: false,
        updatedAt: '2026-06-08T00:00:00Z',
      },
    ]
    renderCard()
    await openModal(user)

    const deleteBtn = await screen.findByRole('button', { name: '후보 삭제: 내 눌림목 전략' })
    await user.click(deleteBtn)

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '후보 삭제: 내 눌림목 전략' })).not.toBeInTheDocument(),
    )
  })

  it('사용자 미등록 시 모달에서 추가 흐름이 비활성화된다', async () => {
    const user = userEvent.setup()
    renderCard(null)
    await openModal(user)

    await screen.findByText('MA10 회복 반등 스캘프')
    expect(screen.getByRole('button', { name: '후보로 추가' })).toBeDisabled()
    expect(screen.getByText('사용자 등록 후 내 전략을 후보로 추가할 수 있습니다.')).toBeInTheDocument()
  })
})
