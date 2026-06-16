package kr.spdaewoon.magicjar.domain.position

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 보유 포지션 도메인 (ADR 0042).
 * quantity = 0이면 포지션 청산 (DB 행은 유지).
 */
data class Position(
    val id: Long? = null,
    val userId: String,
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val updatedAt: Instant,
) {
    val isClosed: Boolean get() = quantity == 0
}

/**
 * 손익 계산 (미실현 PnL).
 */
data class PnL(
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val unrealizedPnl: BigDecimal = (currentPrice - avgPrice) * BigDecimal(quantity),
    val unrealizedPnlPct: BigDecimal =
        if (avgPrice.compareTo(BigDecimal.ZERO) != 0) {
            (currentPrice - avgPrice)
                .divide(avgPrice, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
        } else {
            BigDecimal.ZERO
        },
)
