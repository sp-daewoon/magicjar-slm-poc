package kr.spdaewoon.magicjar.api.service.trend

import kotlinx.datetime.Instant
import java.math.BigDecimal

data class IndexCandle(
    val ts: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long = 0L,
)
