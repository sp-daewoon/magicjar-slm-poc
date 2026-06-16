/**
 * useSystemEventStompSubscription — 시스템 이벤트 STOMP 구독 (P2 Task 4)
 *
 * spec §6.5·§11 정합. PR #622 (useStompAccountBalance) 패턴 1:1.
 *
 * ### 2 topic 분기 구독
 * - `/topic/system.event.user.{userId}` — user-specific (userId 있을 때만, 없으면 enabled=false)
 * - `/topic/system.event.broadcast`     — system-wide (항상 구독)
 *
 * backend `SystemEventStompNotifier` (P2 Task 1) 가 Kafka `system.event.v1` →
 *   - event.userId != null → user-specific
 *   - event.userId == null → broadcast
 * 로 fanout.
 *
 * ### 메시지 수신 동작
 * 1. `queryClient.invalidateQueries({queryKey:['system-event'], exact:false})`
 *    → useSystemEventList 30s polling 캐시 즉시 stale → refetch
 * 2. `event.severity === 'CRITICAL'` 시 `showToast` (error type) 표시
 *    INFO/WARN/ERROR 는 silent (UI 알림센터 벨 badge 만 변경 — toast spam 방지)
 *
 * ### graceful 처리
 * - STOMP 연결 실패 → useStompTopic 내부에서 silent skip + useSystemEventList polling fallback (Task 5) 으로 cover.
 * - JSON 파싱 실패 → useStompTopic 내부 try/catch 로 skip.
 *
 * ### 사용 예 (Header layout — Task 8 에서 통합)
 * ```tsx
 * function Header() {
 *   const userId = useUserId()  // string | null
 *   useSystemEventStompSubscription(userId)
 *   return <header>...<NotificationCenter userId={userId} />...</header>
 * }
 * ```
 *
 * spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.5·§11
 * plan: _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 4
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useStompTopic } from '../../lib/stomp'
import { systemEventUser, systemEventBroadcast } from '../stompDestinations'
import { useToast } from '../../components/ui/ToastProvider'
import type { SystemEvent } from '../system-event'

/**
 * 시스템 이벤트 2 topic 구독 + invalidate + CRITICAL toast.
 *
 * @param userId 사용자 ID (null = user-specific 미구독, broadcast 만 활성)
 */
export function useSystemEventStompSubscription(userId: string | null): void {
  const queryClient = useQueryClient()
  const { showToast } = useToast()

  // 메시지 수신 핸들러 — user-specific·broadcast 둘 다 동일 로직
  const handleEvent = useCallback(
    (event: SystemEvent) => {
      // 1) 모든 system-event 캐시 invalidate (list·dashboard·notification center)
      void queryClient.invalidateQueries({
        queryKey: ['system-event'],
        exact: false,
      })

      // 2) CRITICAL 만 toast (spam 방지 — INFO/WARN/ERROR 는 벨 badge 만)
      if (event.severity === 'CRITICAL') {
        const msg = `[${event.category}] ${event.action}: ${event.detailJson}`
        showToast(msg, 'error')
      }
    },
    [queryClient, showToast],
  )

  // user-specific topic — userId 있을 때만 enabled
  const userTopic = userId ? systemEventUser(userId) : ''
  useStompTopic<SystemEvent>(userTopic, handleEvent, userId !== null && userId !== '')

  // broadcast topic — 항상 구독
  useStompTopic<SystemEvent>(systemEventBroadcast, handleEvent, true)
}
