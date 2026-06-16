/**
 * Settings 관련 TypeScript 타입 정의 (ADR 0074 §8 정합)
 *
 * KIS 키 변경 관련: MaskedKisCredentialResponse · MaskedKisCredentialItem · KisCredentialUpdateRequest
 * KillSwitch 관련: KillSwitchStateResponse · KillSwitchToggleRequest
 *
 * 보안 룰 (메모리 feedback_secret_handling.md 정합):
 * - 응답 body에 평문 appKey·secretKey 절대 미포함 (mask만)
 * - frontend state에 평문 키 hold 금지
 */

// ──────────────────────────────────────────────
// KIS 키 관련 타입 (ADR 0074 §2-2-2 정합)
// ──────────────────────────────────────────────

/**
 * GET /api/onboarding/kis/credentials 응답 내부 아이템
 *
 * 마스킹 형식 LOCK (ADR 0074 §2-2-2):
 * - accountNumber: "7468****" (DB masked_account GENERATED column)
 * - appKeyMasked: "PS01***wxyz" (앞 4자 + *** + 뒤 4자)
 * - secretKeyMasked: "********" (전부 마스킹)
 * - hasKey: false 시 row 미등록 → UI "등록된 KIS 키 없음" 표시
 */
export interface MaskedKisCredentialItem {
  accountType: 'REAL' | 'MOCK'
  accountNumber: string       // "7468****"
  appKeyMasked: string        // "PS01***wxyz"
  secretKeyMasked: string     // "********"
  lastValidatedAt: string | null  // ISO 8601 instant (null = 미등록)
  hasKey: boolean             // false = row 미등록
}

/**
 * GET /api/onboarding/kis/credentials 응답 (최상위)
 * ADR 0089 Phase 2 — userId number (backend Long)
 */
export interface MaskedKisCredentialResponse {
  userId: number
  credentials: MaskedKisCredentialItem[]
}

/**
 * PUT /api/onboarding/kis/{accountType} 요청
 *
 * accountNumber는 read-only — request body 포함 금지 (ADR 0074 §2-2-1 정합)
 */
export interface KisCredentialUpdateRequest {
  appKey: string
  secretKey: string
}

/**
 * PUT /api/onboarding/kis/{accountType} 응답
 * (200 성공 시 갱신된 마스킹 아이템 반환)
 */
export type KisCredentialUpdateResponse = MaskedKisCredentialItem

// ──────────────────────────────────────────────
// KillSwitch 관련 타입 (ADR 0074 §3 정합)
// ──────────────────────────────────────────────

/**
 * GET /api/system-config/kill-switch 응답
 * PUT /api/system-config/kill-switch 응답 (동일 DTO)
 *
 * updatedBy: "AUTO" | "USER:<userId>" (ADR 0043 baseline)
 */
export interface KillSwitchStateResponse {
  enabled: boolean
  reason: string | null       // null 가능 (초기 등록 X)
  updatedAt: string | null    // ISO 8601 instant (null 가능 — 초기)
  updatedBy: string           // "AUTO" | "USER:<userId>"
}

/**
 * PUT /api/system-config/kill-switch 요청
 *
 * enabled + reason 둘 다 필수 (ADR 0074 §3-3 정합)
 * reason minLength=5 maxLength=64 (backend VARCHAR(64) 정합)
 */
export interface KillSwitchToggleRequest {
  enabled: boolean
  reason: string              // minLength=5, maxLength=64
}
