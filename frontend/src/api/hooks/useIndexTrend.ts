/**
 * useIndexTrend — 단일 지수 추세 상세 (MA·crossSignal·trendLabel·changeRate)
 * (T-MARKET R2 frontend §2-1 — MarketTrendPage 중앙 패널 + 차트 데이터)
 *
 * Backend 1:1:
 * - GET /api/market/index/{code}/trend?periods=... → IndexTrendResponse
 * - STOMP /topic/market.index.{code} → IndexTrendResponse push
 *
 * periods override: 생략 시 backend가 user pref echo (응답 periodsUsed로 확인).
 * 명시 시 일회성 override (URL share · 테스트용).
 */
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchIndexTrend, type IndexTrendResponse } from '../market'
import { useStompTopic } from '../../lib/stomp'
import { marketIndex } from '../stompDestinations'

interface UseIndexTrendOptions {
  code: string
  /** 명시 시 user pref override. 생략 시 backend echo user pref. */
  periods?: number[]
  enabled?: boolean
}

export function indexTrendQueryKey(code: string, periods?: number[]) {
  return ['indexTrend', code, periods?.join(',') ?? 'pref'] as const
}

const POLLING_INTERVAL_MS = 60_000

export function useIndexTrend({ code, periods, enabled = true }: UseIndexTrendOptions) {
  const queryClient = useQueryClient()
  const key = indexTrendQueryKey(code, periods)
  const isPrefView = periods === undefined

  const query = useQuery({
    queryKey: key,
    queryFn: () => fetchIndexTrend(code, periods),
    enabled: enabled && code.length > 0,
    refetchInterval: POLLING_INTERVAL_MS,
    refetchOnWindowFocus: false,
    staleTime: 5_000,
  })

  // STOMP — pref view 일 때만 push merge (override view는 polling으로 신선도 유지).
  useStompTopic<IndexTrendResponse>(
    isPrefView && code ? marketIndex(code) : '',
    (payload) => {
      // 다른 code의 push가 잘못 도달했을 때 가드 — race-safe.
      if (payload.code !== code) return
      queryClient.setQueryData(key, payload)
    },
    enabled && isPrefView && code.length > 0,
  )

  return {
    trend: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  }
}
