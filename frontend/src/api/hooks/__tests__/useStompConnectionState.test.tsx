/**
 * useStompConnectionState 단위 테스트 — 사용자 결재 2026-05-20
 *
 * 2 시나리오 (사용자 임무 명세):
 *   1. onConnect callback 시 CONNECTED 전환
 *   2. onDisconnect callback 시 DISCONNECTED 전환 + warning toast 호출
 *
 * 추가 검증:
 *   - CONNECTED → DISCONNECTED 전환 시 warning toast
 *   - DISCONNECTED → CONNECTED 복귀 시 success toast (재연결 안내)
 *   - 첫 mount CONNECTING 상태일 때 toast 0건 (사용자 인지 방해 회피)
 */
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  showToast: vi.fn(),
  client: {
    connected: false as boolean,
    onConnect: undefined as ((frame: unknown) => void) | undefined,
    onDisconnect: undefined as ((frame: unknown) => void) | undefined,
    onWebSocketClose: undefined as ((event: unknown) => void) | undefined,
    onStompError: undefined as ((frame: unknown) => void) | undefined,
  },
}))

vi.mock('../../../lib/stomp', () => ({
  getStompClient: () => mocks.client,
}))

vi.mock('../../../components/ui/ToastProvider', () => ({
  useOptionalToast: () => ({ showToast: mocks.showToast }),
}))

import { useStompConnectionState } from '../useStompConnectionState'

beforeEach(() => {
  mocks.showToast.mockReset()
  mocks.client.connected = false
  mocks.client.onConnect = undefined
  mocks.client.onDisconnect = undefined
  mocks.client.onWebSocketClose = undefined
  mocks.client.onStompError = undefined
})

describe('useStompConnectionState', () => {
  it('client.connected=true 초기 mount 시 CONNECTED 상태', () => {
    mocks.client.connected = true
    const { result } = renderHook(() => useStompConnectionState())

    expect(result.current).toBe('CONNECTED')
    // 첫 mount toast 0건
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('onConnect callback 트리거 시 CONNECTED 상태로 전환', () => {
    mocks.client.connected = false
    const { result } = renderHook(() => useStompConnectionState())

    // 초기 CONNECTING
    expect(result.current).toBe('CONNECTING')
    expect(typeof mocks.client.onConnect).toBe('function')

    // STOMP CONNECT frame 수신 simulate
    act(() => {
      mocks.client.onConnect?.({ headers: {}, command: 'CONNECTED' })
    })

    expect(result.current).toBe('CONNECTED')
    // 첫 mount CONNECTING → CONNECTED는 toast 0 (사용자 인지 방해 회피)
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('onDisconnect callback 시 DISCONNECTED 전환 + warning toast 호출', () => {
    // CONNECTED 상태에서 시작 (DISCONNECTED 전환 시 toast 발동 조건)
    mocks.client.connected = true
    const { result } = renderHook(() => useStompConnectionState())

    expect(result.current).toBe('CONNECTED')
    expect(typeof mocks.client.onDisconnect).toBe('function')

    act(() => {
      mocks.client.onDisconnect?.({ headers: {}, command: 'DISCONNECT' })
    })

    expect(result.current).toBe('DISCONNECTED')
    expect(mocks.showToast).toHaveBeenCalledWith(
      'WS 연결이 끊어졌습니다 — 재연결 시도 중...',
      'warning',
    )
  })

  it('onWebSocketClose callback 시에도 DISCONNECTED + warning toast', () => {
    mocks.client.connected = true
    const { result } = renderHook(() => useStompConnectionState())

    expect(result.current).toBe('CONNECTED')

    act(() => {
      mocks.client.onWebSocketClose?.({ code: 1006, reason: 'abnormal' })
    })

    expect(result.current).toBe('DISCONNECTED')
    expect(mocks.showToast).toHaveBeenCalledWith(
      'WS 연결이 끊어졌습니다 — 재연결 시도 중...',
      'warning',
    )
  })

  it('DISCONNECTED → CONNECTED 복귀 시 success toast (재연결 안내)', () => {
    mocks.client.connected = true
    const { result } = renderHook(() => useStompConnectionState())

    // CONNECTED → DISCONNECTED
    act(() => {
      mocks.client.onDisconnect?.({ headers: {}, command: 'DISCONNECT' })
    })
    expect(result.current).toBe('DISCONNECTED')

    mocks.showToast.mockClear()

    // DISCONNECTED → CONNECTED (재연결)
    act(() => {
      mocks.client.onConnect?.({ headers: {}, command: 'CONNECTED' })
    })

    expect(result.current).toBe('CONNECTED')
    expect(mocks.showToast).toHaveBeenCalledWith('WS 재연결 완료', 'success')
  })

  it('enableToast=false 옵션 시 toast 호출 0건', () => {
    mocks.client.connected = true
    const { result } = renderHook(() => useStompConnectionState({ enableToast: false }))

    act(() => {
      mocks.client.onDisconnect?.({ headers: {}, command: 'DISCONNECT' })
    })

    expect(result.current).toBe('DISCONNECTED')
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('unmount 시 콜백 chain 복원 — 이전 onConnect/onDisconnect 보존', () => {
    const previousOnConnect = vi.fn()
    const previousOnDisconnect = vi.fn()
    mocks.client.onConnect = previousOnConnect
    mocks.client.onDisconnect = previousOnDisconnect

    const { unmount } = renderHook(() => useStompConnectionState())

    // hook이 새 callback으로 덮어썼는지 확인 (function이지만 동일 ref 아님)
    expect(mocks.client.onConnect).not.toBe(previousOnConnect)

    unmount()

    // 복원 확인
    expect(mocks.client.onConnect).toBe(previousOnConnect)
    expect(mocks.client.onDisconnect).toBe(previousOnDisconnect)
  })

  it('hook이 후킹한 새 callback이 호출될 때 이전 chain도 함께 호출됨', () => {
    const previousOnConnect = vi.fn()
    mocks.client.onConnect = previousOnConnect
    mocks.client.connected = false

    renderHook(() => useStompConnectionState())

    act(() => {
      mocks.client.onConnect?.({ headers: {}, command: 'CONNECTED' })
    })

    expect(previousOnConnect).toHaveBeenCalledTimes(1)
  })
})
