/**
 * useKisWsHealthState 단위 테스트 — 사용자 dogfooding 2026-05-20
 *
 * 4 시나리오 (사용자 임무 명세):
 *   1. WS_CONNECTED·WS_RECONNECTED 이벤트 latest → CONNECTED 전환
 *   2. WS_DISCONNECTED 이벤트 latest → DISCONNECTED + warning toast
 *   3. useStompConnectionState DISCONNECTED → UNKNOWN
 *   4. DISCONNECTED → RECONNECTED 복귀 시 success toast
 *
 * 추가 검증:
 *   - 첫 mount 초기 상태 toast 0건 (사용자 인지 방해 회피)
 *   - actor ≠ KisWebSocketBridge 이벤트 → UNKNOWN (필터링 정확도)
 *   - decideKisWsStateFromEvents pure helper 단독 검증
 *
 * mock 전략:
 *   - useStompConnectionState mock — STOMP 상태 강제
 *   - useSystemEventList mock — events 목록 강제
 *   - useOptionalToast mock — showToast spy
 */
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SystemEvent } from '../../system-event'

// ──────────────────────────────────────────────
// mocks
// ──────────────────────────────────────────────

const mocks = vi.hoisted(() => ({
  showToast: vi.fn(),
  stompState: 'CONNECTED' as 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING',
  events: [] as SystemEvent[],
  isLoading: false as boolean,
  hasData: true as boolean,
}))

vi.mock('../useStompConnectionState', () => ({
  useStompConnectionState: () => mocks.stompState,
}))

vi.mock('../useSystemEventList', () => ({
  useSystemEventList: () => ({
    data: mocks.hasData
      ? {
          items: mocks.events,
          totalCount: mocks.events.length,
          unreadCount: 0,
          page: 0,
          size: 20,
        }
      : undefined,
    isLoading: mocks.isLoading,
  }),
}))

vi.mock('../../../components/ui/ToastProvider', () => ({
  useOptionalToast: () => ({ showToast: mocks.showToast }),
}))

import { useKisWsHealthState, decideKisWsStateFromEvents } from '../useKisWsHealthState'

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function makeEvent(overrides: Partial<SystemEvent> = {}): SystemEvent {
  return {
    id: 1,
    category: 'WEBSOCKET',
    severity: 'INFO',
    actor: 'KisWebSocketBridge',
    action: 'WS_CONNECTED',
    userId: null,
    correlationId: 'cid-test',
    detailJson: '{}',
    occurredAt: '2026-05-20T10:00:00Z',
    viewedAt: null,
    resolvedAt: null,
    ...overrides,
  }
}

beforeEach(() => {
  mocks.showToast.mockReset()
  mocks.stompState = 'CONNECTED'
  mocks.events = []
  mocks.isLoading = false
  mocks.hasData = true
})

// ──────────────────────────────────────────────
// decideKisWsStateFromEvents — pure helper 단독 검증
// ──────────────────────────────────────────────

