/**
 * useMarketIndex — 4 지수 카드 snapshot polling 30s + STOMP race-safe merge
 * (T-MARKET R2 frontend §2-1 — DashboardPage MarketIndexStrip + MarketTrendPage)
 *
 * Backend 1:1:
 * - GET /api/market/index/snapshot → IndexSnapshotResponse (4 row)
 * - STOMP /topic/market.index.{code} → IndexTrendResponse (1분 cron 결과 push)
 *
 * race-safe merge — STOMP 메시지 도달 시 해당 code row만 갱신 (다른 row 보존).
 * mock 합성 0 — backend 빈 list → '데이터 없음' UI 표시 의무.
 */
import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  DEFAULT_INDEX_CODES,
  fetchIndexSnapshot,
  type IndexSnapshotResponse,
  type IndexTrendResponse,
} from '../market'
import { useStompTopic } from '../../lib/stomp'
import { marketIndex } from '../stompDestinations'

export const MARKET_INDEX_QUERY_KEY = ['marketIndex', 'snapshot'] as const

/** 30s polling fallback — STOMP 끊김·tick miss 시. */
const POLLING_INTERVAL_MS = 30_000

export function useMarketIndex(opts?: { enabled?: boolean }) {
  const enabled = opts?.enabled ?? true
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: MARKET_INDEX_QUERY_KEY,
    queryFn: fetchIndexSnapshot,
    enabled,
    refetchInterval: POLLING_INTERVAL_MS,
    refetchOnWindowFocus: false,
    // 0건일 때도 graceful — staleTime 짧게
    staleTime: 5_000,
  })

  const mergeIndexPayload = useCallback(
    (payload: IndexTrendResponse) => {
      queryClient.setQueryData<IndexSnapshotResponse>(MARKET_INDEX_QUERY_KEY, (prev) => {
        if (!prev) return { indexes: [payload] }
        const next = prev.indexes.map((row) => (row.code === payload.code ? payload : row))
        // 신규 code (snapshot 0건 + push 도달) 시 append
        if (!prev.indexes.some((row) => row.code === payload.code)) {
          return { indexes: [...prev.indexes, payload] }
        }
        return { indexes: next }
      })
    },
    [queryClient],
  )

  // 4 default index 구독 — race-safe merge (다른 row 보존).
  // hook은 항상 동일 4 topic 을 같은 순서로 구독한다.
  useStompTopic<IndexTrendResponse>(marketIndex(DEFAULT_INDEX_CODES[0]), mergeIndexPayload, enabled)
  useStompTopic<IndexTrendResponse>(marketIndex(DEFAULT_INDEX_CODES[1]), mergeIndexPayload, enabled)
  useStompTopic<IndexTrendResponse>(marketIndex(DEFAULT_INDEX_CODES[2]), mergeIndexPayload, enabled)
  useStompTopic<IndexTrendResponse>(marketIndex(DEFAULT_INDEX_CODES[3]), mergeIndexPayload, enabled)

  return {
    snapshot: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  }
}
