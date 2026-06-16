package kr.spdaewoon.magicjar.api.service

import java.math.BigDecimal

internal data class QuoteChangeMetrics(
    val price: BigDecimal,
    val signedChange: BigDecimal,
    val signedChangeRate: BigDecimal,
    val changeSign: String?,
) {
    val previousClose: BigDecimal
        get() = price - signedChange
}

internal fun resolveQuoteChangeMetrics(quoteMap: Map<String, Any?>): QuoteChangeMetrics? {
    val price = quoteMap["price"]?.toString()?.toBigDecimalOrNull() ?: return null
    val rawChange = quoteMap["priceChange"]?.toString()?.toBigDecimalOrNull() ?: return null
    val rawChangeRate = quoteMap["changeRate"]?.toString()?.toBigDecimalOrNull() ?: return null
    val changeSign = quoteMap["changeSign"]?.toString()?.trim()?.ifBlank { null }

    return QuoteChangeMetrics(
        price = price,
        signedChange = signedQuoteValue(rawChange, changeSign),
        signedChangeRate = signedQuoteValue(rawChangeRate, changeSign),
        changeSign = changeSign,
    )
}

private fun signedQuoteValue(
    value: BigDecimal,
    changeSign: String?,
): BigDecimal =
    when (changeSign) {
        "1", "2" -> value.abs()
        "3" -> BigDecimal.ZERO
        "4", "5" -> value.abs().negate()
        else -> value
    }
