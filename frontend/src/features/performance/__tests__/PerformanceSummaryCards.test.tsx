/**
 * PerformanceSummaryCards 단위 테스트 — 3 케이스
 *
 * ADR 0072 §9-4 + spec §3-3 정합:
 *   1. 5 지표 표시 (P&L·MDD·승률·평균 손익비·거래 횟수)
 *   2. empty state — "성과 데이터가 없습니다" 표시
 *   3. negative MDD edge case — MDD 양수 red 색상 클래스
 */
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { PerformanceSummaryCards } from '../components/PerformanceSummaryCards'
import type { PerformanceSummaryDto } from '../api/performanceApi'

const mockSummary: PerformanceSummaryDto = {
  period: 'MONTH',
  periodStart: '2026-05-01',
  periodEnd: '2026-05-14',
  totalPnl: 1250000,
  mddPct: 8.4321,
  winRatePct: 62.5,
  profitLossRatio: 1.8462,
  tradeCount: 32,
  computedAt: '2026-05-14T18:50:00+09:00',
  cacheHit: false,
}

describe('PerformanceSummaryCards', () => {
  it('1. 5 지표 카드 label + value 표시', () => {
    render(<PerformanceSummaryCards summary={mockSummary} isLoading={false} isError={false} />)

    // labels
    expect(screen.getByText('총 손익')).toBeInTheDocument()
    expect(screen.getByText('최대 낙폭 (MDD)')).toBeInTheDocument()
    expect(screen.getByText('승률')).toBeInTheDocument()
    expect(screen.getByText('평균 손익비')).toBeInTheDocument()
    expect(screen.getByText('거래 횟수')).toBeInTheDocument()

    // values
    expect(screen.getByText('+1,250,000원')).toBeInTheDocument()
    expect(screen.getByText('8.43%')).toBeInTheDocument()
    expect(screen.getByText('62.5%')).toBeInTheDocument()
    expect(screen.getByText('1.85')).toBeInTheDocument()
    expect(screen.getByText('32회')).toBeInTheDocument()
  })

  it('2. summary undefined → empty state 표시', () => {
    render(<PerformanceSummaryCards summary={undefined} isLoading={false} isError={false} />)
    expect(screen.getByText('성과 데이터가 없습니다.')).toBeInTheDocument()
  })

  it('3. negative MDD(0%) edge case — 0.00% 표시', () => {
    const zeroMdd: PerformanceSummaryDto = { ...mockSummary, mddPct: 0, totalPnl: 0 }
    render(<PerformanceSummaryCards summary={zeroMdd} isLoading={false} isError={false} />)
    expect(screen.getByText('0.00%')).toBeInTheDocument()
  })
})
