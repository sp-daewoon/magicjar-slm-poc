/**
 * usePerformanceSummary — GET /api/performance/summary query
 *
 * ADR 0072 §7 정합 — staleTime 5min (performance summary 캐시 부담 적정)
 * 마운트 폴링 0 (refetchInterval 0)
 * mock 합성 0 — userId null 시 enabled=false
 */
import { useQuery } from '@tanstack/react-query'
import { performanceApi, type PerformancePeriod } from '../api/performanceApi'
import type { AccountType } from '../../../api/account'

interface UsePerformanceSummaryParams {
  userId: number | null
  period: PerformancePeriod
  periodStart?: string
  periodEnd?: string
  mode?: AccountType
}

export function usePerformanceSummary({
  userId,
  period,
  periodStart,
  periodEnd,
  mode,
}: UsePerformanceSummaryParams) {
  return useQuery({
    queryKey: ['performance', 'summary', { userId, period, periodStart, periodEnd, mode }],
    queryFn: () => performanceApi.getSummary(userId!, period, periodStart, periodEnd, mode),
    enabled: userId !== null,
    staleTime: 5 * 60_000,
    refetchInterval: false,
  })
}