describe('decideKisWsStateFromEvents', () => {
  it('이벤트 0건 → UNKNOWN', () => {
    expect(decideKisWsStateFromEvents([])).toBe('UNKNOWN')
  })

  it('KisWebSocketBridge actor 외 이벤트만 존재 → UNKNOWN', () => {
    const events = [
      makeEvent({ actor: 'OtherActor', action: 'WS_CONNECTED' }),
    ]
    expect(decideKisWsStateFromEvents(events)).toBe('UNKNOWN')
  })

  it('latest action = WS_RECONNECTED → CONNECTED', () => {
    const events = [
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
      makeEvent({
        action: 'WS_RECONNECTED',
        severity: 'INFO',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    expect(decideKisWsStateFromEvents(events)).toBe('CONNECTED')
  })

  it('latest action = WS_CONNECTED → CONNECTED', () => {
    const events = [
      makeEvent({
        action: 'WS_CONNECTED',
        severity: 'INFO',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    expect(decideKisWsStateFromEvents(events)).toBe('CONNECTED')
  })

  it('latest action = WS_DISCONNECTED → DISCONNECTED', () => {
    const events = [
      makeEvent({
        action: 'WS_CONNECTED',
        severity: 'INFO',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    expect(decideKisWsStateFromEvents(events)).toBe('DISCONNECTED')
  })

  it('KisWebSocketBridge 이벤트와 다른 actor 섞임 → KIS WS만 보고 판정', () => {
    const events = [
      makeEvent({
        actor: 'KisWebSocketBridge',
        action: 'WS_DISCONNECTED',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
      makeEvent({
        actor: 'OtherActor',
        action: 'SOMETHING_LATER',
        occurredAt: '2026-05-20T11:00:00Z',
      }),
    ]
    // OtherActor 이벤트가 더 최신이지만 KIS WS만 필터해 DISCONNECTED 유지
    expect(decideKisWsStateFromEvents(events)).toBe('DISCONNECTED')
  })
})

// ──────────────────────────────────────────────
// useKisWsHealthState — hook 통합 검증
// ──────────────────────────────────────────────

describe('useKisWsHealthState', () => {
  it('WS_CONNECTED·WS_RECONNECTED 이벤트 latest → CONNECTED 전환 (첫 mount toast 0건)', () => {
    mocks.stompState = 'CONNECTED'
    mocks.events = [
      makeEvent({
        action: 'WS_RECONNECTED',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]

    const { result } = renderHook(() => useKisWsHealthState())

    expect(result.current).toBe('CONNECTED')
    // 첫 mount toast 0건
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('WS_DISCONNECTED 이벤트 latest → DISCONNECTED + warning toast', () => {
    mocks.stompState = 'CONNECTED'
    // 1차 render: CONNECTED 상태로 시작
    mocks.events = [
      makeEvent({
        action: 'WS_RECONNECTED',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
    ]
    const { result, rerender } = renderHook(() => useKisWsHealthState())
    expect(result.current).toBe('CONNECTED')

    // 2차 render: WS_DISCONNECTED 도착
    mocks.events = [
      makeEvent({
        action: 'WS_RECONNECTED',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    act(() => {
      rerender()
    })

    expect(result.current).toBe('DISCONNECTED')
    expect(mocks.showToast).toHaveBeenCalledWith(
      'KIS WS 끊김 — 시세·호가·체결 영향 (재연결 시도 중)',
      'warning',
    )
  })

  it('useStompConnectionState DISCONNECTED → UNKNOWN (SystemEvent 도착 불가)', () => {
    mocks.stompState = 'DISCONNECTED'
    // KIS WS 자체는 CONNECTED 이벤트 받았더라도 STOMP가 끊겼으면 UNKNOWN
    mocks.events = [
      makeEvent({
        action: 'WS_RECONNECTED',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]

    const { result } = renderHook(() => useKisWsHealthState())

    expect(result.current).toBe('UNKNOWN')
  })

  it('DISCONNECTED → RECONNECTED 복귀 시 success toast', () => {
    mocks.stompState = 'CONNECTED'
    // 1차 render: DISCONNECTED
    mocks.events = [
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
    ]
    const { result, rerender } = renderHook(() => useKisWsHealthState())
    expect(result.current).toBe('DISCONNECTED')
    // 첫 mount toast 0건 검증
    expect(mocks.showToast).not.toHaveBeenCalled()

    // 2차 render: WS_RECONNECTED 도착
    mocks.events = [
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
      makeEvent({
        action: 'WS_RECONNECTED',
        severity: 'INFO',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    act(() => {
      rerender()
    })

    expect(result.current).toBe('CONNECTED')
    expect(mocks.showToast).toHaveBeenCalledWith('KIS WS 재연결 완료', 'success')
  })

  it('초기 로딩 (data 미도착) → UNKNOWN (mock 합성 0건 정책 정합)', () => {
    mocks.stompState = 'CONNECTED'
    mocks.isLoading = true
    mocks.hasData = false
    mocks.events = []

    const { result } = renderHook(() => useKisWsHealthState())

    expect(result.current).toBe('UNKNOWN')
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('enableToast=false → 전환 발생해도 toast 0건', () => {
    mocks.stompState = 'CONNECTED'
    mocks.events = [
      makeEvent({
        action: 'WS_RECONNECTED',
        occurredAt: '2026-05-20T09:00:00Z',
      }),
    ]
    const { rerender } = renderHook(() =>
      useKisWsHealthState({ enableToast: false }),
    )

    // CONNECTED → DISCONNECTED 전환
    mocks.events = [
      makeEvent({
        action: 'WS_DISCONNECTED',
        severity: 'WARN',
        occurredAt: '2026-05-20T10:00:00Z',
      }),
    ]
    act(() => {
      rerender()
    })

    expect(mocks.showToast).not.toHaveBeenCalled()
  })
})
