/**
 * KisWsHealthIndicator unit test — 사용자 dogfooding 2026-05-20
 *
 * 3 시나리오 (사용자 임무 명세):
 *   1. CONNECTED    → emerald dot + "KIS WS" 텍스트 (spinner 없음)
 *   2. DISCONNECTED → red dot + "KIS WS 끊김" + spinner
 *   3. UNKNOWN      → yellow dot + "KIS WS ?" (spinner 없음)
 *
 * useKisWsHealthState hook을 mock — pure render test
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const mockState = vi.hoisted(() => ({
  value: 'CONNECTED' as 'CONNECTED' | 'DISCONNECTED' | 'UNKNOWN',
}))

vi.mock('../../../api/hooks/useKisWsHealthState', () => ({
  useKisWsHealthState: () => mockState.value,
}))

import { KisWsHealthIndicator } from '../KisWsHealthIndicator'

describe('KisWsHealthIndicator', () => {
  it('CONNECTED 상태 — emerald dot + "KIS WS" 텍스트 + spinner 없음', () => {
    mockState.value = 'CONNECTED'
    render(<KisWsHealthIndicator />)

    expect(screen.getByText('KIS WS')).toBeInTheDocument()
    const dot = screen.getByTestId('kis-ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('green')

    expect(screen.queryByTestId('kis-ws-indicator-spinner')).not.toBeInTheDocument()

    const container = screen.getByTestId('kis-ws-health-indicator')
    expect(container.getAttribute('aria-label')).toBe('KIS WebSocket 상태: 연결됨')
    expect(container.getAttribute('data-state')).toBe('CONNECTED')
  })

  it('DISCONNECTED 상태 — red dot + "KIS WS 끊김" + spinner 표시', () => {
    mockState.value = 'DISCONNECTED'
    render(<KisWsHealthIndicator />)

    expect(screen.getByText('KIS WS 끊김')).toBeInTheDocument()
    const dot = screen.getByTestId('kis-ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('red')

    expect(screen.getByTestId('kis-ws-indicator-spinner')).toBeInTheDocument()

    const container = screen.getByTestId('kis-ws-health-indicator')
    expect(container.getAttribute('aria-label')).toBe('KIS WebSocket 상태: 끊김')
    expect(container.getAttribute('data-state')).toBe('DISCONNECTED')
  })

  it('UNKNOWN 상태 — yellow dot + "KIS WS ?" 텍스트 + spinner 없음', () => {
    mockState.value = 'UNKNOWN'
    render(<KisWsHealthIndicator />)

    expect(screen.getByText('KIS WS ?')).toBeInTheDocument()
    const dot = screen.getByTestId('kis-ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('yellow')

    expect(screen.queryByTestId('kis-ws-indicator-spinner')).not.toBeInTheDocument()

    const container = screen.getByTestId('kis-ws-health-indicator')
    expect(container.getAttribute('aria-label')).toBe('KIS WebSocket 상태: 알 수 없음')
    expect(container.getAttribute('data-state')).toBe('UNKNOWN')
  })
})
