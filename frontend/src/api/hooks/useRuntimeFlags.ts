/**
 * useRuntimeFlags — RuntimeFlags 조회 hook (P2-2 frontend)
 *
 * GET /api/runtime/flags
 *  - 60s refetchInterval (다중 탭 동기화 — backend env 변경은 재기동 동반이므로 빈번 polling 불필요)
 *  - staleTime=30s
 *  - 실패 시 isError → indicator placeholder (mock 합성 0 LOCK)
 *
 * AppHeader indicator + dev console에서 사용.
 */
import { useQuery } from '@tanstack/react-query'
import { runtimeApi, type RuntimeFlagsDto } from '../runtime'

export const RUNTIME_FLAGS_QUERY_KEY = ['runtime', 'flags'] as const

export function useRuntimeFlags(userId?: string | number | null) {
  return useQuery<RuntimeFlagsDto, Error>({
    queryKey: [...RUNTIME_FLAGS_QUERY_KEY, userId ?? null],
    queryFn: () => runtimeApi.getFlags(userId),
    staleTime: 30_000,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
    retry: 1,
  })
}
