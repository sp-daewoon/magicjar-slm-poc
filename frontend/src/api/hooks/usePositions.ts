/**
 * Position TanStack Query hooks (T-RISK Phase 3 — ADR 0067)
 *
 * hooks:
 *   usePositions(params)  — useQuery (목록)
 *   usePosition(id)       — useQuery (단건)
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useQuery } from '@tanstack/react-query'
import { positionApi, type PositionDto, type PositionListParams } from '../position'

const POSITIONS_KEY = (params: PositionListParams) => ['positions', 'list', params] as const
const POSITION_KEY = (id: string) => ['positions', id] as const

/**
 * 보유 포지션 목록 hook
 * staleTime=15s — 실시간성 반영
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 */
export function usePositions(params: PositionListParams = {}) {
  return useQuery<PositionDto[]>({
    queryKey: POSITIONS_KEY(params),
    queryFn: () => positionApi.list(params),
    enabled: params.userId !== undefined && params.userId !== null,
    staleTime: 15 * 1000,
  })
}

/**
 * 포지션 단건 조회 (ADR 0089 Phase 2 — userId number)
 */
export function usePosition(id: string | undefined, userId: number | undefined) {
  return useQuery<PositionDto>({
    queryKey: POSITION_KEY(id ?? ''),
    queryFn: () => positionApi.get(id!, userId!),
    enabled: !!id && userId !== undefined && userId !== null,
    staleTime: 30 * 1000,
  })
}
