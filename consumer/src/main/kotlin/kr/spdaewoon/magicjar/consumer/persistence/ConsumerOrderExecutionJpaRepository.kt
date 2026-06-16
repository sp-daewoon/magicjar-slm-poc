package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

/**
 * order_execution Spring Data JPA Repository — consumer 모듈.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * 헬퍼는 [ConsumerOrderExecutionRepositoryAdapter] private fun 위치.
 *
 * ### T-O Phase 1 B-2 합류 (spec `2026-05-18-to-main-track-entry-plan.md` §B-2)
 * api 모듈 [kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository] 동일 abstract fun 매트릭스.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ConsumerOrderExecutionJpaRepository : JpaRepository<ConsumerOrderExecutionJpaEntity, Long> {
    fun findByOrderIdOrderByExecutedAtAsc(orderId: Long): List<ConsumerOrderExecutionJpaEntity>

    @Query(
        "SELECT COALESCE(SUM(e.executedQty), 0) FROM ConsumerOrderExecutionJpaEntity e WHERE e.orderId = :orderId",
    )
    fun sumExecutedQtyByOrderId(
        @Param("orderId") orderId: Long,
    ): BigDecimal

    /**
     * ADR 0084 P0-3 — event_id 기준 멱등 가드 (Repository abstract — body 0건, 헌법 §6 정합).
     * Flyway V35 `uq_execution_event_id` partial index 백업.
     */
    fun existsByEventId(eventId: String): Boolean
}
