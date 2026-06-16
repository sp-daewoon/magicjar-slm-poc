/**
 * CrossSignalBadge 단위 테스트 — T-MARKET R2 frontend.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CrossSignalBadge } from '../components/CrossSignalBadge'

describe('CrossSignalBadge', () => {
  it('GOLDEN — "GOLDEN CROSS" + emerald', () => {
    render(<CrossSignalBadge signal="GOLDEN" />)
    const el = screen.getByText(/GOLDEN CROSS/)
    expect(el.className).toContain('emerald')
  })

  it('DEAD — "DEAD CROSS" + red', () => {
    render(<CrossSignalBadge signal="DEAD" />)
    const el = screen.getByText(/DEAD CROSS/)
    expect(el.className).toContain('red')
  })

  it('NEUTRAL — "교차 없음" + gray (pulse 안 함)', () => {
    render(<CrossSignalBadge signal="NEUTRAL" pulse />)
    const el = screen.getByText(/교차 없음/)
    expect(el.className).toContain('gray')
    expect(el.className).not.toContain('animate-pulse')
  })

  it('GOLDEN + pulse=true — animate-pulse 클래스', () => {
    render(<CrossSignalBadge signal="GOLDEN" pulse />)
    const el = screen.getByText(/GOLDEN CROSS/)
    expect(el.className).toContain('animate-pulse')
  })

  it('GOLDEN + pulse=false — animate-pulse 없음', () => {
    render(<CrossSignalBadge signal="GOLDEN" pulse={false} />)
    const el = screen.getByText(/GOLDEN CROSS/)
    expect(el.className).not.toContain('animate-pulse')
  })
})
