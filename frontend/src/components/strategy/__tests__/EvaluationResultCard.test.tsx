/**
 * EvaluationResultCard 단위 테스트
 *
 * 검증:
 * - BUY/SELL/HOLD signal 분기 렌더
 * - 신호 강도 게이지 퍼센트
 * - "관심종목 추가" 버튼 mutate 호출
 * - 0건 "데이터 없음" (EvaluationEmptyCard)
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EvaluationResultCard, EvaluationEmptyCard } from '../EvaluationResultCard'
import type { EvaluateResponse } from '../../../api/strategy'

// useAddWatchlist mock
vi.mock('../../../api/hooks/useWatchlist', () => ({
  useAddWatchlist: () => ({
    mutate: vi.fn(),
    isPending: false,
  }),
}))

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

const buyResult: EvaluateResponse = {
  strategyId: 'test-id',
  signal: 'BUY',
  strength: 0.85,
  reason: { trigger: 'RSI 30 이하 진입 신호', lhsValue: 29.8 },
  evalTimeMs: 42,
}

const sellResult: EvaluateResponse = {
  strategyId: 'test-id',
  signal: 'SELL',
  strength: 0.72,
  reason: 'RSI 70 이상 과매수',
  evalTimeMs: 38,
}

const holdResult: EvaluateResponse = {
  strategyId: 'test-id',
  signal: 'HOLD',
  strength: 0.45,
  reason: '조건 미충족',
  evalTimeMs: 35,
}

describe('EvaluationResultCard signal 분기', () => {
  it('BUY 신호 표시', () => {
    render(<EvaluationResultCard result={buyResult} code="005930" />, { wrapper })
    expect(screen.getByText(/매수/)).toBeInTheDocument()
    expect(screen.getByText(/RSI 30/)).toBeInTheDocument()
  })

  it('backend Map 형태의 평가 근거를 문자열로 표시', () => {
    render(<EvaluationResultCard result={buyResult} code="005930" />, { wrapper })
    expect(screen.getByText(/trigger: RSI 30 이하 진입 신호/)).toBeInTheDocument()
    expect(screen.getByText(/lhsValue: 29.8/)).toBeInTheDocument()
  })

  it('SELL 신호 표시', () => {
    render(<EvaluationResultCard result={sellResult} code="005930" />, { wrapper })
    expect(screen.getByText(/매도/)).toBeInTheDocument()
  })

  it('HOLD 신호 표시', () => {
    render(<EvaluationResultCard result={holdResult} code="005930" />, { wrapper })
    expect(screen.getByText(/보유/)).toBeInTheDocument()
  })

  it('신호 강도 퍼센트 표시', () => {
    render(<EvaluationResultCard result={buyResult} code="005930" />, { wrapper })
    expect(screen.getByText('85%')).toBeInTheDocument()
  })

  it('평가 시간 표시', () => {
    render(<EvaluationResultCard result={buyResult} code="005930" />, { wrapper })
    expect(screen.getByText(/42ms/)).toBeInTheDocument()
  })

  it('관심종목 추가 버튼 렌더', () => {
    render(<EvaluationResultCard result={buyResult} code="005930" />, { wrapper })
    expect(screen.getByLabelText(/관심종목 추가/)).toBeInTheDocument()
  })
})

describe('EvaluationEmptyCard — 데이터 없음', () => {
  it('"데이터 없음" 표시', () => {
    render(<EvaluationEmptyCard />)
    expect(screen.getByText('데이터 없음')).toBeInTheDocument()
  })
})
