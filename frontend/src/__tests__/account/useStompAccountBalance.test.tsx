/**
 * useStompAccountBalance hook 단위 테스트
 *
 * polling → STOMP push 전환 (2026-05-20) — backend 매트릭스:
 *   topic: /topic/account.balance.{userId}
 *   payload: { userId, accountType, reason, triggerKisOrderNo, occurredAt }
 *
 * 검증 항목:
 * 1. userId null → subscribe 호출 안 함 (enabled=false)
 * 2. userId 정상 → /topic/account.balance.{userId} 구독
 * 3. enabled=false 명시 → subscribe 호출 안 함
 * 4. 메시지 수신 → queryClient.invalidateQueries({queryKey:['account'], exact:false}) 호출
 * 5. onBalanceChanged 콜백 호출 + latestEvent state 갱신
 * 6. REAL·MOCK 양쪽 캐시 invalidate (prefix invalidate 검증)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import React from 'react'
import {
  useStompAccountBalance,
  type AccountBalanceChangedPayload,
} from '../../api/hooks/useStompAccountBalance'

// ──────────────────────────────────────────────
// useStompTopic mock — 메시지 핸들러 캡처 + topic·enabled 인자 추적
// ──────────────────────────────────────────────

type StompHandler = (payload: AccountBalanceChangedPayload) => void

// mock 호출 인자 + 핸들러 캡처용 모듈 스코프 저장소
const stompCalls: Array<{ topic: string; enabled: boolean }> = []
let lastHandler: StompHandler | null = null

vi.mock('../../lib/stomp', () => ({
  useStompTopic: vi.fn(
    (topic: string, handler: StompHandler, enabled: boolean) => {
      stompCalls.push({ topic, enabled })
      if (enabled && topic) {
        lastHandler = handler
      }
    },
  ),
  isStompConnected: () => false,
}))

beforeEach(() => {
  stompCalls.length = 0
  lastHandler = null
})

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return {
    queryClient,
    Wrapper: function Wrapper({ children }: { children: ReactNode }) {
      return React.createElement(QueryClientProvider, { client: queryClient }, children)
    },
  }
}

const samplePayload: AccountBalanceChangedPayload = {
  userId: '74683223',
  accountType: 'REAL',
  reason: 'ORDER_FILLED',
  triggerKisOrderNo: '0000032846',
  occurredAt: '2026-05-20T15:11:55Z',
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('useStompAccountBalance', () => {
  it('userId null → subscribe 비활성 (enabled=false 로 useStompTopic 호출)', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useStompAccountBalance({ userId: null }), { wrapper: Wrapper })

    // useStompTopic 호출 자체는 됨 (rules of hooks) — 단 enabled=false
    expect(stompCalls.length).toBe(1)
    expect(stompCalls[0].enabled).toBe(false)
  })

  it('userId undefined → subscribe 비활성', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useStompAccountBalance({ userId: undefined }), { wrapper: Wrapper })

    expect(stompCalls.length).toBe(1)
    expect(stompCalls[0].enabled).toBe(false)
  })

  it('userId 정상 + enabled=true (default) → /topic/account.balance.{userId} 구독', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useStompAccountBalance({ userId: 74683223 }), { wrapper: Wrapper })

    expect(stompCalls.length).toBe(1)
    expect(stompCalls[0].topic).toBe('/topic/stomp.api.account.balance.user.74683223')
    expect(stompCalls[0].enabled).toBe(true)
  })

  it('enabled=false 명시 → subscribe 비활성 (userId 정상이어도)', () => {
    const { Wrapper } = createWrapper()
    renderHook(
      () => useStompAccountBalance({ userId: 74683223, enabled: false }),
      { wrapper: Wrapper },
    )

    expect(stompCalls.length).toBe(1)
    expect(stompCalls[0].enabled).toBe(false)
  })

  it('메시지 수신 → queryClient.invalidateQueries({queryKey:["account"], exact:false}) 호출', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    renderHook(() => useStompAccountBalance({ userId: 74683223 }), { wrapper: Wrapper })

    // STOMP 핸들러 캡처 확인
    expect(lastHandler).not.toBeNull()

    // STOMP 메시지 수신 시뮬레이션
    act(() => {
      lastHandler?.(samplePayload)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['account'],
        exact: false,
      })
    })
  })

  it('메시지 수신 → onBalanceChanged 콜백 호출 + latestEvent 갱신', async () => {
    const onBalanceChanged = vi.fn()
    const { Wrapper } = createWrapper()

    const { result } = renderHook(
      () => useStompAccountBalance({ userId: 74683223, onBalanceChanged }),
      { wrapper: Wrapper },
    )

    expect(result.current.latestEvent).toBeNull()
    expect(onBalanceChanged).not.toHaveBeenCalled()

    // 메시지 수신
    act(() => {
      lastHandler?.(samplePayload)
    })

    await waitFor(() => {
      expect(result.current.latestEvent).toEqual(samplePayload)
    })
    expect(onBalanceChanged).toHaveBeenCalledTimes(1)
    expect(onBalanceChanged).toHaveBeenCalledWith(samplePayload)
  })

  it('REAL·MOCK 양쪽 캐시 invalidate — prefix ["account"] 만 invalidate (queryKey 4-tuple 양쪽 cover)', async () => {
    const { queryClient, Wrapper } = createWrapper()

    // REAL·MOCK 양쪽 캐시 미리 세팅 (실제 4-tuple queryKey 구조 정합)
    queryClient.setQueryData(['account', 'summary', 74683223, 'REAL'], { realData: true })
    queryClient.setQueryData(['account', 'summary', 74683223, 'MOCK'], { mockData: true })
    queryClient.setQueryData(['account', 'balance', 74683223, 'REAL'], { realBal: true })
    queryClient.setQueryData(['account', 'positions', 74683223, 'MOCK'], { mockPos: true })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    renderHook(() => useStompAccountBalance({ userId: 74683223 }), { wrapper: Wrapper })

    // accountType=REAL 메시지 수신
    act(() => {
      lastHandler?.(samplePayload)
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['account'],
        exact: false,
      })
    })

    // prefix invalidate 가 양쪽 caches 모두 stale 로 마킹했는지 검증.
    // (TanStack Query v5: invalidateQueries 호출 시 matching queries 가 stale 상태로 전환)
    const realSummary = queryClient.getQueryState(['account', 'summary', 74683223, 'REAL'])
    const mockSummary = queryClient.getQueryState(['account', 'summary', 74683223, 'MOCK'])
    const realBalance = queryClient.getQueryState(['account', 'balance', 74683223, 'REAL'])
    const mockPositions = queryClient.getQueryState(['account', 'positions', 74683223, 'MOCK'])

    // 네 캐시 모두 invalidated 상태 (isInvalidated=true) — REAL·MOCK·summary·balance·positions 모두 cover
    expect(realSummary?.isInvalidated).toBe(true)
    expect(mockSummary?.isInvalidated).toBe(true)
    expect(realBalance?.isInvalidated).toBe(true)
    expect(mockPositions?.isInvalidated).toBe(true)
  })

  it('topic 경로 — userId 정수형이 정확한 형식 (백엔드 매트릭스 LOCK)', () => {
    const { Wrapper } = createWrapper()
    renderHook(() => useStompAccountBalance({ userId: 1 }), { wrapper: Wrapper })

    expect(stompCalls[0].topic).toBe('/topic/stomp.api.account.balance.user.1')
  })

  it('reason 3가지 (ORDER_FILLED/ORDER_CANCELLED/ORDER_REJECTED) 수신 모두 invalidate 트리거', async () => {
    const onBalanceChanged = vi.fn()
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    renderHook(
      () => useStompAccountBalance({ userId: 74683223, onBalanceChanged }),
      { wrapper: Wrapper },
    )

    const reasons: Array<AccountBalanceChangedPayload['reason']> = [
      'ORDER_FILLED',
      'ORDER_CANCELLED',
      'ORDER_REJECTED',
    ]

    for (const reason of reasons) {
      act(() => {
        lastHandler?.({ ...samplePayload, reason })
      })
    }

    await waitFor(() => {
      expect(onBalanceChanged).toHaveBeenCalledTimes(3)
    })
    // 매번 invalidate 호출
    expect(invalidateSpy).toHaveBeenCalledTimes(3)
  })
})
