/**
 * useTrendPreference — 사용자 추세 설정 + 다중 탭 STOMP sync (T-MARKET R2 frontend §2-1)
 *
 * Backend 1:1:
 * - GET /api/users/me/preferences/trend  → TrendPreferenceResponse (default fallback)
 * - PUT /api/users/me/preferences/trend  → TrendPreferenceResponse + STOMP fanout
 * - STOMP /topic/market.user.{userId}.pref → 다른 탭 변경 즉시 sync
 *
 * race-safe merge — STOMP push 수신 시 setQueryData 직접 갱신 (refetch 0).
 * mock 합성 0 — backend default echo 그대로 표시.
 */
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchTrendPreference,
  updateTrendPreference,
  type TrendPreferenceResponse,
  type TrendPreferenceUpdateRequest,
} from '../market'
import { useStompTopic } from '../../lib/stomp'
import { marketUserPref } from '../stompDestinations'

export const TREND_PREFERENCE_QUERY_KEY = ['trendPreference'] as const

interface UseTrendPreferenceOptions {
  /** userId — STOMP topic 라우팅 키. null 시 STOMP 구독 0 (REST만). */
  userId: number | null | undefined
  /** false 시 query 비활성 (setup 미완 등). default true. */
  enabled?: boolean
}

export function useTrendPreference({ userId, enabled = true }: UseTrendPreferenceOptions) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: TREND_PREFERENCE_QUERY_KEY,
    queryFn: fetchTrendPreference,
    enabled: enabled,
    // 추세 설정은 user-pref → staleTime 1분 (변경 자체가 드뭄, STOMP push가 갱신 본체)
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  })

  // STOMP push — 다른 탭/세션 변경 즉시 반영 (race-safe: setQueryData 직접 갱신)
  useStompTopic<TrendPreferenceResponse>(
    userId != null ? marketUserPref(userId) : '',
    (payload) => {
      // race-safe merge — payload 자체가 최신 권위 (backend updated_at 갱신 직후 fanout)
      queryClient.setQueryData(TREND_PREFERENCE_QUERY_KEY, payload)
    },
    enabled && userId != null,
  )

  const mutation = useMutation({
    mutationFn: (request: TrendPreferenceUpdateRequest) => updateTrendPreference(request),
    onSuccess: (updated) => {
      // 즉시 캐시 갱신 (STOMP 도달 전 UI 응답성 확보).
      queryClient.setQueryData(TREND_PREFERENCE_QUERY_KEY, updated)
      // 추세 의존 query 전체 무효화 — periods 변경 시 차트·snapshot·ranking 다시 계산
      queryClient.invalidateQueries({ queryKey: ['marketIndex'] })
      queryClient.invalidateQueries({ queryKey: ['indexTrend'] })
      queryClient.invalidateQueries({ queryKey: ['sectorRanking'] })
    },
  })

  return {
    preference: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    update: mutation.mutate,
    updateAsync: mutation.mutateAsync,
    isUpdating: mutation.isPending,
    updateError: mutation.error,
  }
}

// ──────────────────────────────────────────────
// 프리셋 카탈로그 (T-MARKET R2 §2-1 SettingsModal 4 프리셋)
// ──────────────────────────────────────────────

export interface TrendPreset {
  id: 'classic' | 'mid' | 'long' | 'custom'
  label: string
  description: string
  maPeriods: number[]
}

export const TREND_PRESETS: TrendPreset[] = [
  { id: 'classic', label: '전통 [5·20·60]', description: '단·중·장기 표준', maPeriods: [5, 20, 60] },
  { id: 'mid', label: '중기 [10·30·90]', description: '주봉 1.5x', maPeriods: [10, 30, 90] },
  { id: 'long', label: '장기 [20·60·120]', description: '월봉 위주', maPeriods: [20, 60, 120] },
]

/** 사용자 maPeriods → 매칭 프리셋 id. 매칭 0 시 'custom'. */
export function detectPresetId(maPeriods: number[]): TrendPreset['id'] {
  for (const preset of TREND_PRESETS) {
    if (
      preset.maPeriods.length === maPeriods.length &&
      preset.maPeriods.every((v, i) => v === maPeriods[i])
    ) {
      return preset.id
    }
  }
  return 'custom'
}

/**
 * pref가 default 인지 + STOMP topic 구독 효율을 위한 user-pref subscribe-only 변형.
 * MarketTrendPage 외 페이지에서 단순 "다른 탭 pref 변경 listen만 하고 setQueryData 갱신"용.
 */
export function useTrendPreferenceSubscription(userId: number | null | undefined): void {
  const queryClient = useQueryClient()
  useStompTopic<TrendPreferenceResponse>(
    userId != null ? marketUserPref(userId) : '',
    (payload) => {
      queryClient.setQueryData(TREND_PREFERENCE_QUERY_KEY, payload)
      queryClient.invalidateQueries({ queryKey: ['marketIndex'] })
      queryClient.invalidateQueries({ queryKey: ['indexTrend'] })
      queryClient.invalidateQueries({ queryKey: ['sectorRanking'] })
    },
    userId != null,
  )
  // useEffect는 useStompTopic 내부에 있으므로 본 hook은 side-effect만 의도.
  // 명시적으로 unused-effect 경고 회피 — 빈 effect 1건.
  useEffect(() => {
    // 본 hook의 단일 책임은 STOMP subscribe + query invalidate.
    // 추가 setup/cleanup은 없음.
  }, [])
}
