/**
 * useUpdateRealTradingConsent — 실거래 동의 토글 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * PUT /api/user-account/real-trading-consent
 * 성공 시 consent 쿼리 자동 무효화
 *
 * 이중 confirm UX: UI 레이어(RealTradingConsentToggle)에서 수행 (§16-4)
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  accountApi,
  type ConsentToggleRequestDto,
  type ConsentToggleResponseDto,
} from '../account'

/** ADR 0089 Phase 2 — userId number */
export function useRealTradingConsent(userId: number | null | undefined) {
  return useQuery<ConsentToggleResponseDto, Error>({
    queryKey: ['realTradingConsent', userId],
    queryFn: () => accountApi.getRealTradingConsent(userId!),
    enabled: userId !== null && userId !== undefined,
    staleTime: 60_000,
  })
}

export function useUpdateRealTradingConsent(userId: number | null | undefined) {
  const queryClient = useQueryClient()

  return useMutation<ConsentToggleResponseDto, Error, ConsentToggleRequestDto>({
    mutationFn: (req) => accountApi.updateRealTradingConsent(userId!, req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['realTradingConsent'] })
    },
  })
}
