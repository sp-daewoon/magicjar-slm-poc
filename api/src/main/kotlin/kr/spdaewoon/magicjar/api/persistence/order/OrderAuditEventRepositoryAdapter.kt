package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * OrderAuditEventRepository port JPA 어댑터 — Flyway V22 정합 (ADR 0068 §15-2 LOCK).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderAuditEventRepositoryAdapter(
    private val jpa: OrderAuditEventJpaRepository,
) : OrderAuditEventRepository {
    @Transactional
    override fun save(event: OrderAuditEvent): OrderAuditEvent = toDomain(jpa.save(toEntity(event)))

    @Transactional(readOnly = true)
    override fun findByOrderId(orderId: Long): List<OrderAuditEvent> = jpa.findByOrderIdOrderByTsAsc(orderId).map { toDomain(it) }

    // --- private helper ---

    private fun toEntity(domain: OrderAuditEvent): OrderAuditEventJpaEntity =
        OrderAuditEventJpaEntity(
            id = domain.id,
            orderId = domain.orderId,
            userId = domain.userId,
            action = domain.eventType.name,
            detailJson = domain.payloadJson,
            ts = domain.createdAt,
        )

    private fun toDomain(entity: OrderAuditEventJpaEntity): OrderAuditEvent =
        OrderAuditEvent(
            id = entity.id,
            orderId = entity.orderId,
            userId = entity.userId,
            eventType = safeEventType(entity.action),
            payloadJson = entity.detailJson ?: "{}",
            createdAt = entity.ts,
        )

    private fun safeEventType(value: String): OrderAuditEventType =
        runCatching { OrderAuditEventType.valueOf(value) }.getOrDefault(OrderAuditEventType.KIS_ACK)
}
