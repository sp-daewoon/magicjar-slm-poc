/**
 * useDaytradeDiscover — POST /api/screener/daytrade-discover mutation
 *
 * ADR 0117 R2 동기 inline 발굴 (마운트 폴링 0).
 * React Query useMutation — 단발 응답 즉시 리스트 표시.
 */
import { useMutation } from '@tanstack/react-query'
import {
  daytradeDiscoverApi,
  type DaytradeDiscoverRequest,
  type DaytradeDiscoverResultDto,
} from '../api/daytradeDiscoverApi'

export function useDaytradeDiscover() {
  return useMutation<DaytradeDiscoverResultDto, Error, DaytradeDiscoverRequest>({
    mutationFn: (req) => daytradeDiscoverApi.discover(req),
  })
}
