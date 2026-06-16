/**
 * Watchlist TanStack Query hooks
 *
 * spec §3-2 C 정합:
 * - useWatchlist() — useQuery (queryKey ['watchlist'], staleTime 30s)
 * - useAddWatchlist() — useMutation + invalidateQueries
 * - useRemoveWatchlist() — useMutation + invalidateQueries
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { watchlistApi, type WatchlistAddRequest, type WatchlistEntryDto } from '../watchlist'

const WATCHLIST_QUERY_KEY = ['watchlist'] as const

/**
 * 관심종목 목록 조회 hook
 * staleTime=30s — 최근 캐시 유효 (30초 이내 재진입 시 네트워크 요청 0)
 */
export function useWatchlist() {
  return useQuery<WatchlistEntryDto[]>({
    queryKey: WATCHLIST_QUERY_KEY,
    queryFn: () => watchlistApi.list(),
    staleTime: 30 * 1000,
  })
}

/**
 * 관심종목 등록 mutation
 * 성공 시 목록 쿼리 invalidate → 즉시 재조회
 */
export function useAddWatchlist() {
  const queryClient = useQueryClient()
  return useMutation<WatchlistEntryDto, unknown, WatchlistAddRequest>({
    mutationFn: (req) => watchlistApi.add(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WATCHLIST_QUERY_KEY })
    },
  })
}

/**
 * 관심종목 삭제 mutation
 * 성공 시 목록 쿼리 invalidate → 즉시 재조회
 */
export function useRemoveWatchlist() {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (code) => watchlistApi.remove(code),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WATCHLIST_QUERY_KEY })
    },
  })
}
