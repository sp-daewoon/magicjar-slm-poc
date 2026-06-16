package kr.spdaewoon.magicjar.api.messaging.journal

import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * 매매일지 한국어 summary 합성기 — 실제 체결 복기장.
 *
 * 매매일지는 ORDER_FILLED 중심으로 사용한다.
 * 과거 row 호환을 위해 기존 이벤트 helper는 유지하지만 신규 listener는 체결만 쓴다.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class JournalSummaryComposer {
    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.KOREA)
    private val orderFilledQtyKeys = listOf("executedQty", "executedQuantity", "filledQuantity", "filledQty", "quantity")
    private val orderFilledPriceKeys = listOf("executedPrice", "filledPrice", "price")

    /**
     * ORDER_FILLED summary 합성.
     * @param name 종목명 (null이면 code 사용)
     * @param side "BUY" / "SELL"
     * @param qty 체결 수량
     * @param price 체결 단가
     */
    fun composeOrderFilled(
        name: String?,
        code: String,
        side: String,
        qty: BigDecimal,
        price: BigDecimal,
        strategyName: String? = null,
        realizedPnl: BigDecimal? = null,
    ): String {
        val displayName = name ?: code
        val sideKr = sideKorean(side)
        val qtyInt = qty.toLong()
        val priceStr = numberFormat.format(price)
        val strategyPart = strategyName?.takeIf { it.isNotBlank() }?.let { " · 전략: $it" } ?: ""
        val pnlPart = realizedPnl?.let { " · 실현손익 ${formatPnl(it)}원" } ?: ""
        return "체결: $displayName $sideKr ${qtyInt}주 @ ${priceStr}원$strategyPart$pnlPart"
    }

    /**
     * ORDER_REJECTED summary 합성.
     */
    fun composeOrderRejected(
        name: String?,
        code: String,
        side: String,
        qty: BigDecimal?,
        reason: String?,
    ): String {
        val displayName = name ?: code
        val sideKr = sideKorean(side)
        val qtyStr = qty?.let { "${it.toLong()}주" } ?: "미지정"
        val reasonStr = reason?.take(40) ?: "사유 불명"
        return "주문 거부: $displayName $sideKr $qtyStr — $reasonStr"
    }

    /**
     * ORDER_CANCELLED summary 합성.
     */
    fun composeOrderCancelled(
        name: String?,
        code: String,
        side: String,
        qty: BigDecimal?,
    ): String {
        val displayName = name ?: code
        val sideKr = sideKorean(side)
        val qtyStr = qty?.let { "${it.toLong()}주" } ?: "미지정"
        return "주문 취소: $displayName $sideKr $qtyStr"
    }

    /**
     * SIGNAL_GENERATED summary 합성.
     * @param strength 시그널 강도 (0~100)
     */
    fun composeSignalGenerated(
        name: String?,
        code: String,
        side: String,
        strength: Int,
    ): String {
        val displayName = name ?: code
        val sideKr = sideKorean(side)
        return "시그널: $displayName $sideKr 강도 $strength"
    }

    /**
     * RISK_VIOLATION summary 합성.
     * @param ruleType 리스크 규칙 타입 (POSITION_LIMIT · DAILY_LOSS · etc)
     * @param code 종목 코드 (nullable)
     */
    fun composeRiskViolation(
        ruleType: String,
        code: String?,
    ): String {
        val ruleKr = riskRuleKorean(ruleType)
        return if (code != null) "리스크 차단: $code $ruleKr" else "리스크 차단: $ruleKr"
    }

    /**
     * 이벤트 타입 + payload map 으로 summary 합성 (범용 진입점).
     */
    fun compose(
        eventType: JournalEventType,
        payload: Map<String, Any?>,
    ): String =
        when (eventType) {
            JournalEventType.ORDER_FILLED -> {
                val code = payload["code"]?.toString() ?: "미지정"
                val name = payload["instrumentName"]?.toString()?.takeIf { it.isNotBlank() && it != code }
                val side = payload["side"]?.toString() ?: "BUY"
                val qty = payloadBigDecimalAnyOf(payload, orderFilledQtyKeys) ?: BigDecimal.ONE
                val price = payloadBigDecimalAnyOf(payload, orderFilledPriceKeys) ?: BigDecimal.ZERO
                val strategyName =
                    payload["strategyDisplayName"]?.toString()
                        ?: payload["strategyNameKo"]?.toString()
                        ?: payload["strategyName"]?.toString()
                val realizedPnl = payloadBigDecimal(payload, "realizedPnl")
                composeOrderFilled(name, code, side, qty, price, strategyName, realizedPnl)
            }
            JournalEventType.ORDER_REJECTED -> {
                val name = payload["instrumentName"]?.toString()
                val code = payload["code"]?.toString() ?: "미지정"
                val side = payload["side"]?.toString() ?: "BUY"
                val qty = payloadBigDecimal(payload, "quantity")
                val reason = payload["rejectReason"]?.toString()
                composeOrderRejected(name, code, side, qty, reason)
            }
            JournalEventType.ORDER_CANCELLED -> {
                val name = payload["instrumentName"]?.toString()
                val code = payload["code"]?.toString() ?: "미지정"
                val side = payload["side"]?.toString() ?: "BUY"
                val qty = payloadBigDecimal(payload, "quantity")
                composeOrderCancelled(name, code, side, qty)
            }
            JournalEventType.SIGNAL_GENERATED -> {
                val name = payload["instrumentName"]?.toString()
                val code = payload["code"]?.toString() ?: "미지정"
                val side = payload["side"]?.toString() ?: "BUY"
                val strength = (payload["strength"] as? Number)?.toInt() ?: 0
                composeSignalGenerated(name, code, side, strength)
            }
            JournalEventType.RISK_VIOLATION -> {
                val ruleType = payload["ruleType"]?.toString() ?: "UNKNOWN"
                val code = payload["code"]?.toString()
                composeRiskViolation(ruleType, code)
            }
        }

    // --- private helpers ---

    private fun sideKorean(side: String): String =
        when (side.uppercase()) {
            "BUY" -> "매수"
            "SELL" -> "매도"
            else -> side
        }

    private fun riskRuleKorean(ruleType: String): String =
        when (ruleType.uppercase()) {
            "POSITION_LIMIT" -> "포지션 한도 초과"
            "DAILY_LOSS" -> "일일 손실 한도 초과"
            "CONCENTRATION_LIMIT" -> "집중도 한도 초과"
            "KILLSWITCH" -> "킬스위치 차단"
            else -> ruleType
        }

    private fun formatPnl(value: BigDecimal): String {
        val sign = if (value.signum() > 0) "+" else ""
        return sign + numberFormat.format(value)
    }

    private fun payloadBigDecimal(
        payload: Map<String, Any?>,
        key: String,
    ): BigDecimal? =
        when (val v = payload[key]) {
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> v.toBigDecimalOrNull()
            else -> null
        }

    private fun payloadBigDecimalAnyOf(
        payload: Map<String, Any?>,
        keys: List<String>,
    ): BigDecimal? = keys.firstNotNullOfOrNull { key -> payloadBigDecimal(payload, key) }
}
