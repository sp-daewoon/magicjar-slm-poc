/**
 * WsConnectionIndicator unit test — 사용자 결재 2026-05-20 (WS 접속 indicator 헤더 영구 표시)
 *
 * 3 시나리오 (사용자 임무 명세):
 *   1. CONNECTED   → emerald dot + "WS 연결됨"
 *   2. DISCONNECTED → red dot + "끊김" + spinner
 *   3. CONNECTING  → yellow dot + "연결 중..."
 *
 * useStompConnectionState hook을 mock — pure render test
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const mockState = vi.hoisted(() => ({
  value: 'CONNECTED' as 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING',
}))

vi.mock('../../../api/hooks/useStompConnectionState', () => ({
  useStompConnectionState: () => mockState.value,
}))

// import 후 mock 적용 (vi.mock은 hoist되므로 안전)
import { WsConnectionIndicator } from '../WsConnectionIndicator'

describe('WsConnectionIndicator', () => {
  it('CONNECTED 상태 — emerald dot + "WS 연결됨" 텍스트 + spinner 없음', () => {
    mockState.value = 'CONNECTED'
    render(<WsConnectionIndicator />)

    expect(screen.getByText('WS 연결됨')).toBeInTheDocument()
    const dot = screen.getByTestId('ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('green')

    expect(screen.queryByTestId('ws-indicator-spinner')).not.toBeInTheDocument()

    const container = screen.getByTestId('ws-connection-indicator')
    expect(container.getAttribute('aria-label')).toBe('WS 접속 상태: 연결됨')
    expect(container.getAttribute('data-state')).toBe('CONNECTED')
  })

  it('DISCONNECTED 상태 — red dot + "끊김" 텍스트 + spinner 표시', () => {
    mockState.value = 'DISCONNECTED'
    render(<WsConnectionIndicator />)

    expect(screen.getByText('끊김')).toBeInTheDocument()
    const dot = screen.getByTestId('ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('red')

    expect(screen.getByTestId('ws-indicator-spinner')).toBeInTheDocument()

    const container = screen.getByTestId('ws-connection-indicator')
    expect(container.getAttribute('aria-label')).toBe('WS 접속 상태: 끊김')
    expect(container.getAttribute('data-state')).toBe('DISCONNECTED')
  })

  it('CONNECTING 상태 — yellow dot + "연결 중..." 텍스트 + spinner 표시', () => {
    mockState.value = 'CONNECTING'
    render(<WsConnectionIndicator />)

    expect(screen.getByText('연결 중...')).toBeInTheDocument()
    const dot = screen.getByTestId('ws-indicator-dot')
    expect(dot.getAttribute('data-color')).toBe('yellow')

    expect(screen.getByTestId('ws-indicator-spinner')).toBeInTheDocument()

    const container = screen.getByTestId('ws-connection-indicator')
    expect(container.getAttribute('aria-label')).toBe('WS 접속 상태: 연결 중')
    expect(container.getAttribute('data-state')).toBe('CONNECTING')
  })
})
