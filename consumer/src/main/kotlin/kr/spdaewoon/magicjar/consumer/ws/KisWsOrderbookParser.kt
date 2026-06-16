package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.market.OrderbookSnapshot
import kr.spdaewoon.magicjar.domain.market.PriceLevel
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * KIS WebSocket H0UNASP0 통합 호가 메시지 파서.
 *
 * KIS-first 출처: open-trading-api examples_user/domestic_stock/domestic_stock_functions_ws.py
 *
 * H0UNASP0 PIPE+캐럿 메시지 형식:
 *   0|H0UNASP0|001|{데이터}
 *   데이터는 캐럿(^) 구분 65개 필드.
 *
 * 필드 인덱스 (asking_price_total() cols 배열 기반):
 *   [0]  MKSC_SHRN_ISCD  — 종목코드
 *   [1]  BSOP_HOUR       — 영업시간 (HHMMSS)
 *   [2]  HOUR_CLS_CODE   — 시간구분코드
 *   [3]  ASKP1           — 매도호가1
 *   [4]  ASKP2           — 매도호가2
 *   ...
 *   [12] ASKP10          — 매도호가10
 *   [13] BIDP1           — 매수호가1
 *   [14] BIDP2           — 매수호가2
 *   ...
 *   [22] BIDP10          — 매수호가10
 *   [23] ASKP_RSQN1      — 매도호가잔량1
 *   ...
 *   [32] ASKP_RSQN10     — 매도호가잔량10
 *   [33] BIDP_RSQN1      — 매수호가잔량1
 *   ...
 *   [42] BIDP_RSQN10     — 매수호가잔량10
 *   [43] TOTAL_ASKP_RSQN — 총 매도호가잔량
 *   [44] TOTAL_BIDP_RSQN — 총 매수호가잔량
 *   [45] OVTM_TOTAL_ASKP_RSQN — 시간외 총 매도호가잔량
 *   [46] OVTM_TOTAL_BIDP_RSQN — 시간외 총 매수호가잔량
 *   [47] ANTC_CNPR       — 예상 체결가
 *   [48] ANTC_CNQN       — 예상 체결수량
 *   [49] ANTC_VOL        — 예상 거래량
 *   [50] ANTC_CNTG_VRSS  — 예상 체결 전일대비
 *   [51] ANTC_CNTG_VRSS_SIGN — 예상 체결 전일대비 부호
 *   [52] ANTC_CNTG_PRDY_CTRT — 예상 체결 전일대비율
 *   [53] ACML_VOL        — 누적 거래량
 *   [58] STCK_DEAL_CLS_CODE — 거래 구분 코드
 */
object KisWsOrderbookParser {
    private val log = LoggerFactory.getLogger(KisWsOrderbookParser::class.java)
    private val kst = TimeZone.of("Asia/Seoul")

    // H0UNASP0 필드 인덱스 (open-trading-api asking_price_total() 기반)
    private const val IDX_CODE = 0
    private const val IDX_BSOP_HOUR = 1 // HHMMSS
    private const val IDX_ASKP_START = 3 // 매도호가1~10 (index 3~12)
    private const val IDX_BIDP_START = 13 // 매수호가1~10 (index 13~22)
    private const val IDX_ASKP_RSQN_START = 23 // 매도호가잔량1~10 (index 23~32)
    private const val IDX_BIDP_RSQN_START = 33 // 매수호가잔량1~10 (index 33~42)
    private const val IDX_TOTAL_ASKP_RSQN = 43
    private const val IDX_TOTAL_BIDP_RSQN = 44
    private const val IDX_OVTM_TOTAL_ASKP_RSQN = 45
    private const val IDX_OVTM_TOTAL_BIDP_RSQN = 46
    private const val IDX_ANTC_CNPR = 47
    private const val IDX_ANTC_CNQN = 48
    private const val IDX_ANTC_VOL = 49
    private const val IDX_ANTC_CNTG_VRSS = 50
    private const val IDX_ANTC_CNTG_VRSS_SIGN = 51
    private const val IDX_ANTC_CNTG_PRDY_CTRT = 52
    private const val IDX_ACML_VOL = 53
    private const val IDX_STCK_DEAL_CLS_CODE = 58
    private const val LEVELS = 10
    private const val MIN_FIELDS = 43 // 최소 필요 필드 수 (ASKP/BIDP 10단계 + 잔량 10단계)

