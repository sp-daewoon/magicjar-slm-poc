package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * order_audit_event Spring Data JPA Repository — consumer 모듈.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ConsumerOrderAuditEventJpaRepository : JpaRepository<ConsumerOrderAuditEventJpaEntity, Long> {
    fun findByOrderIdOrderByTsAsc(orderId: Long): List<ConsumerOrderAuditEventJpaEntity>
}
