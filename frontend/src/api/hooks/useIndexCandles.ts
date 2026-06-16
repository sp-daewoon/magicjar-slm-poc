/**
 * useIndexCandles — market_index_snapshot 기반 지수 캔들 series.
 *
 * 시장 추세 화면은 기본적으로 KST 거래일 기준 일봉만 표시한다.
 */
import { useQuery } from '@tanstack/react-query'
import { fetchIndexCandles } from '../market'

interface UseIndexCandlesOptions {
  code: string
  interval?: '1m' | '1d'
  limit?: number
  enabled?: boolean
}

export function indexCandlesQueryKey(
  code: string,
  interval: '1m' | '1d' = '1d',
  limit = 260,
) {
  return ['indexCandles', code, interval, limit] as const
}

const POLLING_INTERVAL_MS = 30_000

export function useIndexCandles({
  code,
  interval = '1d',
  limit = 260,
  enabled = true,
}: UseIndexCandlesOptions) {
  const query = useQuery({
    queryKey: indexCandlesQueryKey(code, interval, limit),
    queryFn: () => fetchIndexCandles(code, { interval, limit }),
    enabled: enabled && code.length > 0,
    refetchInterval: POLLING_INTERVAL_MS,
    refetchOnWindowFocus: false,
    staleTime: 5_000,
  })

  return {
    candles: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  }
}
