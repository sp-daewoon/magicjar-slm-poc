/**
 * useCandleHealth — 캔들 수집 건강도 TanStack Query hook (readiness P1)
 *
 * 목적: 종목 상세에서 캔들이 stale일 때 "왜 수집이 안 됐는지"를 1줄로 노출.
 *
 * 폴링 정책 (과호출 금지 — 디스패치 §3):
 *   - 상세 진입 시 1회 + 60s 간격 (시세 polling보다 느린 저빈도).
 *   - refetchIntervalInBackground=false → 백그라운드 탭에서는 멈춤.
 *
 * graceful (사용자 흐름 mock 합성 금지):
 *   - 404/에러는 isError로 노출 → 컴포넌트가 "수집 상태 확인 불가" 명시.
 *   - 가짜 FRESH 합성 금지. retry 1로 백엔드 미머지 상태 빠르게 graceful 처리.
 *
 * orchestrator LOCK 계약 (market-data 병렬 구현 중):
 *   GET /api/symbols/{code}/candle-health → CandleHealthDto
 */
import { useQuery } from '@tanstack/react-query'
import { symbolApi } from '../symbol'

export const CANDLE_HEALTH_POLL_INTERVAL_MS = 60_000
export const CANDLE_HEALTH_STALE_TIME_MS = 50_000

export interface UseCandleHealthOptions {
  /** false면 조회 비활성 (상장폐지 등). 기본 true. */
  enabled?: boolean
  /** 폴링 주기. 기본 60초. */
  refetchInterval?: number
}

export function useCandleHealth(code: string, options: UseCandleHealthOptions = {}) {
  const { enabled = true, refetchInterval = CANDLE_HEALTH_POLL_INTERVAL_MS } = options

  return useQuery({
    queryKey: ['candle-health', code] as const,
    queryFn: () => symbolApi.getCandleHealth(code),
    refetchInterval: enabled ? refetchInterval : false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: false,
    staleTime: CANDLE_HEALTH_STALE_TIME_MS,
    enabled: Boolean(code) && enabled,
    // 백엔드 미머지/일시 장애 시 빠르게 graceful — 무한 재시도로 화면 막지 않음.
    retry: 1,
  })
}
