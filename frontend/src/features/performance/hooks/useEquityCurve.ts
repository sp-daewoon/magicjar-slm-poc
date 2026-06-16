/**
 * useEquityCurve — GET /api/performance/equity-curve query
 *
 * ADR 0072 §6-5 정합
 * from·to 변경 시 자동 refetch (queryKey 연동)
 */
import { useQuery } from '@tanstack/react-query'
import { performanceApi } from '../api/performanceApi'
import type { AccountType } from '../../../api/account'

interface UseEquityCurveParams {
  userId: number | null
  from?: string
  to?: string
  mode?: AccountType
  enabled?: boolean
}

export function useEquityCurve({ userId, from, to, mode, enabled = true }: UseEquityCurveParams) {
  return useQuery({
    queryKey: ['performance', 'equity-curve', { userId, from, to, mode }],
    queryFn: () => performanceApi.getEquityCurve(userId!, from, to, mode),
    enabled: enabled && userId !== null,
    staleTime: 5 * 60_000,
    refetchInterval: false,
  })
}
