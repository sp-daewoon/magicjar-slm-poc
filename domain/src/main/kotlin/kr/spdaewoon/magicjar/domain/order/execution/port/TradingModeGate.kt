package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode

/**
 * 3중 게이트 포트 (ADR 0068 §14-1 LOCK · §14-3 정합 · 의제 A-1).
 *
 * 자본 보호 핵심 — 3 조건 모두 통과해야 REAL 모드 진입:
 *   1. env MAGICJAR_MODE == "REAL"  (false → MOCK fallback, 안전 default)
 *   2. env MAGICJAR_ALLOW_REAL == "true"
 *   3. user_account.real_trading_consent_at != null (사용자 동의 토글)
 *
 * 한 조건이라도 false 시 → [OrderResult.Left] DenyReason.RealGateDenied(cond) 즉시 반환 +
 * audit `REAL_GATE_DENIED:{which condition}` (호출자 책임).
 *
 * ### 페이퍼 전략 MOCK 강제 (ADR 0122 §6 LOCK — GAP-0 hotfix 2026-06-11)
 * [paperStrategy]=true(발굴 승격 `origin=PAPER_DISCOVERY` 전략 주문)면 **env 3중 게이트와 무관하게
 * [TradingMode.MOCK] 즉시 반환**. 시스템이 REAL로 올라가도 페이퍼 전략 주문은 실 주문으로
 * 나가지 않는다(가상 자본 격리와 쌍 — spec `2026-06-11-gap0-paper-mode-gate-hotfix.md`).
 * default false — 기존 호출 계약 zero-regression. paper 판정 책임은 호출자(OrderRouter —
 * strategy.origin DB 단일 진실원 조회, 불명 시 REAL fail-closed는 호출자 책임).
 *
 * Phase 1 consumer TradingModeGateImpl이 구현.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface TradingModeGate {
    fun resolveMode(
        userId: String,
        paperStrategy: Boolean = false,
    ): OrderResult<DenyReason.RealGateDenied, TradingMode>
}
