/**
 * RiskUsage TanStack Query hook — 리스크 한도 사용량 게이지
 *
 * useRiskUsage(userId, strategyId)
 *   - GET /api/risk/usage?userId=&strategyId=
 *   - 10초 polling (refetchInterval) — 노출·손익·동시보유·빈도는 장중 빠르게 변함
 *   - background tab 에서는 polling pause (refetchIntervalInBackground=false)
 *   - userId 없으면 비활성 (feedback_no_mock_data 정합)
 *
 * useInvalidateRiskUsage()
 *   - 설정 "저장" 직후 호출 → 분모(한도)가 새 값으로 즉시 갱신되도록 강제 재조회.
 *     (사용자 핵심 요구: 한도를 바꾸면 게이지의 분모가 바로 새 한도로 바뀌어야 함)
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { riskUsageApi, type RiskUsageDto } from '../riskUsage'

/** 10초 polling — 사용량은 장중 자주 변함 */
export const RISK_USAGE_POLL_MS = 10 * 1000

const RISK_USAGE_KEY = (userId: number | string, strategyId: number | string | null | undefined) =>
  ['risk-usage', userId, strategyId ?? 'none'] as const

/** strategyId 와 무관하게 해당 userId 의 모든 usage 쿼리 매칭용 prefix */
const RISK_USAGE_PREFIX = (userId: number | string) => ['risk-usage', userId] as const

export function useRiskUsage(
  userId: number | null | undefined,
  strategyId?: number | null,
) {
  return useQuery<RiskUsageDto>({
    queryKey: RISK_USAGE_KEY(userId ?? '', strategyId),
    queryFn: () => riskUsageApi.getUsage(userId!, strategyId),
    enabled: userId !== null && userId !== undefined,
    refetchInterval: RISK_USAGE_POLL_MS,
    refetchIntervalInBackground: false,
    // 사용량은 짧게 stale 처리 — 저장 invalidate 시 즉시 refetch
    staleTime: 0,
  })
}

/**
 * 설정 저장/초기화 직후 사용량 강제 재조회 헬퍼.
 * 반환 콜백 호출 시 해당 userId 의 모든 usage 쿼리(전략 무관)를 무효화 → 즉시 refetch.
 */
export function useInvalidateRiskUsage(): (userId: number) => void {
  const queryClient = useQueryClient()
  return useCallback(
    (userId: number) => {
      void queryClient.invalidateQueries({ queryKey: RISK_USAGE_PREFIX(userId) })
    },
    [queryClient],
  )
}
