package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent

/**
 * OrderAuditEvent 영속 포트 (ADR 0068 §14-1 LOCK · 헌법 §6 abstract만).
 *
 * §14-2 매 step + §14-6 흐름 분기마다 save 호출.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderAuditEventRepository {
    fun save(event: OrderAuditEvent): OrderAuditEvent

    fun findByOrderId(orderId: Long): List<OrderAuditEvent>
}
