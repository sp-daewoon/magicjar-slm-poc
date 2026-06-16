/**
 * useStrategyPosition — 전략별 포지션 조회 hook (ADR 0110 · 분할매수 R3-a)
 *
 * GET /api/strategies/{id}/position
 * ADR 0009 TanStack Query 패턴 정합.
 */
import { useQuery } from '@tanstack/react-query'
import { strategyPositionApi, type StrategyPositionDto } from '../strategyPosition'

const STRATEGY_POSITION_KEY = (id: string) => ['strategy-position', id] as const

/**
 * 전략별 포지션 조회. strategyId 없으면 비활성.
 */
export function useStrategyPosition(strategyId: string | undefined, enabled = true) {
  return useQuery<StrategyPositionDto[]>({
    queryKey: STRATEGY_POSITION_KEY(strategyId ?? ''),
    queryFn: () => strategyPositionApi.get(strategyId!),
    enabled: !!strategyId && enabled,
    staleTime: 15 * 1000,
  })
}
