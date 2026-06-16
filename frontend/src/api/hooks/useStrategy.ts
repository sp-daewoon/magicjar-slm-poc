/**
 * Strategy TanStack Query hooks
 *
 * hooks:
 *   useStrategies(userId?) — useQuery
 *   useStrategy(id)        — useQuery
 *   useStrategyValidate()  — useMutation
 *   useStrategyEvaluate()  — useMutation
 *   useCreateStrategy()    — useMutation + invalidate list
 *   useEnableStrategy()    — useMutation
 *   useDeleteStrategy()    — useMutation + invalidate list
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  strategyApi,
  type CreateStrategyRequest,
  type CreateStrategyResponse,
  type EvaluateRequest,
  type EvaluateResponse,
  type StrategyComplexityDto,
  type StrategyDto,
  type ValidateRequest,
} from '../strategy'

const STRATEGY_LIST_KEY = (userId?: number) => ['strategy', 'list', userId] as const
const STRATEGY_KEY = (id: string) => ['strategy', id] as const

/**
 * 사용자별 전략 목록 조회
 * staleTime 30s — ADR 0089 Phase 2 userId number
 */
export function useStrategies(userId?: number) {
  return useQuery<StrategyDto[]>({
    queryKey: STRATEGY_LIST_KEY(userId),
    queryFn: () => strategyApi.list(userId),
    staleTime: 30 * 1000,
  })
}

/**
 * 단일 전략 조회
 */
export function useStrategy(id: string | undefined) {
  return useQuery<StrategyDto>({
    queryKey: STRATEGY_KEY(id ?? ''),
    queryFn: () => strategyApi.get(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}

/**
 * DSL 검증 mutation
 * POST /api/strategy/validate
 */
export function useStrategyValidate() {
  return useMutation<StrategyComplexityDto, Error, ValidateRequest>({
    mutationFn: (req) => strategyApi.validate(req),
  })
}

export interface EvaluateParams {
  strategyId: string
  req: EvaluateRequest
}

/**
 * 전략 평가 mutation
 * POST /api/strategy/{id}/evaluate
 */
export function useStrategyEvaluate() {
  return useMutation<EvaluateResponse, Error, EvaluateParams>({
    mutationFn: ({ strategyId, req }) => strategyApi.evaluate(strategyId, req),
  })
}

/**
 * 전략 저장 mutation
 * POST /api/strategy → invalidate strategy list
 */
export function useCreateStrategy(userId?: number) {
  const queryClient = useQueryClient()
  return useMutation<CreateStrategyResponse, Error, CreateStrategyRequest>({
    mutationFn: (req) => strategyApi.create(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: STRATEGY_LIST_KEY(userId) })
    },
  })
}

/**
 * 전략 활성화 mutation
 */
export function useEnableStrategy() {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => strategyApi.enable(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['strategy'] })
    },
  })
}

/**
 * 전략 삭제 mutation
 */
export function useDeleteStrategy(userId?: number) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => strategyApi.delete(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: STRATEGY_LIST_KEY(userId) })
    },
  })
}
