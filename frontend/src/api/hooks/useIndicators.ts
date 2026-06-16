/**
 * useIndicators — 지표 시계열 TanStack Query hook
 *
 * spec §6-3 REST endpoint LOCK:
 *   GET /api/indicators?code=...&name=...&interval=...&params=...&from=...&to=...
 *
 * queryKey: ['indicators', code, name, interval, params]
 * - interval별 staleTime: 분봉=60초, 일봉=300초 (backend Redis TTL 정합)
 *
 * 에러 핸들링:
 * - 미지원 지표(400): isError=true + errorMessage 추출
 * - 네트워크 실패: retry=1
 * - 캔들 부족(400): 동일 처리
 *
 * ADR 0023: AI import 0 — T-IND는 W2, LLM 금지
 */
import { useQuery } from '@tanstack/react-query'
import { indicatorApi, type IndicatorResultDto } from '../indicator'
import type { CandleInterval } from '../symbol'

/** 인터벌이 분봉인지 여부 */
function isIntradayInterval(interval: string): boolean {
  return ['1m', '3m', '5m', '15m', '30m', '1h', '90m', '4h', '12h'].includes(interval)
}

/** 인터벌별 staleTime (backend Redis TTL 정합) */
function getStaleTime(interval: string): number {
  return isIntradayInterval(interval) ? 60_000 : 300_000
}

export interface UseIndicatorsOptions {
  code: string
  name: string
  interval: CandleInterval
  /** key=value 쉼표 구분 (예: "period=14") */
  params?: string
  from?: string
  to?: string
  adjusted?: boolean
  /** false이면 쿼리 비활성 */
  enabled?: boolean
}

export interface UseIndicatorsResult {
  data: IndicatorResultDto | undefined
  isLoading: boolean
  isError: boolean
  errorMessage: string | null
  isFetching: boolean
}

/**
 * 단일 지표 시계열 조회 hook.
 * enabled=false (기본 true) 또는 code/name 빈 문자열이면 쿼리 비활성.
 */
export function useIndicators({
  code,
  name,
  interval,
  params,
  from,
  to,
  adjusted = false,
  enabled = true,
}: UseIndicatorsOptions): UseIndicatorsResult {
  const isEnabled = enabled && Boolean(code) && Boolean(name)

  const query = useQuery({
    queryKey: ['indicators', code, name, interval, params ?? '', from ?? '', to ?? ''] as const,
    queryFn: () =>
      indicatorApi.getIndicator({ code, name, interval, params, from, to, adjusted }),
    enabled: isEnabled,
    staleTime: getStaleTime(interval),
    gcTime: 120_000, // 2분 캐시 유지
    refetchOnWindowFocus: false,
    refetchIntervalInBackground: false,
    retry: 1,
  })

  // 에러 메시지 추출
  let errorMessage: string | null = null
  if (query.isError && query.error) {
    const err = query.error as { response?: { data?: { error?: string; message?: string } } }
    errorMessage =
      err.response?.data?.error ??
      err.response?.data?.message ??
      '지표 데이터를 가져올 수 없습니다.'
  }

  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    errorMessage,
    isFetching: query.isFetching,
  }
}
