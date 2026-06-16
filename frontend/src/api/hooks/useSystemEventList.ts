/**
 * useSystemEventList — 시스템 이벤트 목록 REST hook (P2 Task 5)
 *
 * STOMP push (useSystemEventStompSubscription, Task 4) 의 **fallback polling** path.
 *
 * ### 동작 매트릭스
 * - refetchInterval: 30s — STOMP 끊김 시 cover
 * - staleTime: 25s — STOMP invalidate 또는 polling tick 전까지 캐시 신뢰
 * - refetchIntervalInBackground: false — 탭 비활성 시 polling 정지 (KIS 호출 0)
 * - queryKey: ['system-event', 'list', params] — STOMP invalidate prefix ['system-event'] 와 정합
 *   (useSystemEventStompSubscription 에서 invalidateQueries({queryKey:['system-event'], exact:false}) 호출 시 매칭)
 *
 * ### 사용 patterns
 * - NotificationCenter (Task 6): `useSystemEventList({ userId, unreadOnly: true, page: 0, size: 10 })`
 * - SystemEventPage (Task 7):    `useSystemEventList({ unreadOnly, page, size: 20 })`
 *
 * ### Source of truth
 * - REST endpoint: GET /api/system-event (backend Phase A SystemEventController)
 * - STOMP push:    useSystemEventStompSubscription (사용자 화면 진입 시 항상 active)
 *
 * spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.6·§13
 * plan: _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 5
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { systemEventApi, type ListParams, type SystemEventPage } from '../system-event'

/** STOMP 끊김 fallback polling 주기 — spec §6.6 LOCK */
const POLLING_INTERVAL_MS = 30_000

/** STOMP push 가 healthy 일 때 캐시 신뢰 윈도우 — polling tick 보다 약간 짧게 */
const STALE_TIME_MS = 25_000

/**
 * 시스템 이벤트 목록 조회 (TanStack Query useQuery wrap).
 *
 * @param params {@link ListParams} (userId·unreadOnly·page·size 모두 optional)
 * @returns {@link UseQueryResult}<{@link SystemEventPage}>
 */
export function useSystemEventList(
  params: ListParams = {},
): UseQueryResult<SystemEventPage> {
  return useQuery<SystemEventPage>({
    queryKey: ['system-event', 'list', params],
    queryFn: () => systemEventApi.list(params),
    refetchInterval: POLLING_INTERVAL_MS,
    refetchIntervalInBackground: false,
    staleTime: STALE_TIME_MS,
  })
}
