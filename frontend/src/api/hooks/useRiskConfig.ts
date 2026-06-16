/**
 * RiskConfig TanStack Query hooks (T-RISK Phase 3 — ADR 0067)
 *
 * hooks:
 *   useRiskConfig(userId)         — useQuery (단건)
 *   useUpdateRiskConfig(userId)   — useMutation (PUT)
 *   useResetRiskConfig(userId)    — useMutation (POST /reset)
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { riskApi, type RiskConfigDto, type RiskConfigUpdateRequest } from '../risk'

const RISK_CONFIG_KEY = (userId: number | string) => ['risk-config', userId] as const

/**
 * 리스크 설정 조회 hook
 * staleTime=30s — default seed 포함
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * ADR 0089 Phase 2 — userId number 통일
 */
export function useRiskConfig(userId: number | null | undefined) {
  return useQuery<RiskConfigDto>({
    queryKey: RISK_CONFIG_KEY(userId ?? ''),
    queryFn: () => riskApi.getConfig(userId!),
    enabled: userId !== null && userId !== undefined,
    staleTime: 30 * 1000,
  })
}

/**
 * 리스크 설정 갱신 mutation
 * 성공 시 쿼리 invalidate → 즉시 재조회
 */
export function useUpdateRiskConfig(userId: number | null | undefined) {
  const queryClient = useQueryClient()
  return useMutation<RiskConfigDto, unknown, RiskConfigUpdateRequest>({
    mutationFn: (req) => riskApi.updateConfig(userId!, req),
    onSuccess: () => {
      if (userId !== null && userId !== undefined) {
        void queryClient.invalidateQueries({ queryKey: RISK_CONFIG_KEY(userId) })
      }
    },
  })
}

/**
 * 리스크 설정 초기화 mutation (default seed)
 * 성공 시 쿼리 invalidate → 즉시 재조회
 */
export function useResetRiskConfig(userId: number | null | undefined) {
  const queryClient = useQueryClient()
  return useMutation<RiskConfigDto, unknown, void>({
    mutationFn: () => riskApi.resetConfig(userId!),
    onSuccess: () => {
      if (userId !== null && userId !== undefined) {
        void queryClient.invalidateQueries({ queryKey: RISK_CONFIG_KEY(userId) })
      }
    },
  })
}
