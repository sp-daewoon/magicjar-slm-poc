/**
 * StrategyAiGenerateModal 테스트
 *
 * 검증:
 * - prompt 빈 값 클라이언트 검증 (mutation 미호출)
 * - 생성 → valid/invalid 카드 렌더 분기
 * - valid 카드 "빌더로 불러오기" → onLoadDraft 호출 (자동 저장 0)
 * - invalid 카드 "빌더로 불러오기" 비활성
 * - warnings 표시
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, beforeAll, afterAll, afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyAiGenerateModal } from '../StrategyAiGenerateModal'

const validItem = {
  name: '거래량 돌파 단타',
  description: '20일선 돌파 + 거래량 동반',
  entryDsl: 'close > ma(20)',
  exitDsl: 'close < ma(5)',
  riskPlan: { takeProfitPct: 1.5, fixedStopLossPct: 0.8, minHoldBars: 3 },
  rationale: '추세 + 수급 확인',
  dslValid: true,
  dslError: null,
}

const invalidItem = {
  name: '잘못된 후보',
  description: '파서 미통과',
  entryDsl: 'prev_drop > 0',
  exitDsl: null,
  riskPlan: { takeProfitPct: null, fixedStopLossPct: null, minHoldBars: null },
  rationale: '',
  dslValid: false,
  dslError: 'Unknown DSL identifier: prev_drop',
}

const server = setupServer(
  http.post('/api/strategies/ai-generate', () =>
    HttpResponse.json({
      provider: 'claude-cli',
      generatedAt: '2026-06-08T12:00:00Z',
      warnings: ['익절만 있고 손절이 없는 후보가 포함될 수 있습니다'],
      items: [validItem, invalidItem],
    }),
  ),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function renderModal(overrides: Partial<Parameters<typeof StrategyAiGenerateModal>[0]> = {}) {
  const onLoadDraft = vi.fn()
  const onClose = vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <StrategyAiGenerateModal
        userId={74683223}
        defaultInterval="1d"
        onLoadDraft={onLoadDraft}
        onClose={onClose}
        {...overrides}
      />
    </QueryClientProvider>,
  )
  return { onLoadDraft, onClose }
}

describe('StrategyAiGenerateModal', () => {
  it('prompt 빈 값이면 검증 메시지 표시 + 생성 미진행', () => {
    renderModal()
    fireEvent.click(screen.getByRole('button', { name: '생성하기' }))
    expect(screen.getByText('전략 의도를 입력하세요')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-strategy-card')).not.toBeInTheDocument()
  })

  it('생성 → valid/invalid 카드 + warnings 렌더', async () => {
    renderModal()
    fireEvent.change(screen.getByLabelText('전략 의도 (필수)'), {
      target: { value: '거래량 터지며 20일선 돌파 단타' },
    })
    fireEvent.click(screen.getByRole('button', { name: '생성하기' }))

    await waitFor(() => {
      expect(screen.getAllByTestId('ai-strategy-card')).toHaveLength(2)
    })
    expect(screen.getByText('거래량 돌파 단타')).toBeInTheDocument()
    expect(screen.getByText('close > ma(20)')).toBeInTheDocument()
    expect(screen.getByText(/익절 1.5%/)).toBeInTheDocument()
    // warnings
    expect(screen.getByText(/익절만 있고 손절이 없는/)).toBeInTheDocument()
    // invalid 카드 dslError
    expect(screen.getByText(/Unknown DSL identifier: prev_drop/)).toBeInTheDocument()
  })

  it('valid 카드 "빌더로 불러오기" → onLoadDraft 호출, invalid 카드 버튼은 비활성', async () => {
    const { onLoadDraft } = renderModal()
    fireEvent.change(screen.getByLabelText('전략 의도 (필수)'), {
      target: { value: '과매도 반등 스윙' },
    })
    fireEvent.click(screen.getByRole('button', { name: '생성하기' }))

    await waitFor(() => expect(screen.getAllByTestId('ai-strategy-card')).toHaveLength(2))

    const loadButtons = screen.getAllByRole('button', { name: '빌더로 불러오기' })
    // 첫 카드(valid) 활성, 둘째(invalid) 비활성
    expect(loadButtons[0]).not.toBeDisabled()
    expect(loadButtons[1]).toBeDisabled()

    fireEvent.click(loadButtons[0])
    expect(onLoadDraft).toHaveBeenCalledTimes(1)
    expect(onLoadDraft).toHaveBeenCalledWith(expect.objectContaining({ name: '거래량 돌파 단타', dslValid: true }))
  })

  it('userId 미등록이면 안내 후 생성 미진행', () => {
    renderModal({ userId: null })
    fireEvent.change(screen.getByLabelText('전략 의도 (필수)'), {
      target: { value: '아무 전략' },
    })
    fireEvent.click(screen.getByRole('button', { name: '생성하기' }))
    expect(screen.getByText('사용자 등록 후 이용할 수 있습니다')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-strategy-card')).not.toBeInTheDocument()
  })
})
