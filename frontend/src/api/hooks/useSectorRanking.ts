/**
 * useSectorRanking — todayTop 5 / longTop 5 + STOMP rank push merge
 * (T-MARKET R2 frontend §2-1 — MarketTrendPage 우측 패널)
 *
 * Backend 1:1:
 * - GET /api/market/sectors/ranking?periods=&rsPeriod= → SectorRankingResponse
 * - STOMP /topic/market.sector.rank → SectorRankingResponse (5분 cron 캐시 갱신 push)
 *
 * race-safe merge — STOMP 메시지 자체가 풀 응답이므로 setQueryData 직접 갱신.
 * periods·rsPeriod 변경 시 hook 재호출 → queryKey 갱신 → 자동 refetch.
 */
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSectorRanking, type SectorRankingResponse } from '../market'
import { useStompTopic } from '../../lib/stomp'
import { marketSectorRank } from '../stompDestinations'

interface UseSectorRankingOptions {
  /** 명시 시 user pref override. 생략 시 backend echo user pref. */
  periods?: number[]
  /** 명시 시 user pref override. */
  rsPeriod?: number
  /** false 시 비활성 (setup 미완 등). */
  enabled?: boolean
}

/** queryKey 빌더 — periods·rsPeriod override 시 캐시 분리. */
export function sectorRankingQueryKey(periods?: number[], rsPeriod?: number) {
  return ['sectorRanking', periods?.join(',') ?? 'pref', rsPeriod ?? 'pref'] as const
}

const POLLING_INTERVAL_MS = 60_000

export function useSectorRanking(opts: UseSectorRankingOptions = {}) {
  const { periods, rsPeriod, enabled = true } = opts
  const queryClient = useQueryClient()
  const key = sectorRankingQueryKey(periods, rsPeriod)

  const query = useQuery({
    queryKey: key,
    queryFn: () => fetchSectorRanking({ periods, rsPeriod }),
    enabled,
    refetchInterval: POLLING_INTERVAL_MS,
    refetchOnWindowFocus: false,
    staleTime: 30_000,
  })

  // STOMP push — 5분 cron 캐시 갱신 시 fanout. payload 자체가 풀 응답.
  // 단, periods/rsPeriod override 사용자 view (custom queryKey)는 backend가
  // user pref 기반 push 만 발행하므로 일치 안 함 → 'pref' echo view 만 push 적용.
  // 안전한 선택: pref echo view 인 경우만 push merge (override view는 polling 만으로 신선도 유지).
  const isPrefView = periods === undefined && rsPeriod === undefined
  useStompTopic<SectorRankingResponse>(
    isPrefView ? marketSectorRank : '',
    (payload) => {
      queryClient.setQueryData(key, payload)
    },
    enabled && isPrefView,
  )

  return {
    ranking: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  }
}
