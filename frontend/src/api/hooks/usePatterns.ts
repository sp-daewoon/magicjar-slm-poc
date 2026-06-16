/**
 * usePatterns — 캔들스틱 패턴 TanStack Query hook
 *
 * PR-38 spec R3 REST endpoint LOCK:
 *   GET /api/patterns?code=...&interval=...&patterns=...&from=...&to=...
 *
 * queryKey: ['patterns', code, interval, patterns, from, to]
 *
 * staleTime (backend Redis TTL 정합):
 *   - 분봉: 5분 (300,000ms) — backend 5분 TTL LOCK
 *   - 일봉: 1일 (86,400,000ms) — backend 1일 TTL LOCK
 *
 * 에러 핸들링:
 *   - 400 종목코드 형식 → "종목코드 형식이 올바르지 않습니다" 안내
 *   - 401 onboarding 미완 → "설정에서 KIS 계정을 등록해 주세요" SettingsModal 유도
 *   - 503 → "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요" 안내
 *
 * 룰 5 (W2): LLM provider import 0건
 */
import { useQuery } from '@tanstack/react-query'
import { patternApi, type PatternResponseDto } from '../patterns'
import type { CandleInterval } from '../symbol'

/** 인터벌이 분봉인지 여부 */
function isIntradayInterval(interval: string): boolean {
  return ['1m', '3m', '5m', '15m', '30m', '1h', '90m', '4h', '12h'].includes(interval)
}

/**
 * 인터벌별 staleTime (backend Redis TTL LOCK)
 * - 분봉: 5분 (300,000ms)
 * - 일봉: 1일 (86,400,000ms)
 */
function getStaleTime(interval: string): number {
  return isIntradayInterval(interval) ? 300_000 : 86_400_000
}

/** HTTP 에러 코드별 사용자 안내 메시지 */
function resolveErrorMessage(error: unknown): string {
  const err = error as {
    response?: { status?: number; data?: { error?: string; message?: string } }
  }
  const status = err.response?.status
  if (status === 400) return '종목코드 형식이 올바르지 않습니다.'
  if (status === 401) return '설정에서 KIS 계정을 등록해 주세요.'
  if (status === 503) return '서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  return (
    err.response?.data?.message ??
    err.response?.data?.error ??
    '패턴 데이터를 가져올 수 없습니다.'
  )
}

export interface UsePatternsOptions {
  code: string
  interval: CandleInterval
  /** 활성화할 패턴 id Set */
  patternIds: ReadonlySet<string>
  from?: string
  to?: string
  /** false이면 쿼리 비활성 */
  enabled?: boolean
}

export interface UsePatternsResult {
  data: PatternResponseDto | undefined
  isLoading: boolean
  isError: boolean
  isFetching: boolean
  errorMessage: string | null
}

/**
 * 캔들스틱 패턴 인식 결과 조회 hook.
 *
 * patternIds가 비어있으면 쿼리 비활성.
 * enabled=false 또는 code 빈 문자열이면 쿼리 비활성.
 */
export function usePatterns({
  code,
  interval,
  patternIds,
  from,
  to,
  enabled = true,
}: UsePatternsOptions): UsePatternsResult {
  const patternsStr = [...patternIds].sort().join(',')
  const isEnabled = enabled && Boolean(code) && patternIds.size > 0

  const query = useQuery({
    queryKey: ['patterns', code, interval, patternsStr, from ?? '', to ?? ''] as const,
    queryFn: () =>
      patternApi.getPatterns({
        code,
        interval,
        patterns: patternsStr || undefined,
        from,
        to,
      }),
    enabled: isEnabled,
    staleTime: getStaleTime(interval),
    gcTime: 120_000, // 2분 캐시 유지
    refetchOnWindowFocus: false,
    refetchIntervalInBackground: false,
    retry: 1,
  })

  let errorMessage: string | null = null
  if (query.isError && query.error) {
    errorMessage = resolveErrorMessage(query.error)
  }

  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    isFetching: query.isFetching,
    errorMessage,
  }
}
