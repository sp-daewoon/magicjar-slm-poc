package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * OrderExecutionRepository port JPA 어댑터 — Flyway V21 정합 (ADR 0068 §15-2 LOCK).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderExecutionRepositoryAdapter(
    private val jpa: OrderExecutionJpaRepository,
) : OrderExecutionRepository {
    @Transactional
    override fun save(execution: OrderExecution): OrderExecution = toDomain(jpa.save(toEntity(execution)))

    @Transactional(readOnly = true)
    override fun findByOrderId(orderId: Long): List<OrderExecution> = jpa.findByOrderIdOrderByExecutedAtAsc(orderId).map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun sumExecutedQuantityByOrder(orderId: Long): Int = jpa.sumExecutedQtyByOrderId(orderId).toInt()

    @Transactional(readOnly = true)
    override fun existsByEventId(eventId: String): Boolean = jpa.existsByEventId(eventId)

    // --- private helper ---

    private fun toEntity(domain: OrderExecution): OrderExecutionJpaEntity =
        OrderExecutionJpaEntity(
            id = domain.id,
            orderId = domain.orderId,
            executedQty = domain.executedQuantity.toBigDecimal(),
            executedPrice = domain.executedPrice,
            executedAt = domain.executedAt,
            fee = domain.fee ?: java.math.BigDecimal.ZERO,
            tax = domain.tax ?: java.math.BigDecimal.ZERO,
            eventId = domain.eventId,
            createdAt = domain.createdAt,
        )

    private fun toDomain(entity: OrderExecutionJpaEntity): OrderExecution =
        OrderExecution(
            id = entity.id,
            orderId = entity.orderId,
            userId = "",
            kisOrderNo = entity.kisExecutionNo ?: "",
            executedQuantity = entity.executedQty.toInt(),
            executedPrice = entity.executedPrice,
            executedAt = entity.executedAt,
            fee = entity.fee,
            tax = entity.tax,
            eventId = entity.eventId,
            createdAt = entity.createdAt,
        )
}
