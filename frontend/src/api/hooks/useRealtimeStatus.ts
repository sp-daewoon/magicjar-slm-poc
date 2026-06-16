/**
 * useRealtimeStatus — 실시간 구독 상태 REST polling hook
 *
 * 화면: /admin/realtime (RealtimeStatusPage)
 *
 * ### 동작 매트릭스
 * - refetchInterval: 5s — 관리자 장애 처리 중 실시간 추적 (사용자 결재 5초 auto-refresh)
 * - refetchIntervalInBackground: false — 탭 비활성 시 polling 정지 (불필요 부하 0)
 * - staleTime: 4s — polling tick 직전까지 캐시 신뢰
 * - retry: 1 — 일시 오류 1회 재시도 후 isError 노출 (정직 에러 표기)
 *
 * 데이터 권위: consumer 가 갱신하는 KIS WS 상태 스냅샷.
 * stale=true 면 consumer 가 상태를 못 쓰고 있음 (UI 빨간 경고).
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { adminRealtimeApi, type RealtimeStatus } from '../adminRealtime'

/** auto-refresh 주기 — 사용자 결재 5초 LOCK */
export const REALTIME_STATUS_POLL_MS = 5_000

/** polling tick 직전까지 캐시 신뢰 윈도우 */
const STALE_TIME_MS = 4_000

/**
 * 실시간 구독 상태 조회 (TanStack Query useQuery wrap).
 *
 * @returns {@link UseQueryResult}<{@link RealtimeStatus}>
 */
export function useRealtimeStatus(): UseQueryResult<RealtimeStatus> {
  return useQuery<RealtimeStatus>({
    queryKey: ['admin-realtime-status'],
    queryFn: () => adminRealtimeApi.status(),
    refetchInterval: REALTIME_STATUS_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: STALE_TIME_MS,
    retry: 1,
  })
}
