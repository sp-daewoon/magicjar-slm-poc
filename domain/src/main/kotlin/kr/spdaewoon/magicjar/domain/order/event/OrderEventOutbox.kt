package kr.spdaewoon.magicjar.domain.order.event

import java.time.Instant
import java.util.UUID

/**
 * 주문 이벤트 아웃박스 도메인 (ADR 0084 P1-1 + spec 2026-05-18 §2-5 LOCK).
 *
 * Transactional Outbox 패턴 — 자본 path 4 producer (order.intent.v1 · order.requested.v1 ·
 * order.rejected.v1 · order.filled.v1) Kafka publish 실패 시 row 잔존 + 비동기 retry.
 *
 * NotificationOutbox(ADR 0070 §3-3)의 자본 path superset:
 *   - eventId UUID UNIQUE (멱등 키)
 *   - topic + partition_key + payload_json (Kafka send 3-tuple)
 *   - status PENDING/PUBLISHED/FAILED + published_at + attempt_count + last_error
 *
 * Flyway V40 정합. JPA 엔티티는 api 모듈 OrderEventOutboxJpaEntity.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
data class OrderEventOutbox(
    val id: Long? = null,
    val eventId: UUID = UUID.randomUUID(),
    val eventType: OrderOutboxEventType,
    val topic: String,
    val partitionKey: String?,
    val payloadJson: String,
    val status: OrderOutboxStatus = OrderOutboxStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val publishedAt: Instant? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

/**
 * 주문 outbox 이벤트 유형 — 자본 path 4 producer LOCK (spec §2-5).
 *
 * Phase 2에서 CANCELLED·MODIFY 등 확장 시 enum value add-only.
 */
enum class OrderOutboxEventType {
    ORDER_INTENT,
    ORDER_REQUESTED,
    ORDER_REJECTED,
    ORDER_FILLED,
}

/**
 * 주문 outbox 상태 전이 (spec §2-5).
 *
 *   PENDING ──publish 성공──> PUBLISHED
 *   PENDING ──attempt_count 한계──> FAILED (worker가 운영 알림)
 */
enum class OrderOutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
