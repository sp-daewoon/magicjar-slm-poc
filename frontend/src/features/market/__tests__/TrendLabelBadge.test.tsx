/**
 * TrendLabelBadge 단위 테스트 — T-MARKET R2 frontend.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TrendLabelBadge } from '../components/TrendLabelBadge'

describe('TrendLabelBadge', () => {
  it('UP 라벨 — "상승" 표시 + 색상 클래스', () => {
    render(<TrendLabelBadge label="UP" />)
    const el = screen.getByLabelText(/추세 상승/)
    expect(el).toHaveTextContent('상승')
    expect(el.className).toContain('emerald')
  })

  it('SIDEWAYS 라벨 — "횡보" + amber', () => {
    render(<TrendLabelBadge label="SIDEWAYS" />)
    const el = screen.getByLabelText(/추세 횡보/)
    expect(el).toHaveTextContent('횡보')
    expect(el.className).toContain('amber')
  })

  it('DOWN 라벨 — "하락" + red', () => {
    render(<TrendLabelBadge label="DOWN" />)
    const el = screen.getByLabelText(/추세 하락/)
    expect(el).toHaveTextContent('하락')
    expect(el.className).toContain('red')
  })

  it('compact=true — 더 작은 padding/text', () => {
    render(<TrendLabelBadge label="UP" compact />)
    const el = screen.getByLabelText(/추세 상승/)
    expect(el.className).toContain('px-1.5')
  })
})
