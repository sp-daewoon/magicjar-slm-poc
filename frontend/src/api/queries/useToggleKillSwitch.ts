/**
 * useToggleKillSwitch — PUT /api/system-config/kill-switch mutation hook
 *
 * ADR 0074 §3-3 PUT 흐름 정합:
 * [1] ConfirmDangerToggle confirm + 사유 입력 + 20초 cooldown (UI 레이어 책임 — 2026-05-20 60→20 단축)
 * [2] PUT { enabled, reason } → backend: UPDATE kill_switch_state + Kafka publish
 * [3] 성공 시 useKillSwitchState 쿼리 invalidate
 *
 * 사유 validation: minLength=5, maxLength=64 (backend VARCHAR(64) 정합)
 * — UI 레이어에서 사전 검증 후 mutation 호출 (submit disabled 정책)
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import apiClient from '../../lib/axios'
import type { KillSwitchStateResponse, KillSwitchToggleRequest } from '../types/settings'
import { KILL_SWITCH_QUERY_KEY } from './useKillSwitchState'

export function useToggleKillSwitch() {
  const queryClient = useQueryClient()

  return useMutation<KillSwitchStateResponse, Error, KillSwitchToggleRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.put<KillSwitchStateResponse>('/system-config/kill-switch', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: KILL_SWITCH_QUERY_KEY })
    },
  })
}
