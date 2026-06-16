/**
 * usePerformanceTrades — GET /api/performance/trades query
 *
 * ADR 0072 §6 정합 — 총 손익 상세 근거 조회.
 */
import { useQuery } from '@tanstack/react-query'
import { performanceApi, type PerformancePeriod } from '../api/performanceApi'
import type { AccountType } from '../../../api/account'

interface UsePerformanceTradesParams {
  userId: number | null
  period: PerformancePeriod
  periodStart?: string
  periodEnd?: string
  mode?: AccountType
  enabled?: boolean
}

export function usePerformanceTrades({
  userId,
  period,
  periodStart,
  periodEnd,
  mode,
  enabled = true,
}: UsePerformanceTradesParams) {
  return useQuery({
    queryKey: ['performance', 'trades', { userId, period, periodStart, periodEnd, mode }],
    queryFn: () => performanceApi.getTrades(userId!, period, periodStart, periodEnd, mode),
    enabled: enabled && userId !== null,
    staleTime: 5 * 60_000,
    refetchInterval: false,
  })
}
