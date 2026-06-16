/**
 * useAccountSummary·useAccountBalance·useAccountPositions polling 게이트 회귀 테스트
 *
 * polling → STOMP push 전환 (2026-05-20): refetchInterval 5s → 30s.
 *
 * 폴링 정리 (2026-06-08 — KIS_TPS_EXCEEDED burst 근본 정리, 사용자 dogfooding):
 *   STOMP 게이트 도입 — STOMP CONNECTED 시 폴링 off (refetchInterval=false),
 *   끊김 시에만 60s fallback. refetchInterval prop 명시 시 강제 override.
 *
 * 검증 항목:
 * 1. userId null → 쿼리 비활성 (호출 0)
 * 2. 정상 호출 → 1회 fetch + data 반환
 * 3. STOMP CONNECTED → fallback 폴링 발생 안 함 (5s·30s 회귀 방지 + 폴링 0 게이트)
 * 4. refetchInterval 명시 override → STOMP 상태 무관 폴링 (기존 시그니처 보존)
 *
 * STOMP 연결 상태는 lib/stomp getStompClient mock 으로 제어.
 */
import { describe, it, expect, beforeAll, beforeEach, afterAll, afterEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { ReactNode } from 'react'
import React from 'react'

// ──────────────────────────────────────────────
// STOMP client mock — 연결 상태 제어 (게이트 입력)
// ──────────────────────────────────────────────

const stompMocks = vi.hoisted(() => ({
  client: {
    connected: false as boolean,
    onConnect: undefined as ((frame: unknown) => void) | undefined,
    onDisconnect: undefined as ((frame: unknown) => void) | undefined,
    onWebSocketClose: undefined as ((event: unknown) => void) | undefined,
    onStompError: undefined as ((frame: unknown) => void) | undefined,
  },
}))

vi.mock('../../lib/stomp', () => ({
  getStompClient: () => stompMocks.client,
}))

import { useAccountSummary } from '../../api/hooks/useAccountSummary'
import { useAccountBalance } from '../../api/hooks/useAccountBalance'
import { useAccountPositions } from '../../api/hooks/useAccountPositions'

// ──────────────────────────────────────────────
// msw 서버 (account API stub)
// ──────────────────────────────────────────────

let summaryCallCount = 0
let balanceCallCount = 0
let positionsCallCount = 0

const server = setupServer(
  http.get('/api/account/summary', () => {
    summaryCallCount += 1
    return HttpResponse.json({
      totalEvaluation: 10_000_000,
      cashD2: 5_000_000,
      totalProfitLoss: 100_000,
      profitLossRate: 1.0,
      holdingsCount: 3,
      fetchedAt: '2026-05-20T15:00:00Z',
    })
  }),
  http.get('/api/account/balance', () => {
    balanceCallCount += 1
    return HttpResponse.json({
      deposit: 5_000_000,
      depositD1: 5_000_000,
      depositD2: 5_000_000,
      totalEvalAmount: 10_000_000,
      netAssetAmount: 10_000_000,
      buyAmount: 5_000_000,
      evalAmount: 5_100_000,
      evalProfitLoss: 100_000,
      fetchedAt: '2026-05-20T15:00:00Z',
    })
  }),
  http.get('/api/account/positions', () => {
    positionsCallCount += 1
    return HttpResponse.json([])
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
beforeEach(() => {
  // 기본 — STOMP 미연결 (CONNECTING). 게이트 테스트에서 개별 override.
  stompMocks.client.connected = false
  stompMocks.client.onConnect = undefined
  stompMocks.client.onDisconnect = undefined
  stompMocks.client.onWebSocketClose = undefined
  stompMocks.client.onStompError = undefined
})
afterEach(() => {
  server.resetHandlers()
  summaryCallCount = 0
  balanceCallCount = 0
  positionsCallCount = 0
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return React.createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('account hooks 폴링 게이트 (STOMP push 전환 + STOMP 게이트)', () => {
  it('useAccountSummary userId null → 쿼리 비활성 (호출 0)', async () => {
    const { result } = renderHook(() => useAccountSummary({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(summaryCallCount).toBe(0)
  })

  it('useAccountBalance userId null → 쿼리 비활성', () => {
    const { result } = renderHook(() => useAccountBalance({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(balanceCallCount).toBe(0)
  })

  it('useAccountPositions userId null → 쿼리 비활성', () => {
    const { result } = renderHook(() => useAccountPositions({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(positionsCallCount).toBe(0)
  })

  it('useAccountSummary 정상 호출 → 1회 fetch + data 반환', async () => {
    const { result } = renderHook(
      () => useAccountSummary({ userId: 74683223 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    expect(result.current.data?.totalEvaluation).toBe(10_000_000)
    expect(summaryCallCount).toBe(1)
  })

  it('STOMP CONNECTED 시 fallback 폴링 0 — 초기 1회 fetch 뒤 추가 호출 없음', async () => {
    // STOMP 연결됨 → 게이트가 refetchInterval=false → push 가 primary, 폴링 off.
    stompMocks.client.connected = true

    const { result } = renderHook(
      () => useAccountBalance({ userId: 74683223 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    // 300ms 대기 — 폴링이 살아 있었다면(5s·30s·60s 무관) refetchInterval=false 라 추가 호출 0
    await new Promise((r) => setTimeout(r, 300))
    expect(balanceCallCount).toBe(1)
  })

  it('STOMP 미연결 fallback — 60s 미만에는 추가 폴링 없음 (5s·30s 회귀 방지)', async () => {
    // STOMP 끊김(default) → 60s fallback. 200ms 안에는 폴링 추가 호출 없어야 함.
    const { result } = renderHook(
      () => useAccountBalance({ userId: 74683223 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    await new Promise((r) => setTimeout(r, 200))
    expect(balanceCallCount).toBe(1)
  })

  it('useAccountSummary refetchInterval 명시 override → STOMP 게이트 무시 폴링 (시그니처 보존)', async () => {
    // STOMP 연결돼 있어도 refetchInterval 명시 시 강제 폴링 (기존 호출부 호환).
    stompMocks.client.connected = true

    const { result } = renderHook(
      () => useAccountSummary({ userId: 74683223, refetchInterval: 100 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    // refetchInterval 100ms 명시 → 350ms 대기 시 추가 polling 발생
    await new Promise((r) => setTimeout(r, 350))
    expect(summaryCallCount).toBeGreaterThanOrEqual(2)
  })
})
