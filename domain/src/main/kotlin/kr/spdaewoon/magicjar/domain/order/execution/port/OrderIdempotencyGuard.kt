package kr.spdaewoon.magicjar.domain.order.execution.port

/**
 * 주문 멱등성 가드 포트 (ADR 0068 §14-1 LOCK · 의제 H-1).
 *
 * Redis SETNX 기반 — TTL 60s default (§14-2 step 2).
 * key 컨벤션: "idem:order:{signalId}:{userId}" (호출자 책임).
 *
 * [acquire] true = 처음 발견 (정상 진행) · false = 이미 처리됨 (skip + audit IDEMPOTENCY_SKIPPED).
 *
 * Redis 장애 시 — §14-4 risk 매트릭스 LOCK: 즉시 REJECTED + 사용자 알림 (가용성보다 자본 보호 우선).
 * 구현 시 RedisConnectionException 등은 호출자가 잡아 audit REDIS_DOWN_FALLBACK 후 REJECTED.
 *
 * Phase 1 consumer OrderIdempotencyGuardImpl(Redis SETNX 어댑터)이 구현.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderIdempotencyGuard {
    fun acquire(
        key: String,
        ttlSec: Long = 60,
    ): Boolean
}
