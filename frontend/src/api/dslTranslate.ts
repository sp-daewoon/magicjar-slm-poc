/**
 * DSL 자연어 변환 API 클라이언트
 *
 * T-DSL ai-engineer PR-36 endpoint LOCK:
 *   POST /api/dsl/translate-from-natural
 *
 * ADR 0023 §1 사용자 트리거만 — 자동 호출 0
 * ADR 0023 §2 매매 결정권 0 — DSL 텍스트만 반환
 * ADR 0023 §3 mock fallback — MAGICJAR_AI_ENABLED=false 시 mode=DISABLED
 * ADR 0023 §4 503 disabled
 *
 * 룰 5: LLM provider import 0건 — backend endpoint 호출만
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// DTO 타입
// ──────────────────────────────────────────────

export interface TranslateFromNaturalRequest {
  text: string
}

/** 번역 모드 분기 */
export type TranslateMode = 'REAL' | 'MOCK' | 'DISABLED' | 'UNTRANSLATABLE'

/** POST /api/dsl/translate-from-natural 응답 */
export interface TranslateFromNaturalResponse {
  mode: TranslateMode
  /** 번역 성공 시 entry DSL 표현식 */
  dslEntry?: string
  /** 번역 성공 시 exit DSL 표현식 (optional) */
  dslExit?: string
  /** UNTRANSLATABLE/DISABLED 시 사용자 안내 메시지 */
  reasoning?: string
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const dslTranslateApi = {
  /**
   * POST /api/dsl/translate-from-natural
   * 한국어 자연어 → DSL 텍스트 변환
   *
   * MAGICJAR_AI_ENABLED=false 시 503 또는 mode=DISABLED
   * 사용자 트리거만 — 자동 호출 0 (ADR 0023)
   */
  translateFromNatural: async (
    req: TranslateFromNaturalRequest,
  ): Promise<TranslateFromNaturalResponse> => {
    const res = await apiClient.post<TranslateFromNaturalResponse>(
      '/dsl/translate-from-natural',
      req,
    )
    return res.data
  },
}
