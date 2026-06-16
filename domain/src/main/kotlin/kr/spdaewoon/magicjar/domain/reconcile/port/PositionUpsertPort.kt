package kr.spdaewoon.magicjar.domain.reconcile.port

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding

/**
 * Reconcile drift 검출 시 KIS 신뢰원으로 내부 position upsert 포트 (ADR 0070 §2-2 + §2-11 step 2).
 *
 * 구현: Phase 2 backend-core 어댑터 (T-O `PositionUpdatePort` reuse 또는 별도 어댑터).
 * Phase 1 consumer는 abstract만 — ObjectProvider graceful skip + log.warn (어댑터 미합류 시 KIS 호출만 + log).
 *
 * 의미:
 *   drift 검출 시 KIS 값을 신뢰원으로 DB position을 갱신 (ADR 0070 §2-11 자본 보호 흐름 step 2).
 *   이후 Redis position:current:{userId} HSET TTL 24h (별도 캐시 어댑터).
 */
interface PositionUpsertPort {
    fun upsertFromKis(
        userId: String,
        holding: ReconcileHolding,
    )
}
