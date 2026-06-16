/**
 * useKisCredentials — GET /api/onboarding/kis/credentials query hook
 *
 * ADR 0074 §8 정합 — KIS 마스킹 응답 조회
 * 보안 룰: 응답에 평문 키 미포함 (mask만) — 메모리 feedback_secret_handling.md 정합
 *
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 * - 등록 0건 시 credentials=[] 빈 배열 → UI "등록된 KIS 키 없음" 표시
 */
import { useQuery } from '@tanstack/react-query'
import apiClient from '../../lib/axios'
import type { MaskedKisCredentialResponse } from '../types/settings'

export const KIS_CREDENTIALS_QUERY_KEY = ['settings', 'kisCredentials'] as const

export function useKisCredentials() {
  return useQuery<MaskedKisCredentialResponse>({
    queryKey: KIS_CREDENTIALS_QUERY_KEY,
    queryFn: async () => {
      const res = await apiClient.get<MaskedKisCredentialResponse>('/onboarding/kis/credentials')
      return res.data
    },
    staleTime: 30_000,
    retry: 1,
  })
}
