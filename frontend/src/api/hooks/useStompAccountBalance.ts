/**
 * useStompAccountBalance — 계좌 잔고 변경 실시간 STOMP 구독 (T-ACCT C-3 / 2026-05-20 — polling → push)
 *
 * STOMP topic: /topic/account.balance.{userId}
 *   accountType (REAL/MOCK) 은 payload 에 포함 → 단일 topic 구독으로 양쪽 계좌 invalidate.
 *
 * backend 매트릭스 (병렬 PR backend-core ‘feat(backend-core): account balance STOMP push’):
 *   Kafka topic: account.balance.changed.v1
 *   STOMP topic: /topic/account.balance.{userId}
 *   payload: { userId, accountType, reason, triggerKisOrderNo, occurredAt }
 *     reason ∈ { ORDER_FILLED, ORDER_CANCELLED, ORDER_REJECTED }
 *
 * 동작:
 * - 메시지 수신 시 invalidateQueries({ queryKey: ['account', userId], exact: false })
 *   → useAccountSummary·useAccountBalance·useAccountPositions 3 hook 모두 즉시 refetch
 * - REAL·MOCK 양쪽 캐시 동시 invalidate (queryKey 가 ['account', kind, userId, accountType] 구조라
 *   prefix ['account'] 기반 invalidate 시 양쪽 cover)
 * - 토스트 불요 — useStompOrder 가 이미 주문 이벤트 토스트 표시 중 (중복 spam 방지).
 *   잔고 변경은 invalidate 만 silent 처리.
 *
 * 사용 예:
 *   useStompAccountBalance({ userId, enabled: true })
 *
 * polling fallback: useAccountSummary 등 3 hook 의 refetchInterval 30s 가 STOMP 끊김 시 cover.
 */
import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useStompTopic } from '../../lib/stomp'
import { accountBalanceUser } from '../stompDestinations'

// ──────────────────────────────────────────────
// 타입 (backend matrix LOCK)
// ──────────────────────────────────────────────

export type AccountBalanceChangeReason =
  | 'ORDER_FILLED'
  | 'ORDER_CANCELLED'
  | 'ORDER_REJECTED'

export interface AccountBalanceChangedPayload {
  userId: string
  accountType: 'REAL' | 'MOCK'
  reason: AccountBalanceChangeReason
  triggerKisOrderNo?: string
  occurredAt: string
}

export interface UseStompAccountBalanceOptions {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number | null | undefined
  enabled?: boolean
  /** 메시지 수신 시 외부 콜백 (디버깅·로깅용) */
  onBalanceChanged?: (payload: AccountBalanceChangedPayload) => void
}

export interface UseStompAccountBalanceResult {
  /** 가장 최근 수신 변경 이벤트 (디버깅 / DashboardPage 상태 표시용) */
  latestEvent: AccountBalanceChangedPayload | null
}

export function useStompAccountBalance({
  userId,
  enabled = true,
  onBalanceChanged,
}: UseStompAccountBalanceOptions): UseStompAccountBalanceResult {
  const [latestEvent, setLatestEvent] = useState<AccountBalanceChangedPayload | null>(null)
  const onEventRef = useRef(onBalanceChanged)
  onEventRef.current = onBalanceChanged

  const queryClient = useQueryClient()

  const topic = userId !== null && userId !== undefined ? accountBalanceUser(userId) : ''

  const handleBalanceChanged = useCallback(
    (payload: AccountBalanceChangedPayload) => {
      setLatestEvent(payload)

      // ['account', 'summary' | 'balance' | 'positions', userId, accountType] 양방향 invalidate.
      // exact:false 로 REAL·MOCK 캐시 둘 다 cover.
      void queryClient.invalidateQueries({
        queryKey: ['account'],
        exact: false,
      })

      onEventRef.current?.(payload)
    },
    [queryClient],
  )

  useStompTopic<AccountBalanceChangedPayload>(
    topic,
    handleBalanceChanged,
    enabled && userId !== null && userId !== undefined,
  )

  return { latestEvent }
}
