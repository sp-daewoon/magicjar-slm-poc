/**
 * SigStrategy TanStack Query hooks (T-SIG Phase 3 — ADR 0066)
 *
 * ADR 0066 /api/strategies (전략 목록·자동매매 운영 endpoint)
 *
 * hooks:
 *   useSigStrategies(page, size) — useInfiniteQuery
 *   useSigStrategy(id)           — useQuery
 *   useCreateSigStrategy()       — useMutation
 *   useUpdateSigStrategy()       — useMutation
 *   useDeleteSigStrategy()       — useMutation
 *   useToggleSigStrategyStatus() — useMutation
 *
 * ADR 0009 TanStack Query 패턴 정합
 */
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  sigStrategyApi,
  type CreateSigStrategyRequest,
  type SigStrategyDto,
  type SigStrategyPage,
  type StrategyStatus,
  type UpdateSigStrategyRequest,
} from '../strategy'

const SIG_STRATEGY_LIST_KEY = ['sig-strategies', 'list'] as const
const SIG_STRATEGY_KEY = (id: string) => ['sig-strategies', id] as const

/**
 * 전략 목록 (무한 스크롤)
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 */
export function useSigStrategies(userId: number | null | undefined, size = 20) {
  return useInfiniteQuery<SigStrategyPage, Error>({
    queryKey: [...SIG_STRATEGY_LIST_KEY, userId],
    queryFn: ({ pageParam }) =>
      sigStrategyApi.list(userId!, pageParam as number, size),
    initialPageParam: 0 as number,
    getNextPageParam: (lastPage) =>
      lastPage.last ? undefined : lastPage.page + 1,
    enabled: userId !== null && userId !== undefined,
    staleTime: 30 * 1000,
  })
}

/**
 * 전략 단건 조회
 */
export function useSigStrategy(id: string | undefined) {
  return useQuery<SigStrategyDto>({
    queryKey: SIG_STRATEGY_KEY(id ?? ''),
    queryFn: () => sigStrategyApi.get(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}

/**
 * 전략 생성 mutation
 */
export function useCreateSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, CreateSigStrategyRequest>({
    mutationFn: (req) => sigStrategyApi.create(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 수정 mutation
 */
export function useUpdateSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, { id: string; req: UpdateSigStrategyRequest }>({
    mutationFn: ({ id, req }) => sigStrategyApi.update(id, req),
    onSuccess: (data) => {
      queryClient.setQueryData(SIG_STRATEGY_KEY(data.id), data)
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 삭제 mutation
 */
export function useDeleteSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => sigStrategyApi.delete(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 상태 토글 mutation
 */
export function useToggleSigStrategyStatus() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, { id: string; status: StrategyStatus }>({
    mutationFn: ({ id, status }) => sigStrategyApi.toggleStatus(id, status),
    onSuccess: (data) => {
      queryClient.setQueryData(SIG_STRATEGY_KEY(data.id), data)
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}
