/**
 * Runtime Flags REST API 클라이언트 (P2-2 frontend — backend dispatch 별도)
 *
 * GET /api/runtime/flags  → RuntimeFlagsDto
 *
 * 목적: AppHeader indicator (MAGICJAR_MODE · ALLOW_REAL · KillSwitch · userConsent) — 사용자가
 * 현재 보안 상태를 한 눈에 파악 가능하게 함.
 *
 * 보안 룰:
 *  - 응답에 평문 KIS 키 미포함 (boolean·enum·timestamp만)
 *  - mock 합성 0 LOCK (feedback_no_mock_data) — backend 실패 시 isError → indicator 회색 placeholder
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────────────────────

/** 거래 모드 — backend MAGICJAR_MODE 환경변수 1:1 매핑 */
export type RuntimeMode = 'MOCK' | 'REAL'

/**
 * GET /api/runtime/flags 응답.
 *
 * backend contract LOCK (backend-core dispatch 별도):
 *  - mode: 'MOCK' | 'REAL' — MAGICJAR_MODE 환경변수
 *  - allowReal: MAGICJAR_ALLOW_REAL=true 여부 (REAL 모드에서만 의미 있음)
 *  - killSwitchEnabled: system_config KILL_SWITCH row.enabled
 *  - killSwitchReason: KillSwitch ON 사유 (nullable)
 *  - userConsented: real_trading_consent_at IS NOT NULL (userId 미지정 시 null)
 *  - fetchedAt: 서버 조회 시각 (ISO 8601 UTC)
 */
export interface RuntimeFlagsDto {
  mode: RuntimeMode
  allowReal: boolean
  killSwitchEnabled: boolean
  killSwitchReason: string | null
  userConsented: boolean | null
  fetchedAt: string
}

interface RuntimeFlagsBackendDto {
  mode?: RuntimeMode
  magicjarMode?: RuntimeMode
  allowReal?: boolean
  magicjarAllowReal?: boolean
  killSwitchEnabled?: boolean
  killSwitchActive?: boolean
  killSwitchReason?: string | null
  userConsented?: boolean | null
  fetchedAt?: string
}

// ──────────────────────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────────────────────

export const runtimeApi = {
  /**
   * GET /api/runtime/flags
   * 현재 런타임 상태 조회 — 30s polling 권장 (다중 탭 동기화).
   *
   * backend 구현 의존 — 미구현 시 404 / 500. frontend는 isError 시 indicator hidden.
   */
  getFlags: async (userId?: string | number | null): Promise<RuntimeFlagsDto> => {
    const res = await apiClient.get<RuntimeFlagsBackendDto>('/runtime/flags', {
      params: userId == null ? undefined : { userId },
    })
    const raw = res.data
    return {
      mode: raw.mode ?? raw.magicjarMode ?? 'MOCK',
      allowReal: raw.allowReal ?? raw.magicjarAllowReal ?? false,
      killSwitchEnabled: raw.killSwitchEnabled ?? raw.killSwitchActive ?? false,
      killSwitchReason: raw.killSwitchReason ?? null,
      userConsented: raw.userConsented ?? null,
      fetchedAt: raw.fetchedAt ?? new Date().toISOString(),
    }
  },
}
