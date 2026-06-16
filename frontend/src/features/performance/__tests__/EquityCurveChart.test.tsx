/**
 * EquityCurveChart 단위 테스트 — 2 케이스
 *
 * ADR 0072 §9-4 + spec §3-3 정합:
 *   1. mount — 컨테이너 div 렌더 (LWC v4 chart는 jsdom 미지원 — createChart mock)
 *   2. empty data state — "거래 내역이 없어..." 표시
 *
 * lightweight-charts는 DOM canvas 의존 → vi.mock으로 stub
 */
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { EquityCurveChart } from '../components/EquityCurveChart'

// LWC v4 mock — jsdom canvas 미지원
vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addLineSeries: () => ({
      setData: vi.fn(),
    }),
    applyOptions: vi.fn(),
    timeScale: () => ({ fitContent: vi.fn() }),
    remove: vi.fn(),
  }),
}))

describe('EquityCurveChart', () => {
  it('1. mount — 컨테이너 렌더 정상', () => {
    const { container } = render(
      <EquityCurveChart
        points={[
          { tradeDate: '2026-05-13', dailyPnl: 100000, cumulativePnl: 100000, tradeCount: 2 },
          { tradeDate: '2026-05-14', dailyPnl: 50000, cumulativePnl: 150000, tradeCount: 1 },
        ]}
        isLoading={false}
        isError={false}
      />,
    )
    // 차트 wrapper div 존재
    expect(container.querySelector('.w-full')).toBeInTheDocument()
  })

  it('2. empty data state — 안내 메시지 표시', () => {
    render(<EquityCurveChart points={[]} isLoading={false} isError={false} />)
    expect(
      screen.getByText('거래 내역이 없어 Equity Curve를 표시할 수 없습니다.'),
    ).toBeInTheDocument()
  })
})
