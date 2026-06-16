package kr.spdaewoon.magicjar.domain.risk

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Risk 차단 이벤트의 사용자-facing 설명.
 *
 * RiskGatewayEvaluator 의 원본 reason/context 는 운영 디버깅용으로 보존하고,
 * 알림에는 리스크 설정 화면의 A/B/C/D 섹션명과 같은 단어를 사용한다.
 */
data class RiskAlertMessage(
    val title: String,
    val message: String,
    val formula: String? = null,
    val setting: String? = null,
    val action: String = DEFAULT_ACTION,
) {
    fun toPayloadFields(): Map<String, String?> =
        mapOf(
            "alertTitle" to title,
            "alertMessage" to message,
            "alertFormula" to formula,
            "alertSetting" to setting,
            "alertAction" to action,
        )

    companion object {
        const val DEFAULT_ACTION = "리스크 설정과 전략 신호 빈도를 확인한 뒤 필요하면 전략을 수정하거나 한도를 조정하세요."

        fun from(
            rule: RiskRule,
            reason: String?,
            context: Map<String, Any?> = emptyMap(),
            code: String? = null,
        ): RiskAlertMessage =
            when (rule) {
                RiskRule.POSITION_LIMIT -> positionLimit(context, code)
                RiskRule.DAILY_LOSS_LIMIT -> dailyLossLimit(context)
                RiskRule.MAX_CONCURRENT_POSITIONS -> maxConcurrentPositions(reason, context)
                RiskRule.TRADE_FREQUENCY_MINUTE -> tradeFrequencyMinute(reason, context)
                RiskRule.TRADE_FREQUENCY_DAY -> tradeFrequencyDay(reason, context)
                RiskRule.KILL_SWITCH_ON ->
                    RiskAlertMessage(
                        title = "KillSwitch ON - 모든 자동 주문 차단",
                        message = "KillSwitch가 켜져 있어 신규 자동 주문을 내보내지 않았습니다.",
                        formula = "KillSwitch = ON",
                        setting = "시스템 설정 - KillSwitch",
                        action = "KIS 잔고와 최근 주문을 확인한 뒤 KillSwitch를 직접 해제하세요.",
                    )
                RiskRule.SELL_WITHOUT_POSITION ->
                    RiskAlertMessage(
                        title = "보유 수량 없는 매도 신호 차단",
                        message = "${codeLabel(code, context)} 보유 수량이 0주라 매도 주문을 내보내지 않았습니다.",
                        formula = "현재 보유 수량 0주 <= 0주",
                        setting = "포지션 정합성 가드",
                    )
                RiskRule.SELL_QUANTITY_UNAVAILABLE ->
                    sellQuantityUnavailable(context, code)
                RiskRule.INVALID_SIGNAL_PRICE ->
                    RiskAlertMessage(
                        title = "비정상 신호 가격 차단",
                        message = "신호 가격이 0 이하라 주문 수량과 한도를 계산할 수 없습니다.",
                        formula = "신호 가격 ${money(contextText(context, "priceAtSignal"))} <= 0원",
                        setting = "시그널 가격 유효성 가드",
                    )
                RiskRule.RISK_PLAN_REQUIRED ->
                    RiskAlertMessage(
                        title = "손절/익절 RiskPlan 미설정 - 매수 주문 차단",
                        message = "${codeLabel(code, context)} 자동매매 매수 전에 손절가와 익절가를 먼저 설정해야 합니다.",
                        formula = "RiskPlan(stopLossPct, takeProfitPct) 모두 필요",
                        setting = "전략 자동매매 RiskPlan",
                        action = "전략 빌더에서 손절 기준과 익절 기준을 설정한 뒤 다시 자동매매를 시작하세요.",
                    )
                RiskRule.INVALID_ORDER_INTENT ->
                    RiskAlertMessage(
                        title = "주문 수량 계산 결과 0주 차단",
                        message = "리스크 한도를 적용한 뒤 주문 가능 수량이 0주라 주문을 만들지 않았습니다.",
                        formula = "계산 주문 수량 ${contextText(context, "quantity")}주 <= 0주",
                        setting = "주문 의도 유효성 가드",
                    )
                RiskRule.KIS_EXECUTION_SYNC_STALE ->
                    RiskAlertMessage(
                        title = "KIS 체결 동기화 미완료 차단",
                        message = "실계좌 체결 내역 동기화가 완료되지 않아 자동 주문을 보류했습니다.",
                        formula = "KIS 체결 동기화 상태 != 완료",
                        setting = "실계좌 정합성 가드",
                        action = "체결 동기화 상태와 KIS 계좌 연결을 확인한 뒤 다시 실행하세요.",
                    )
                RiskRule.USER_DISABLED ->
                    RiskAlertMessage(
                        title = "전략 비활성 상태 차단",
                        message = "전략이 비활성 상태라 신규 자동 주문을 내보내지 않았습니다.",
                        formula = "전략 상태 != ACTIVE",
                        setting = "전략 상태 가드",
                    )
            }

        private fun positionLimit(
            context: Map<String, Any?>,
            code: String?,
        ): RiskAlertMessage {
            val unitCost = money(contextText(context, "unitCost"))
            val remaining = money(contextText(context, "remainingBudget"))
            val budget = money(contextText(context, "budget"))
            val existing = money(contextText(context, "existingExposure"))
            val absLimit = money(contextText(context, "absLimit"))
            val percentBudget = money(contextText(context, "percentBudget"))
            val capital = money(contextText(context, "currentCapital"))
            val percent = percent(contextText(context, "maxPositionPercent"))
            val formula =
                if (hasContext(context, "unitCost", "remainingBudget")) {
                    "신호 단가 $unitCost > 남은 종목 한도 $remaining\n" +
                        "종목 한도 = min(절대 한도 $absLimit, 총 자산 $capital x 비율 한도 $percent = $percentBudget) = $budget\n" +
                        "남은 종목 한도 = $budget - 기존 노출 $existing = $remaining"
                } else {
                    "종목당 최대 투자금액 = min(절대 한도, 총 자산 x 비율 한도)"
                }
            return RiskAlertMessage(
                title = "A. 포지션 한도 초과 - 종목당 최대 투자금액 차단",
                message = "${codeLabel(code, context)} 주문이 종목당 최대 투자금액을 넘어서 자동 주문을 내보내지 않았습니다.",
                formula = formula,
                setting = "A. 포지션 한도 - 절대 한도와 비율 한도 중 작은 값 적용",
                action = "종목당 투자 한도를 높이거나, 신호 가격/보유 수량/총 자산 기준을 확인하세요.",
            )
        }

        private fun sellQuantityUnavailable(
            context: Map<String, Any?>,
            code: String?,
        ): RiskAlertMessage {
            if (contextText(context, "source") == "KIS_BALANCE") {
                val reasonCode = contextText(context, "reasonCode")
                if (reasonCode.startsWith("KIS_BALANCE_LOOKUP_FAILED") || reasonCode == "KIS_BALANCE_LOOKUP_UNAVAILABLE") {
                    return RiskAlertMessage(
                        title = "KIS 잔고 조회 실패 - 매도 주문 보류",
                        message = "${codeLabel(code, context)} KIS 잔고를 확인하지 못해 매도 주문을 내보내지 않았습니다.",
                        formula = "KIS 잔고 조회 상태 = 실패, 주문 예정 수량 ${contextText(context, "requestedQty")}주",
                        setting = "KIS 잔고 정합성 가드",
                        action = "KIS rate limit 또는 계좌 연결 상태를 확인하세요. 실제 보유 수량 부족으로 확정된 알림은 아닙니다.",
                    )
                }
                return RiskAlertMessage(
                    title = "KIS 잔고 매도 가능 수량 부족",
                    message = "${codeLabel(code, context)} KIS 계좌 보유 수량이 주문 수량보다 적어 매도 주문을 내보내지 않았습니다.",
                    formula =
                        "KIS 보유 수량 ${contextText(context, "kisHoldingQty")}주 < " +
                            "주문 예정 수량 ${contextText(context, "requestedQty")}주",
                    setting = "KIS 잔고 정합성 가드",
                    action = "KIS 잔고와 미체결 주문을 확인한 뒤 전략을 다시 실행하세요.",
                )
            }
            return RiskAlertMessage(
                title = "매도 가능 수량 부족 - 미체결 매도 주문 보호",
                message = "${codeLabel(code, context)} 기존 미체결 매도 주문이 있어 추가 매도 주문을 내보내지 않았습니다.",
                formula =
                    "매도 가능 수량 = 보유 ${contextText(context, "holdingQty")}주 - " +
                        "미체결 매도 ${contextText(context, "reservedSellQty")}주 = " +
                        "${contextText(context, "availableSellQty")}주",
                setting = "포지션 정합성 가드 - 미체결 주문 차감",
                action = "기존 미체결 주문 체결/취소 상태를 확인한 뒤 다시 실행하세요.",
            )
        }

        private fun dailyLossLimit(context: Map<String, Any?>): RiskAlertMessage {
            val limitType = contextText(context, "limitType")
            val pnl = contextText(context, "dailyRealizedPnl")
            val limit = contextText(context, "limit")
            val formula =
                if (limitType == "percent") {
                    val capital = money(contextText(context, "currentCapital"))
                    val pnlPct = percent(contextText(context, "pnlPercentAbs"))
                    "오늘 실현손실률 $pnlPct = |${money(pnl)}| / 총 자산 $capital x 100 >= 비율 DLL ${percent(limit)}"
                } else {
                    "오늘 실현손실 ${moneyAbs(pnl)} >= 절대 DLL ${money(limit)}"
                }
            return RiskAlertMessage(
                title = "B. 일일 손실 한도 초과 - 자동 주문 차단",
                message = "오늘 누적 손실이 설정한 DLL 한도에 도달해 신규 자동 주문을 내보내지 않았습니다.",
                formula = formula,
                setting = "B. 일일 손실 한도 (DLL)",
                action = "오늘 손익과 DLL 설정을 확인하세요. 연속 BLOCKED가 임계치에 도달하면 해당 전략의 신규 BUY가 차단됩니다.",
            )
        }

        private fun maxConcurrentPositions(
            reason: String?,
            context: Map<String, Any?>,
        ): RiskAlertMessage {
            val parsed = parseTwoNumbers(reason, CONCURRENT_REGEX)
            val current = contextText(context, "currentCount").ifBlank { parsed?.first ?: "?" }
            val limit = contextText(context, "limit").ifBlank { parsed?.second ?: "?" }
            return RiskAlertMessage(
                title = "C. 동시 보유 종목 한도 도달",
                message = "최대 보유 종목 수를 이미 채워서 추가 매수 주문을 내보내지 않았습니다.",
                formula = "현재 보유 종목 수 ${positionCount(current)} >= 최대 보유 종목 수 ${positionCount(limit)}",
                setting = "C. 동시 보유 종목 한도 - 최대 보유 종목 수",
            )
        }

        private fun tradeFrequencyMinute(
            reason: String?,
            context: Map<String, Any?>,
        ): RiskAlertMessage {
            val parsed = parseTwoNumbers(reason, MINUTE_REGEX)
            val recent = contextText(context, "recentTradesMinute").ifBlank { parsed?.first ?: "?" }
            val limit = contextText(context, "limit").ifBlank { parsed?.second ?: "?" }
            return RiskAlertMessage(
                title = "D. 거래 빈도 한도 도달 - 분당 최대 거래",
                message = "최근 1분 안에 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.",
                formula = "최근 1분 허용 주문 수 ${count(recent)} >= 분당 최대 거래 ${count(limit)}/분",
                setting = "D. 거래 빈도 한도 - 분당 최대 거래",
            )
        }

        private fun tradeFrequencyDay(
            reason: String?,
            context: Map<String, Any?>,
        ): RiskAlertMessage {
            val parsed = parseTwoNumbers(reason, DAY_REGEX)
            val recent = contextText(context, "recentTradesDay").ifBlank { parsed?.first ?: "?" }
            val limit = contextText(context, "limit").ifBlank { parsed?.second ?: "?" }
            return RiskAlertMessage(
                title = "D. 거래 빈도 한도 도달 - 일별 최대 거래",
                message = "오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.",
                formula = "오늘 허용 주문 수 ${count(recent)} >= 일별 최대 거래 ${count(limit)}/일",
                setting = "D. 거래 빈도 한도 - 일별 최대 거래",
            )
        }

        private fun contextText(
            context: Map<String, Any?>,
            key: String,
        ): String = context[key]?.toString().orEmpty()

        private fun hasContext(
            context: Map<String, Any?>,
            vararg keys: String,
        ): Boolean = keys.all { !contextText(context, it).isBlank() }

        private fun codeLabel(
            code: String?,
            context: Map<String, Any?>,
        ): String {
            val value = code ?: contextText(context, "code").takeIf { it.isNotBlank() }
            return value?.let { "종목 $it" } ?: "해당 종목"
        }

        private fun money(value: String): String {
            val normalized = value.toBigDecimalOrNull() ?: return if (value.isBlank()) "?원" else "${value}원"
            return "${formatNumber(normalized)}원"
        }

        private fun moneyAbs(value: String): String {
            val normalized = value.toBigDecimalOrNull()?.abs() ?: return money(value)
            return "${formatNumber(normalized)}원"
        }

        private fun percent(value: String): String {
            val normalized = value.toBigDecimalOrNull() ?: return if (value.isBlank()) "?%" else "$value%"
            return "${formatNumber(normalized)}%"
        }

        private fun count(value: String): String {
            val normalized = value.toBigDecimalOrNull() ?: return if (value.isBlank()) "?회" else "${value}회"
            return "${formatNumber(normalized)}회"
        }

        private fun positionCount(value: String): String {
            val normalized = value.toBigDecimalOrNull() ?: return if (value.isBlank()) "?종목" else "${value}종목"
            return "${formatNumber(normalized)}종목"
        }

        private fun formatNumber(value: BigDecimal): String {
            val formatter = DecimalFormat("#,##0.####", DecimalFormatSymbols(Locale.KOREA))
            return formatter.format(value.stripTrailingZeros())
        }

        private fun parseTwoNumbers(
            reason: String?,
            regex: Regex,
        ): Pair<String, String>? {
            val match = reason?.let { regex.find(it) } ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }

        private val DAY_REGEX = Regex("""Strategy daily trade count (\d+) >= limit (\d+)""")
        private val MINUTE_REGEX = Regex("""Strategy minute trade count (\d+) >= limit (\d+)""")
        private val CONCURRENT_REGEX = Regex("""Concurrent positions (\d+) >= limit (\d+)""")
    }
}
