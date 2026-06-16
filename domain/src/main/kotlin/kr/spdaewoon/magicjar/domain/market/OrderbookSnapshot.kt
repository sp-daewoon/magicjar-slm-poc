package kr.spdaewoon.magicjar.domain.market

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 호가 단계 — 가격 + 잔량.
 */
data class PriceLevel(
    val price: BigDecimal,
    val quantity: Long,
)

/**
 * 호가 스냅샷 — 매수·매도 10단계 + 통합 호가 부가 필드 (H0UNASP0).
 *
 * KIS-first 출처: open-trading-api examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   BIDP1~BIDP10 (매수호가 1~10단계) index 13~22
 *   ASKP1~ASKP10 (매도호가 1~10단계) index 3~12
 *   BIDP_RSQN1~10 (매수호가잔량) index 33~42
 *   ASKP_RSQN1~10 (매도호가잔량) index 23~32
 *   ANTC_* (예상체결가·예상체결수량·예상거래량·전일대비) index 47~52
 */
data class OrderbookSnapshot(
    val code: String,
    val ts: Instant,
    val bids: List<PriceLevel>, // 매수호가 10단계 (BIDP1=index13 ~ BIDP10=index22)
    val asks: List<PriceLevel>, // 매도호가 10단계 (ASKP1=index3 ~ ASKP10=index12)
    val totalAskQuantity: Long? = null,
    val totalBidQuantity: Long? = null,
    val overtimeTotalAskQuantity: Long? = null,
    val overtimeTotalBidQuantity: Long? = null,
    val anticipatedPrice: BigDecimal? = null,
    val anticipatedQuantity: Long? = null,
    val anticipatedVolume: Long? = null,
    val anticipatedChange: BigDecimal? = null,
    val anticipatedChangeSign: String? = null,
    val anticipatedChangeRate: BigDecimal? = null,
    val accumulatedVolume: Long? = null,
    val stockDealClassCode: String? = null,
)
