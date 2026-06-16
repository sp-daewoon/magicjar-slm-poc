/**
 * useOrders — 주문 목록 조회 훅 (T-O Phase 3 — ADR 0068 §16 · T-MAN Phase 2 — ADR 0082)
 *
 * GET /api/orders?userId=&status=&side=&code=&source=&from=&to=&page=&size=
 *
 * 기능:
 * - userId 필수 — 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * - 필터 파라미터 변경 시 자동 재조회
 * - 빈 목록 graceful (feedback_no_mock_data 정합)
 * - T-MAN Phase 2: source filter ('AUTO'·'MANUAL') 흡수 — OrdersPage filter toggle 연동
 */
import { useQuery } from '@tanstack/react-query'
import { orderApi, type OrderListParams, type OrderListPage } from '../order'

export interface UseOrdersOptions extends OrderListParams {
  enabled?: boolean
}

export function useOrders(params: UseOrdersOptions = {}) {
  const { enabled = true, ...queryParams } = params

  return useQuery<OrderListPage, Error>({
    queryKey: ['orders', queryParams],
    queryFn: () => orderApi.list(queryParams),
    // userId 없으면 쿼리 비활성 → WARN 0건 (ADR 0089 — userId number)
    enabled:
      enabled && queryParams.userId !== undefined && queryParams.userId !== null,
    staleTime: 30_000,
  })
}
