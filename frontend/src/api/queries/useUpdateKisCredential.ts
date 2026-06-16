/**
 * useUpdateKisCredential — PUT /api/onboarding/kis/{accountType} mutation hook
 *
 * ADR 0074 §2-2-1 풀세트 흐름 정합:
 * [1] PUT 성공 → backend: Redis DEL + Kafka publish
 * [2] consumer: WS graceful reconnect (accountType=REAL 시)
 * [3] 성공 응답 → useKisCredentials 쿼리 invalidate → 마스킹 응답 refetch
 * [4] toast: "KIS 키 변경 완료 — WS 재연결됨"
 *
 * 보안 룰: 요청 body에 accountNumber 포함 금지 (read-only — ADR 0074 §2-2-1 정합)
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import apiClient from '../../lib/axios'
import type { KisCredentialUpdateRequest, KisCredentialUpdateResponse } from '../types/settings'
import { KIS_CREDENTIALS_QUERY_KEY } from './useKisCredentials'

interface MutationVariables {
  accountType: 'REAL' | 'MOCK'
  request: KisCredentialUpdateRequest
}

export function useUpdateKisCredential() {
  const queryClient = useQueryClient()

  return useMutation<KisCredentialUpdateResponse, Error, MutationVariables>({
    mutationFn: async ({ accountType, request }) => {
      const res = await apiClient.put<KisCredentialUpdateResponse>(
        `/onboarding/kis/${accountType}`,
        request,
      )
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: KIS_CREDENTIALS_QUERY_KEY })
    },
  })
}
