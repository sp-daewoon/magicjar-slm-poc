package kr.spdaewoon.magicjar.domain.order.execution.port

import java.time.Instant

/**
 * 실전 매매 사용자 동의 조회 포트 (ADR 0068 §14-3 3중 게이트 조건 3 LOCK).
 *
 * 사용자 자본 보호 핵심 — REAL 모드 진입 시 user_account.real_trading_consent_at 검사 의무.
 *
 * [findConsentAt] return null → CONSENT_NOT_GIVEN denied
 *                  return Instant → 사용자 동의 시각 (UI 화면에 표시 가능)
 *
 * Phase 2 backend-core에서 V23 컬럼 추가 후 JPA 어댑터 구현. 본 Phase 1은 포트만 추가.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface RealTradingConsentPort {
    fun findConsentAt(userId: String): Instant?
}
