package kr.spdaewoon.magicjar.domain.scenario

import java.math.BigDecimal

enum class ScenarioSignalSide {
    BUY,
    SELL,
    HOLD,
}

enum class ScenarioOrderPriceType {
    MARKET,
    LIMIT,
}

data class ScenarioSignalDecision(
    val side: ScenarioSignalSide,
    val quantity: Long? = null,
    val priceType: ScenarioOrderPriceType = ScenarioOrderPriceType.MARKET,
    val limitPrice: BigDecimal? = null,
    val reason: String? = null,
    /**
     * 이 SELL이 분할 익절(PARTIAL_TAKE_PROFIT)이면 발화 단계 인덱스 (분할매수 R3).
     * 비-null이면 체결 확정 직후 [ScenarioLedger.recordExitStage]로 마킹 → 같은 단계 중복 발화 차단.
     * scale-in/분할 익절 미사용 리플레이는 항상 null(하위호환 — 기존 동작 보존).
     */
    val partialExitStageIndex: Int? = null,
) {
    init {
        require(quantity == null || quantity > 0) { "quantity must be positive when present: $quantity" }
        require(limitPrice == null || limitPrice > BigDecimal.ZERO) { "limitPrice must be positive when present: $limitPrice" }
        require(priceType == ScenarioOrderPriceType.MARKET || limitPrice != null) {
            "limitPrice is required for LIMIT scenario orders"
        }
        require(partialExitStageIndex == null || (side == ScenarioSignalSide.SELL && partialExitStageIndex >= 0)) {
            "partialExitStageIndex is only valid for SELL with index >= 0: side=$side, index=$partialExitStageIndex"
        }
    }

    companion object {
        fun hold(reason: String? = null): ScenarioSignalDecision = ScenarioSignalDecision(side = ScenarioSignalSide.HOLD, reason = reason)

        fun buy(
            quantity: Long? = null,
            reason: String? = null,
        ): ScenarioSignalDecision =
            ScenarioSignalDecision(
                side = ScenarioSignalSide.BUY,
                quantity = quantity,
                reason = reason,
            )

        fun sell(
            quantity: Long? = null,
            reason: String? = null,
        ): ScenarioSignalDecision =
            ScenarioSignalDecision(
                side = ScenarioSignalSide.SELL,
                quantity = quantity,
                reason = reason,
            )
    }
}

sealed class ScenarioRiskDecision {
    object Allowed : ScenarioRiskDecision()

    data class Blocked(
        val reasonCode: String,
        val message: String,
        val payload: Map<String, Any?> = emptyMap(),
    ) : ScenarioRiskDecision() {
        init {
            require(reasonCode.isNotBlank()) { "reasonCode must be non-blank" }
            require(message.isNotBlank()) { "message must be non-blank" }
        }
    }
}
