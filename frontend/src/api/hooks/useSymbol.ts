/**
 * useSymbol — 종목 메타·현재가 TanStack Query hook
 *
 * spec §6-3 LOCK:
 * - queryKey: ['symbol', code]
 * - HTTP는 최초 스냅샷 + 조건부 fallback 전용
 * - STOMP 정상 수신 중에는 refetchInterval=false
 *
 * ADR 0014: stale 배지 — data.stale 확인 후 UI 표시
 */
import { useQuery } from '@tanstack/react-query'
import { symbolApi } from '../symbol'

export const SYMBOL_FALLBACK_POLLING_INTERVAL_MS = 30_000
export const SYMBOL_FALLBACK_STALE_TIME_MS = 25_000

export interface UseSymbolOptions {
  /** false면 최초 스냅샷 조회도 비활성화 */
  enabled?: boolean
  /**
   * STOMP 미연결·tick 지연 등 fallback 상황에서만 true.
   * true일 때만 HTTP polling을 수행한다.
   */
  pollingEnabled?: boolean
  /** fallback polling 주기. 기본 30초. */
  refetchInterval?: number
}

export function useSymbol(code: string, options: UseSymbolOptions = {}) {
  const {
    enabled = true,
    pollingEnabled = false,
    refetchInterval = SYMBOL_FALLBACK_POLLING_INTERVAL_MS,
  } = options

  return useQuery({
    queryKey: ['symbol', code] as const,
    queryFn: () => symbolApi.getSymbol(code),
    refetchInterval: pollingEnabled ? refetchInterval : false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    staleTime: SYMBOL_FALLBACK_STALE_TIME_MS,
    enabled: Boolean(code) && enabled,
    retry: 2,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10000),
  })
}
