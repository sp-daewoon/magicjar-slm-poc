/**
 * 페이퍼 트레이딩 React Query hooks (ADR 0122 R3)
 *
 * - useDaytradePaperPromote: 발굴 setup → 페이퍼 승격 mutation
 * - useDaytradePaperStrategies: 활성 페이퍼 전략 목록 query
 * - useDaytradePaperPositions: 페이퍼 포지션 query
 * - useDaytradePaperStop: 페이퍼 전략 중단 mutation
 *
 * 승급/중단 성공 시 strategies·positions query invalidate (자동 새로고침).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  daytradePaperApi,
  type PaperPromoteRequest,
  type PaperPositionDto,
  type PaperStrategyDto,
} from '../api/daytradePaperApi'

const STRATEGIES_KEY = (userId: number | null) => ['daytrade-paper', 'strategies', userId] as const
const POSITIONS_KEY = (userId: number | null) => ['daytrade-paper', 'positions', userId] as const

/** 발굴 setup → MOCK 페이퍼 전략 일괄 승격 */
export function useDaytradePaperPromote() {
  const qc = useQueryClient()
  return useMutation<PaperStrategyDto, Error, PaperPromoteRequest>({
    mutationFn: (req) => daytradePaperApi.promote(req),
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: STRATEGIES_KEY(variables.userId) })
      qc.invalidateQueries({ queryKey: POSITIONS_KEY(variables.userId) })
    },
  })
}

/** 활성 페이퍼 전략 목록 (userId 있을 때만 enabled) */
export function useDaytradePaperStrategies(userId: number | null) {
  return useQuery<PaperStrategyDto[], Error>({
    queryKey: STRATEGIES_KEY(userId),
    queryFn: () => daytradePaperApi.listStrategies(userId as number),
    enabled: userId !== null,
    // 페이퍼 포지션은 장중 갱신되므로 주기 새로고침 (실시간 권위는 backend WS·체결)
    refetchInterval: 15_000,
  })
}

/** 페이퍼 포지션 목록 (userId 있을 때만 enabled) */
export function useDaytradePaperPositions(userId: number | null) {
  return useQuery<PaperPositionDto[], Error>({
    queryKey: POSITIONS_KEY(userId),
    queryFn: () => daytradePaperApi.listPositions(userId as number),
    enabled: userId !== null,
    refetchInterval: 15_000,
  })
}

/** 페이퍼 전략 중단 (soft delete) */
export function useDaytradePaperStop(userId: number | null) {
  const qc = useQueryClient()
  return useMutation<void, Error, number>({
    mutationFn: (strategyId) => daytradePaperApi.stop(strategyId, userId as number),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: STRATEGIES_KEY(userId) })
      qc.invalidateQueries({ queryKey: POSITIONS_KEY(userId) })
    },
  })
}
