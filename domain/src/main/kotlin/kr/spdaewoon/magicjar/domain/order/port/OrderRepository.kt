package kr.spdaewoon.magicjar.domain.order.port

import kr.spdaewoon.magicjar.domain.order.Order
import kr.spdaewoon.magicjar.domain.order.OrderStatus
import java.util.UUID

/**
 * 주문 영속 포트 (ADR 0011·0015).
 */
interface OrderRepository {
    fun findById(id: Long): Order?

    fun findByIntentId(intentId: UUID): Order?

    fun findByUserId(
        userId: String,
        limit: Int = 50,
    ): List<Order>

    fun findByStatus(status: OrderStatus): List<Order>

    fun save(order: Order): Order

    fun updateStatus(
        id: Long,
        status: OrderStatus,
    ): Order?
}
