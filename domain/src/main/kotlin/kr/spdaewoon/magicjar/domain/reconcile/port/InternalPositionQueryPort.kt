package kr.spdaewoon.magicjar.domain.reconcile.port

import kr.spdaewoon.magicjar.domain.reconcile.InternalHolding

/**
 * 내부 DB position 조회 포트 (ADR 0070 §2-2).
 *
 * 구현: Phase 2 backend-core 어댑터 (position 테이블 select 또는 T-RISK `RiskPositionPort` reuse).
 * Phase 1 consumer는 abstract만 — ObjectProvider graceful skip 시 emptyList() default.
 *
 * 의미:
 *   reconcile 비교용 — KIS 잔고 vs 내부 DB 잔고 diff 계산 의무 입력.
 */
interface InternalPositionQueryPort {
    fun findCurrentByUser(userId: String): List<InternalHolding>
}
