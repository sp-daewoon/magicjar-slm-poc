/**
 * useOrder — 주문 단건 조회 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * GET /api/orders/{id}?userId=
 * 반환: request + executions N건 + audit_events 시계열
 */
import { useQuery } from '@tanstack/react-query'
import { orderApi, type OrderDetailDto } from '../order'

/** ADR 0089 Phase 2 — userId number */
export function useOrder(id: number | null | undefined, userId: number | null | undefined) {
  return useQuery<OrderDetailDto, Error>({
    queryKey: ['orders', id, userId],
    queryFn: () => orderApi.get(id!, userId!),
    enabled: id != null && id > 0 && userId !== null && userId !== undefined,
    staleTime: 30_000,
  })
}
