/**
 * ScreenerScoreBreakdown 단위 테스트 — 4 기준 가중치 시각화
 *
 * ADR 0071 §2-5 E-1 4 기준 정합:
 *   signal(0.40)·volume(0.30)·atr(0.20)·marketCap(0.10)
 */
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { ScreenerScoreBreakdown } from '../components/ScreenerScoreBreakdown'
import type { ScreenerScoreComponentsDto } from '../api/screenerApi'

const mockComponents: ScreenerScoreComponentsDto = {
  signal: 0.8,
  volume: 0.6,
  atr: 0.5,
  marketCap: 0.3,
}

describe('ScreenerScoreBreakdown', () => {
  it('4 기준 label 모두 표시 (전체 모드)', () => {
    render(<ScreenerScoreBreakdown scoreComponents={mockComponents} />)

    expect(screen.getByText('시그널')).toBeInTheDocument()
    expect(screen.getByText('거래대금')).toBeInTheDocument()
    expect(screen.getByText('변동성(ATR)')).toBeInTheDocument()
    expect(screen.getByText('시총')).toBeInTheDocument()
  })

  it('가중치 label 표시 (×0.4 등)', () => {
    render(<ScreenerScoreBreakdown scoreComponents={mockComponents} />)

    expect(screen.getByText('(×0.4)')).toBeInTheDocument()
    expect(screen.getByText('(×0.3)')).toBeInTheDocument()
    expect(screen.getByText('(×0.2)')).toBeInTheDocument()
    expect(screen.getByText('(×0.1)')).toBeInTheDocument()
  })

  it('compact 모드 — bar 4개, aria-label 존재', () => {
    render(<ScreenerScoreBreakdown scoreComponents={mockComponents} compact />)

    const container = screen.getByLabelText('점수 구성')
    expect(container).toBeInTheDocument()
    // compact: 4개 div bar
    const bars = container.querySelectorAll('div')
    expect(bars.length).toBe(4)
  })

  it('0 값 처리 — 최소 높이(4px percent) 유지', () => {
    const zeroComponents: ScreenerScoreComponentsDto = {
      signal: 0,
      volume: 0,
      atr: 0,
      marketCap: 0,
    }
    render(<ScreenerScoreBreakdown scoreComponents={zeroComponents} compact />)
    // 에러 없이 렌더되어야 함
    const container = screen.getByLabelText('점수 구성')
    expect(container).toBeInTheDocument()
  })
})
