/**
 * useDeleteKisCredential — DELETE /api/onboarding/kis/{accountType} mutation hook
 *
 * ADR 0074 §2-2-1 MOCK 삭제 정합:
 * - accountType=MOCK만 허용 (REAL은 backend 403 — UI에서도 MOCK만 노출)
 * - 성공 시 useKisCredentials 쿼리 invalidate
 * - 이중 confirm은 UI(ConfirmDangerToggle) 레이어에서 수행
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import apiClient from '../../lib/axios'
import { KIS_CREDENTIALS_QUERY_KEY } from './useKisCredentials'

export function useDeleteKisCredential() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, { accountType: 'MOCK' }>({
    mutationFn: async ({ accountType }) => {
      await apiClient.delete(`/onboarding/kis/${accountType}`)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: KIS_CREDENTIALS_QUERY_KEY })
    },
  })
}
