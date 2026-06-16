/**
 * RiskEvent TanStack Query hooks (T-RISK Phase 3 — ADR 0067)
 *
 * hooks:
 *   useRiskEvents(userId, params)  — useInfiniteQuery (무한 스크롤)
 *   useRiskEvent(id, userId)       — useQuery (단건)
 *
 * ADR 0009 TanStack Query 패턴 정합 (useSignal 패턴 준용)
 * 룰 5: LLM provider import 0건
 */
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { riskApi, type RiskEventDto, type RiskEventListParams, type RiskEventPage } from '../risk'

const RISK_EVENT_LIST_KEY = (params: RiskEventListParams) =>
  ['risk-events', 'list', params] as const

const RISK_EVENT_KEY = (id: string) => ['risk-events', id] as const

/**
 * 리스크 이벤트 목록 (무한 스크롤)
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * ADR 0089 Phase 2 — userId number
 */
export function useRiskEvents(
  userId: number | null | undefined,
  params: Omit<RiskEventListParams, 'page' | 'userId'> = {},
  size = 20,
) {
  return useInfiniteQuery<RiskEventPage, Error>({
    queryKey: RISK_EVENT_LIST_KEY({ ...params, userId: userId ?? undefined, size }),
    queryFn: ({ pageParam }) =>
      riskApi.listEvents({ ...params, userId: userId!, size, page: pageParam as number }),
    initialPageParam: 0 as number,
    getNextPageParam: (lastPage) =>
      lastPage.last ? undefined : lastPage.page + 1,
    enabled: userId !== null && userId !== undefined,
    staleTime: 15 * 1000,
  })
}

/**
 * 리스크 이벤트 단건 조회
 */
export function useRiskEvent(id: string | undefined, userId: number | null | undefined) {
  return useQuery<RiskEventDto>({
    queryKey: RISK_EVENT_KEY(id ?? ''),
    queryFn: () => riskApi.getEvent(id!, userId!),
    enabled: !!id && userId !== null && userId !== undefined,
    staleTime: 60 * 1000,
  })
}
