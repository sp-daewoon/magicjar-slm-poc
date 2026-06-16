/**
 * useSystemEventStompSubscription 단위 테스트 (P2 Task 4)
 *
 * 검증 매트릭스 (plan Task 4 Step 1):
 * 1. userId 정상 → user-specific topic + broadcast topic 둘 다 subscribe (2개 useStompTopic 호출)
 * 2. userId null → user-specific topic 미구독 (enabled=false) + broadcast 만 enabled=true
 *
 * 추가 검증 (PR #622 패턴 정합):
 * 3. 메시지 수신 시 queryClient.invalidateQueries({queryKey:['system-event'], exact:false}) 호출
 * 4. severity=CRITICAL 수신 시 toast 표시 (severity != CRITICAL 시 toast 0건)
 *
 * mock 전략:
 * - useStompTopic mock: (topic, handler, enabled) 호출 인자 캡처 + handler 보관
 *   broadcast 가 항상 호출되므로 sequential index 로 user-specific(0) / broadcast(1) 구분
 * - useToast mock: showToast spy
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import React from 'react'
import {
  useSystemEventStompSubscription,
} from '../useSystemEventStompSubscription'
import type { SystemEvent } from '../../system-event'

// ──────────────────────────────────────────────
// useStompTopic mock — 호출 인자 + handler 캡처
// ──────────────────────────────────────────────

type StompHandler = (payload: SystemEvent) => void

const stompCalls: Array<{ topic: string; enabled: boolean }> = []
const handlers: StompHandler[] = []

vi.mock('../../../lib/stomp', () => ({
  useStompTopic: vi.fn(
    (topic: string, handler: StompHandler, enabled: boolean) => {
      stompCalls.push({ topic, enabled })
      handlers.push(handler)
    },
  ),
  isStompConnected: () => false,
}))

// ──────────────────────────────────────────────
// useToast mock — showToast spy
// ──────────────────────────────────────────────

const showToastMock = vi.fn()

vi.mock('../../../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: showToastMock }),
}))

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

beforeEach(() => {
  stompCalls.length = 0
  handlers.length = 0
  showToastMock.mockClear()
})

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    Wrapper: function Wrapper({ children }: { children: ReactNode }) {
      return React.createElement(QueryClientProvider, { client: queryClient }, children)
    },
  }
}

const criticalEvent: SystemEvent = {
  id: 99,
  category: 'KIS_RATELIMIT',
  severity: 'CRITICAL',
  actor: 'KisRateLimiter',
  action: 'KIS_RATELIMIT_EXHAUSTED',
  userId: '74683223',
  correlationId: null,
  detailJson: '{"window":"1s","exceeded":20}',
  occurredAt: '2026-05-20T15:11:00Z',
  viewedAt: null,
  resolvedAt: null,
}

const warnEvent: SystemEvent = {
  ...criticalEvent,
  id: 100,
  severity: 'WARN',
  action: 'WS_DISCONNECTED',
  category: 'WEBSOCKET',
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('useSystemEventStompSubscription', () => {
  it('userId 정상 → user-specific topic 과 broadcast topic 둘 다 subscribe', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useSystemEventStompSubscription('74683223'), { wrapper: Wrapper })

    // useStompTopic 가 2번 호출됨 (rules of hooks — 순서·횟수 고정)
    expect(stompCalls.length).toBe(2)

    // 0번 호출 = user-specific
    expect(stompCalls[0].topic).toBe('/topic/stomp.api.system.event.user.74683223')
    expect(stompCalls[0].enabled).toBe(true)

    // 1번 호출 = broadcast
    expect(stompCalls[1].topic).toBe('/topic/stomp.api.system.event.broadcast')
    expect(stompCalls[1].enabled).toBe(true)
  })

  it('userId null → user-specific 미구독 (enabled=false) + broadcast 만 enabled=true', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useSystemEventStompSubscription(null), { wrapper: Wrapper })

    expect(stompCalls.length).toBe(2)

    // user-specific 은 enabled=false (rules of hooks — 호출 자체는 됨)
    expect(stompCalls[0].enabled).toBe(false)

    // broadcast 는 항상 enabled
    expect(stompCalls[1].topic).toBe('/topic/stomp.api.system.event.broadcast')
    expect(stompCalls[1].enabled).toBe(true)
  })

  it('메시지 수신 → queryClient.invalidateQueries({queryKey:["system-event"], exact:false}) 호출', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    renderHook(() => useSystemEventStompSubscription('74683223'), { wrapper: Wrapper })

    // user-specific topic handler 호출
    expect(handlers.length).toBe(2)
    act(() => {
      handlers[0](warnEvent)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['system-event'],
        exact: false,
      })
    })
  })

  it('severity=CRITICAL 수신 → showToast 호출 (error type)', async () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useSystemEventStompSubscription('74683223'), { wrapper: Wrapper })

    act(() => {
      handlers[0](criticalEvent)
    })

    await waitFor(() => {
      expect(showToastMock).toHaveBeenCalledTimes(1)
    })
    // 메시지에 category·action·detail 포함 검증
    const [msg, type] = showToastMock.mock.calls[0]
    expect(msg).toContain('KIS_RATELIMIT')
    expect(msg).toContain('KIS_RATELIMIT_EXHAUSTED')
    expect(type).toBe('error')
  })

  it('severity != CRITICAL 수신 → showToast 0건 (invalidate 만)', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    renderHook(() => useSystemEventStompSubscription('74683223'), { wrapper: Wrapper })

    act(() => {
      handlers[0](warnEvent)  // severity=WARN
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalled()
    })
    expect(showToastMock).not.toHaveBeenCalled()
  })
})
