package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * OrderExecutionRepository port JPA 어댑터 — consumer 모듈.
 *
 * ### T-O Phase 1 B-2 합류 (spec `2026-05-18-to-main-track-entry-plan.md` §B-2)
 * - Phase 2 spec(`_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md`)는 api 모듈 Adapter만 LOCK했으나
 *   `KisExecutionWsListener.handleExecution()` 이 거주하는 consumer 모듈은 api 모듈 미의존
 *   (`consumer/build.gradle.kts` 검증) → consumer JVM bean scan 범위 외 →
 *   `OrderExecutionRepository` ObjectProvider.getIfAvailable() = null → graceful skip
 *   (log.warn "OrderExecutionRepository 미합류") → 분할 체결 영속 0건.
 * - root cause: PR #481(ConsumerOrderRequestRepositoryAdapter + ConsumerOrderAuditEventRepositoryAdapter)
 *   에서 두 Adapter는 합류했으나 OrderExecutionRepository 는 third 합류 누락.
 * - fix: PR #481 동일 패턴 third 합류 — consumer 모듈에 자체 JPA Adapter 신설 + 동일 테이블 `order_execution` 합류.
 *
 * ### 모듈 경계 정합 (ADR 0004)
 * consumer 는 `domain` + `pattern-engine` 만 의존 (ADR 0004 모듈 경계 LOCK 유지). api 모듈 의존 도입 0건.
 * Flyway V21 은 api 모듈 단독 관리 유지 — 본 entity 는 `@Table("order_execution")` 사본만
 * (spring.jpa.hibernate.ddl-auto=validate 모드면 DB 스키마와 일치 검증).
 *
 * ### 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class ConsumerOrderExecutionRepositoryAdapter(
    private val jpa: ConsumerOrderExecutionJpaRepository,
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

    private fun toEntity(domain: OrderExecution): ConsumerOrderExecutionJpaEntity =
        ConsumerOrderExecutionJpaEntity(
            id = domain.id,
            orderId = domain.orderId,
            executedQty = domain.executedQuantity.toBigDecimal(),
            executedPrice = domain.executedPrice,
            executedAt = domain.executedAt,
            fee = domain.fee ?: BigDecimal.ZERO,
            tax = domain.tax ?: BigDecimal.ZERO,
            kisExecutionNo = domain.kisOrderNo.ifBlank { null },
            eventId = domain.eventId,
            createdAt = domain.createdAt,
        )

    private fun toDomain(entity: ConsumerOrderExecutionJpaEntity): OrderExecution =
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
