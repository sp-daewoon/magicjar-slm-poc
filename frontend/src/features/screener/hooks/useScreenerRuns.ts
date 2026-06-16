/**
 * useScreenerRuns — GET /api/screener/runs 쿼리
 *
 * 옵션 영속(MAGICJAR_SCREENER_PERSIST=true) 환경에서만 활성.
 * 본 사이클은 이번 PR 범위 외 (후속 사이클 useScreenerRuns 사용처 합류 예정).
 * enabled=false 기본값으로 비활성 상태 — 추후 사용처에서 enabled 활성화.
 */
import { useQuery } from '@tanstack/react-query'
import { screenerApi } from '../api/screenerApi'

export function useScreenerRuns(userId: number, page = 0, size = 20, enabled = false) {
  return useQuery({
    queryKey: ['screener', 'runs', userId, page, size] as const,
    queryFn: () => screenerApi.listRuns(userId, page, size),
    enabled,
    staleTime: 60 * 1000,
  })
}
