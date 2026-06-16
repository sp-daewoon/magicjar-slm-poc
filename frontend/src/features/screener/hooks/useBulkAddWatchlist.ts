/**
 * useBulkAddWatchlist — screener 매칭 결과 → Watchlist 일괄 등록
 *
 * ADR 0071 §8 "Watchlist 일괄 등록" 합류.
 * 본 사이클은 단건 POST multi 호출 (bulk endpoint 미발행 — backlog §57 정합).
 *
 * 구현: Promise.allSettled 사용 → 일부 실패(중복 등) 시 다른 등록 계속 진행
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { watchlistApi } from '../../../api/watchlist'

interface BulkAddRequest {
  codes: string[]
}

export interface BulkAddResult {
  succeeded: string[]
  failed: { code: string; reason: string }[]
}

export function useBulkAddWatchlist() {
  const queryClient = useQueryClient()

  return useMutation<BulkAddResult, Error, BulkAddRequest>({
    mutationFn: async ({ codes }) => {
      const results = await Promise.allSettled(
        codes.map((code) => watchlistApi.add({ code })),
      )

      const succeeded: string[] = []
      const failed: { code: string; reason: string }[] = []

      results.forEach((result, idx) => {
        if (result.status === 'fulfilled') {
          succeeded.push(codes[idx])
        } else {
          const reason =
            result.reason instanceof Error ? result.reason.message : '알 수 없는 오류'
          failed.push({ code: codes[idx], reason })
        }
      })

      return { succeeded, failed }
    },
    onSuccess: () => {
      // watchlist 목록 쿼리 invalidate → 즉시 재조회
      void queryClient.invalidateQueries({ queryKey: ['watchlist'] })
    },
  })
}
