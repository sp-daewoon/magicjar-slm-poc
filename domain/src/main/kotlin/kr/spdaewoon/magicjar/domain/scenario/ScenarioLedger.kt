package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.RoundingMode

data class ScenarioPositionSeed(
    val code: String,
    val quantity: Long,
    val averagePrice: BigDecimal,
) {
    init {
        require(code.matches(CODE_PATTERN)) { "code must be 6 digits: $code" }
        require(quantity >= 0) { "quantity must be >= 0: $quantity" }
        require(averagePrice >= BigDecimal.ZERO) { "averagePrice must be >= 0: $averagePrice" }
    }

    companion object {
        private val CODE_PATTERN = Regex("\\d{6}")
    }
}

data class ScenarioPositionSnapshot(
    val code: String,
    val quantity: Long,
    val averagePrice: BigDecimal,
    val updatedAt: Instant,
    /**
     * scale-in 진행도 — 체결된 분할 회차 수 (분할매수 R3 — 가상 StrategyPosition.filledLots 대칭).
     * scale-in 미사용 리플레이에서도 매 BUY 체결마다 +1 누적된다(기존 단일 진입은 1로 수렴 — 하위호환).
     * 전량 청산(quantity==0) 시 0으로 reset.
     */
    val filledLots: Int = 0,
    /**
     * 이미 발화·체결된 PartialExit 단계 인덱스 집합 (분할 익절 중복 발화 방지 — StrategyPosition.executedExitStages 대칭).
     * 전량 청산 시 빈 집합으로 reset. 부분 청산만으로는 reset 안 함(단계 단조 진행 보존).
     */
    val executedExitStages: Set<Int> = emptySet(),
)

data class ScenarioLedgerSnapshot(
    val cash: BigDecimal,
    val positions: List<ScenarioPositionSnapshot>,
)

class ScenarioLedger(
    initialCash: BigDecimal,
    initialPositions: List<ScenarioPositionSeed>,
) {
    private var cash: BigDecimal = initialCash
    private val positions: MutableMap<String, MutablePosition> =
        initialPositions
            .filter { it.quantity > 0 }
            .associate { seed ->
                // seed 포지션은 "이미 1회 진입"으로 간주(filledLots=1) — 가상 평단 기준 exit/scale-in 평가 base.
                seed.code to MutablePosition(seed.quantity, seed.averagePrice, filledLots = 1)
            }.toMutableMap()

    init {
        require(initialCash >= BigDecimal.ZERO) { "initialCash must be >= 0: $initialCash" }
    }

    fun cash(): BigDecimal = cash

    fun availableQuantity(code: String): Long = positions[code]?.quantity ?: 0L

    fun maxAffordableQuantity(price: BigDecimal): Long {
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        return cash.divide(price, 0, RoundingMode.DOWN).toLong()
    }

    fun buy(
        code: String,
        quantity: Long,
        price: BigDecimal,
        at: Instant,
    ): ScenarioPositionSnapshot {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        val cost = price.multiply(BigDecimal.valueOf(quantity))
        require(cash >= cost) { "scenario cash is insufficient: cash=$cash, cost=$cost" }

        val current = positions[code]
        val next =
            if (current == null) {
                // 신규 진입 = 첫 회차(filledLots=1). 가상 StrategyPosition 평단 갱신 규칙과 동일.
                MutablePosition(quantity = quantity, averagePrice = price, filledLots = 1)
            } else {
                val currentCost = current.averagePrice.multiply(BigDecimal.valueOf(current.quantity))
                val nextQuantity = current.quantity + quantity
                val nextAverage =
                    currentCost
                        .add(cost)
                        .divide(BigDecimal.valueOf(nextQuantity), 6, RoundingMode.HALF_UP)
                // 추가 회차 진입 = filledLots += 1 (StrategyPosition 평단/진행도 갱신 규칙 대칭).
                MutablePosition(
                    quantity = nextQuantity,
                    averagePrice = nextAverage,
                    filledLots = current.filledLots + 1,
                    executedExitStages = current.executedExitStages,
                )
            }
        positions[code] = next
        cash = cash.subtract(cost)
        return next.toSnapshot(code, at)
    }

    fun sell(
        code: String,
        quantity: Long,
        price: BigDecimal,
        at: Instant,
    ): ScenarioPositionSnapshot {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        val current = positions[code]
        require(current != null && current.quantity >= quantity) {
            "scenario position quantity is insufficient: code=$code, holding=${current?.quantity ?: 0}, requested=$quantity"
        }

        val nextQuantity = current.quantity - quantity
        cash = cash.add(price.multiply(BigDecimal.valueOf(quantity)))
        return if (nextQuantity == 0L) {
            // 전량 청산 — 행 제거. 가상 StrategyPosition reset(filledLots=0·executedExitStages={})과 의미 동일.
            positions.remove(code)
            ScenarioPositionSnapshot(code = code, quantity = 0, averagePrice = BigDecimal.ZERO, updatedAt = at)
        } else {
            // 부분 청산 — 평단·진행도·발화 단계 보존(단조 진행 LOCK).
            val next =
                MutablePosition(
                    quantity = nextQuantity,
                    averagePrice = current.averagePrice,
                    filledLots = current.filledLots,
                    executedExitStages = current.executedExitStages,
                )
            positions[code] = next
            next.toSnapshot(code, at)
        }
    }

    /**
     * 분할 익절 단계 발화를 기록한다 (가상 StrategyPosition.executedExitStages += stageIndex).
     * 부분 청산 체결 직후 호출 — 같은 단계가 가격이 trigger 위에 머무는 동안 재발화하지 않도록 한다.
     * 보유분이 없거나 이미 기록된 단계면 멱등(no-op).
     */
    fun recordExitStage(
        code: String,
        stageIndex: Int,
    ) {
        val current = positions[code] ?: return
        if (stageIndex in current.executedExitStages) return
        positions[code] = current.copy(executedExitStages = current.executedExitStages + stageIndex)
    }

    fun snapshot(at: Instant): ScenarioLedgerSnapshot =
        ScenarioLedgerSnapshot(
            cash = cash,
            positions = positions.map { (code, position) -> position.toSnapshot(code, at) }.sortedBy { it.code },
        )

    private data class MutablePosition(
        val quantity: Long,
        val averagePrice: BigDecimal,
        val filledLots: Int = 0,
        val executedExitStages: Set<Int> = emptySet(),
    ) {
        fun toSnapshot(
            code: String,
            at: Instant,
        ): ScenarioPositionSnapshot =
            ScenarioPositionSnapshot(
                code = code,
                quantity = quantity,
                averagePrice = averagePrice,
                updatedAt = at,
                filledLots = filledLots,
                executedExitStages = executedExitStages,
            )
    }
}
