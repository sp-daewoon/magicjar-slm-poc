/**
 * useCancelOrder — 주문 수동 취소 mutation 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * POST /api/orders/{id}/cancel
 * 성공 시 orders 쿼리 자동 무효화
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { orderApi, type CancelOrderResponseDto } from '../order'

interface CancelOrderVariables {
  id: number
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  reason?: string
}

export function useCancelOrder() {
  const queryClient = useQueryClient()

  return useMutation<CancelOrderResponseDto, Error, CancelOrderVariables>({
    mutationFn: ({ id, userId, reason }) => orderApi.cancel(id, userId, reason),
    onSuccess: (_data, variables) => {
      // 목록 + 단건 모두 무효화
      void queryClient.invalidateQueries({ queryKey: ['orders'] })
      void queryClient.invalidateQueries({ queryKey: ['orders', variables.id] })
    },
  })
}
