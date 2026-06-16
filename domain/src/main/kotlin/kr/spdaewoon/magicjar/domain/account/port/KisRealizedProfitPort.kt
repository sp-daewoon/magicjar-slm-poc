package kr.spdaewoon.magicjar.domain.account.port

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.math.BigDecimal
import java.time.Instant

interface KisRealizedProfitPort {
    fun fetchRealizedProfit(
        userId: String,
        mode: TradingMode,
    ): KisRealizedProfitSnapshot
}

data class KisRealizedProfitSnapshot(
    val userId: String,
    val mode: TradingMode,
    val items: List<KisRealizedProfitItem>,
    val summary: KisRealizedProfitSummary?,
    val fetchedAt: Instant,
)

data class KisRealizedProfitItem(
    val code: String,
    val name: String?,
    val holdingQty: Int?,
    val orderableQty: Int?,
    val purchaseAmount: BigDecimal?,
    val evaluationAmount: BigDecimal?,
    val evaluationProfitLoss: BigDecimal?,
    val realizedProfitLoss: BigDecimal?,
    val realizedReturnRate: BigDecimal?,
    val realEvaluationProfitLoss: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)

data class KisRealizedProfitSummary(
    val cashBalance: BigDecimal?,
    val cashD1: BigDecimal?,
    val cashD2: BigDecimal?,
    val totalEvaluation: BigDecimal?,
    val netAssetValue: BigDecimal?,
    val totalEvaluationProfitLoss: BigDecimal?,
    val realizedProfitLoss: BigDecimal?,
    val realizedReturnRate: BigDecimal?,
    val realEvaluationProfitLoss: BigDecimal?,
    val realEvaluationReturnRate: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)
