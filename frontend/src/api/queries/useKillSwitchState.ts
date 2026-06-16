/**
 * useKillSwitchState — GET /api/system-config/kill-switch query hook
 *
 * ADR 0074 §3 정합 — KillSwitch 현재 상태 조회
 *
 * polling: 30초마다 자동 갱신 (drift auto-ON 감지 UX 정합)
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 * - 초기 row 없을 때: enabled=false, reason=null, updatedAt=null, updatedBy="(없음)"
 */
import { useQuery } from '@tanstack/react-query'
import apiClient from '../../lib/axios'
import type { KillSwitchStateResponse } from '../types/settings'

export const KILL_SWITCH_QUERY_KEY = ['settings', 'killSwitch'] as const

export function useKillSwitchState() {
  return useQuery<KillSwitchStateResponse>({
    queryKey: KILL_SWITCH_QUERY_KEY,
    queryFn: async () => {
      const res = await apiClient.get<KillSwitchStateResponse>('/system-config/kill-switch')
      return res.data
    },
    staleTime: 10_000,
    refetchInterval: 30_000,  // 30초 polling — drift auto-ON 감지
    retry: 1,
  })
}
