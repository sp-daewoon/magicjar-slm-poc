package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.math.BigDecimal

/**
 * KIS 종목 미시구조 read-only 조회 포트.
 *
 * 주문/취소 권한은 없고, 종목 상세/MCP/운영 진단에서 투자자 수급과 최근 체결을 보강하기 위한 경계다.
 */
interface KisMarketMicrostructurePort {
    fun fetchInvestorFlow(
        userId: String,
        mode: TradingMode,
        code: String,
        marketDivCode: String = "J",
    ): List<KisInvestorFlow>

    fun fetchRecentExecutions(
        userId: String,
        mode: TradingMode,
        code: String,
        marketDivCode: String = "J",
    ): List<KisRecentExecution>
}

data class KisInvestorFlow(
    val code: String,
    val marketDivCode: String,
    val businessDate: String?,
    val closePrice: BigDecimal?,
    val individualNetBuyQty: Long?,
    val foreignNetBuyQty: Long?,
    val institutionNetBuyQty: Long?,
    val individualNetBuyAmount: BigDecimal?,
    val foreignNetBuyAmount: BigDecimal?,
    val institutionNetBuyAmount: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)

data class KisRecentExecution(
    val code: String,
    val marketDivCode: String,
    val executionTime: String?,
    val price: BigDecimal?,
    val volume: Long?,
    val relativeStrength: BigDecimal?,
    val previousDayRate: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)
