/**
 * useScreenerEvaluate — POST /api/screener/evaluate mutation
 *
 * ADR 0071 §2-1 동기 inline 평가 정합 (마운트 폴링 0)
 * React Query useMutation — 단발 응답 즉시 모달 표시
 */
import { useMutation } from '@tanstack/react-query'
import { screenerApi, type ScreenerEvaluateRequest, type ScreenerRunResponse } from '../api/screenerApi'

export function useScreenerEvaluate() {
  return useMutation<ScreenerRunResponse, Error, ScreenerEvaluateRequest>({
    mutationFn: (req) => screenerApi.evaluate(req),
  })
}
