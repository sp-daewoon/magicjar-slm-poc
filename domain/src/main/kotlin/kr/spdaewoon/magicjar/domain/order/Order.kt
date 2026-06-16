package kr.spdaewoon.magicjar.domain.order

import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.util.UUID

/**
 * 주문 의도 — 아직 KIS에 미전송 (ADR 0011).
 */
data class OrderIntent(
    val intentId: UUID = UUID.randomUUID(),
    val userId: String,
    val code: String,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: Int,
    val price: BigDecimal?,
)

/**
 * 주문 결과 — KIS에 전송 후 상태 추적 (ADR 0011·0015).
 */
data class Order(
    val id: Long? = null,
    val intentId: UUID,
    val userId: String,
    val code: String,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: Int,
    val price: BigDecimal?,
    val status: OrderStatus,
    val rejectReason: String? = null,
    val rejectDetail: String? = null,
    val rejectSource: String? = null,
    val kisOrdNo: String? = null,
    val createdAt: Instant,
    val executedAt: Instant? = null,
)

enum class OrderSide { BUY, SELL }

enum class OrderType { LIMIT, MARKET, CONDITION }

enum class OrderStatus {
    PENDING,
    PARTIAL_FILLED,
    FILLED,
    REJECTED,
    CANCELLED,
}

/**
 * 주문 거절 사유 (ADR 0015 §RejectReason 정합).
 */
enum class RejectReason {
    KILL_SWITCH_ACTIVE,
    DAILY_LOSS_LIMIT,
    POSITION_LIMIT,
    PRICE_GUARD,
    KIS_API_ERROR,
    UNKNOWN,
}
