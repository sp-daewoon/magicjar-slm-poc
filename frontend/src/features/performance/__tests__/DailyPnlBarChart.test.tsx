/**
 * DailyPnlBarChart 단위 테스트 — 2 케이스
 *
 * ADR 0072 §9-4 정합:
 *   1. mount — 컨테이너 렌더 (LWC v4 histogram mock)
 *   2. empty data state — "일별 P&L 데이터가 없습니다" 표시
 */
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { DailyPnlBarChart } from '../components/DailyPnlBarChart'

vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addHistogramSeries: () => ({
      setData: vi.fn(),
    }),
    applyOptions: vi.fn(),
    timeScale: () => ({ fitContent: vi.fn() }),
    remove: vi.fn(),
  }),
}))

describe('DailyPnlBarChart', () => {
  it('1. mount — 컨테이너 렌더 정상', () => {
    const { container } = render(
      <DailyPnlBarChart
        points={[
          { tradeDate: '2026-05-13', dailyPnl: 100000, cumulativePnl: 100000, tradeCount: 2 },
          { tradeDate: '2026-05-14', dailyPnl: -30000, cumulativePnl: 70000, tradeCount: 1 },
        ]}
        isLoading={false}
        isError={false}
      />,
    )
    expect(container.querySelector('.w-full')).toBeInTheDocument()
  })

  it('2. empty data state — 안내 메시지 표시', () => {
    render(<DailyPnlBarChart points={[]} isLoading={false} isError={false} />)
    expect(screen.getByText('일별 P&L 데이터가 없습니다.')).toBeInTheDocument()
  })
})