    /**
     * H0UNASP0 rawData(캐럿 구분 필드열) → OrderbookSnapshot.
     * rawData는 KisWsMessageParser.parse()의 RealtimeData.rawData 값.
     *
     * KIS-first 출처: domestic_stock_functions_ws.py asking_price_total() 필드 인덱스 직접 인용.
     */
    fun parseH0Unasp0(rawData: String): OrderbookSnapshot? {
        return try {
            val fields = rawData.split("^")
            if (fields.size < MIN_FIELDS) {
                log.warn("H0UNASP0 필드 수 부족: expected >={} got={}", MIN_FIELDS, fields.size)
                return null
            }

            val code = fields[IDX_CODE].trim()
            val timeStr = fields[IDX_BSOP_HOUR].trim()

            val ts =
                if (timeStr.length == 6) {
                    try {
                        val now =
                            kotlinx.datetime.Clock.System
                                .now()
                                .toLocalDateTime(kst)
                        val hour = timeStr.substring(0, 2).toInt()
                        val minute = timeStr.substring(2, 4).toInt()
                        val second = timeStr.substring(4, 6).toInt()
                        kotlinx.datetime
                            .LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, hour, minute, second)
                            .toInstant(kst)
                    } catch (e: Exception) {
                        log.debug("H0UNASP0 시각 파싱 실패 — Clock.now() 사용: time={}", timeStr)
                        Clock.System.now()
                    }
                } else {
                    Clock.System.now()
                }

            // 매도호가 10단계 (ASKP1~ASKP10, index 3~12) + 매도호가잔량 (ASKP_RSQN1~10, index 23~32)
            val asks =
                (0 until LEVELS).mapNotNull { i ->
                    val priceStr = fields.getOrNull(IDX_ASKP_START + i)?.trim() ?: return@mapNotNull null
                    val qtyStr = fields.getOrNull(IDX_ASKP_RSQN_START + i)?.trim() ?: "0"
                    val price = priceStr.ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val qty = qtyStr.ifBlank { "0" }.toLongOrNull() ?: 0L
                    if (price == BigDecimal.ZERO) null else PriceLevel(price = price, quantity = qty)
                }

            // 매수호가 10단계 (BIDP1~BIDP10, index 13~22) + 매수호가잔량 (BIDP_RSQN1~10, index 33~42)
            val bids =
                (0 until LEVELS).mapNotNull { i ->
                    val priceStr = fields.getOrNull(IDX_BIDP_START + i)?.trim() ?: return@mapNotNull null
                    val qtyStr = fields.getOrNull(IDX_BIDP_RSQN_START + i)?.trim() ?: "0"
                    val price = priceStr.ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val qty = qtyStr.ifBlank { "0" }.toLongOrNull() ?: 0L
                    if (price == BigDecimal.ZERO) null else PriceLevel(price = price, quantity = qty)
                }

            OrderbookSnapshot(
                code = code,
                ts = ts,
                bids = bids,
                asks = asks,
                totalAskQuantity = fields.longAt(IDX_TOTAL_ASKP_RSQN),
                totalBidQuantity = fields.longAt(IDX_TOTAL_BIDP_RSQN),
                overtimeTotalAskQuantity = fields.longAt(IDX_OVTM_TOTAL_ASKP_RSQN),
                overtimeTotalBidQuantity = fields.longAt(IDX_OVTM_TOTAL_BIDP_RSQN),
                anticipatedPrice = fields.decimalAt(IDX_ANTC_CNPR),
                anticipatedQuantity = fields.longAt(IDX_ANTC_CNQN),
                anticipatedVolume = fields.longAt(IDX_ANTC_VOL),
                anticipatedChange = fields.decimalAt(IDX_ANTC_CNTG_VRSS),
                anticipatedChangeSign = fields.textAt(IDX_ANTC_CNTG_VRSS_SIGN),
                anticipatedChangeRate = fields.decimalAt(IDX_ANTC_CNTG_PRDY_CTRT),
                accumulatedVolume = fields.longAt(IDX_ACML_VOL),
                stockDealClassCode = fields.textAt(IDX_STCK_DEAL_CLS_CODE),
            )
        } catch (e: Exception) {
            log.error("H0UNASP0 파싱 실패: rawData={} error={}", rawData.take(100), e.message)
            null
        }
    }

    private fun List<String>.decimalAt(index: Int): BigDecimal? = textAt(index)?.toBigDecimalOrNull()

    private fun List<String>.longAt(index: Int): Long? = textAt(index)?.toLongOrNull()

    private fun List<String>.textAt(index: Int): String? = getOrNull(index)?.trim()?.ifBlank { null }
}
