package kr.spdaewoon.magicjar.strategy.scalein

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import java.math.BigDecimal

/**
 * 시뮬레이션(리플레이·백테스트) 공유 — [ScaleInExitCalculator] 단일 함수를 호출해 가상 lot 액션을 산출한다 (분할매수 R3 · spec §6-3 LOCK).
 *
 * 라이브(RealtimeSignalGenerator)·리플레이(ScenarioReplayService 가상 ledger)·백테스트(JvmBacktestEngine)가
 * **동일 [ScaleInExitCalculator.evaluate]** 를 통과하도록 묶는 얇은 어댑터다. 시뮬레이션 호출부는 자신의
 * 가상 포지션(평단·filledLots·executedExitStages)을 [VirtualStrategyPosition] 으로 넘기고, 본 함수는 그걸
 * [StrategyPosition] 으로 감싸 calculator에 위임한 뒤 sealed 결정을 그대로 돌려준다.
 *
 * ## 순수성 (LOCK)
 * - calculator와 동일하게 Spring·JPA·IO 의존 0. 동일 입력 → 동일 출력(백테스트 재현성).
 * - 가상 포지션의 `userId`·`strategyId`·timestamp는 calculator가 읽지 않으므로 placeholder를 주입한다.
 *
 * ## 하위호환 (zero-regression)
 * - `scaleIn == null` AND `partialExits` null/empty → 호출부는 본 어댑터를 거치지 않고 현행 단일 경로를 그대로 탄다.
 *   (게이트 판정은 [isScaleInExitActive] 헬퍼로 호출부에서 수행 — calculator 진입 전 분기.)
 *
 * // AI 호출 0 — 룰 기반 순수 함수 (ADR 0023 §2-4 정합)
 */
object ScaleInExitSimulation {
    private val PLACEHOLDER_TS: Instant = Instant.fromEpochSeconds(0)

    /**
     * 시뮬레이션 호출부가 scale-in/exit 신규 경로를 타야 하는지 — calculator 진입 전 게이트.
     * 라이브 `RealtimeSignalGenerator.isScaleInExitActive`와 동일 판정(전략 설정 기준 — feature toggle은 라이브 전용).
     */
    fun isActive(
        scaleIn: ScaleInPlan?,
        partialExits: List<PartialExit>?,
    ): Boolean = scaleIn != null || !partialExits.isNullOrEmpty()

    /**
     * 가상 포지션 + 현재가 + BUY 시그널 발화 여부로 다음 lot 액션을 [ScaleInExitCalculator] 로 평가한다.
     *
     * @param position 가상 포지션(미보유면 null). 평단·filledLots·executedExitStages 권위.
     * @param currentPrice 현재 boundary close(리플레이) 또는 t 시점 평가가(백테스트).
     * @param scaleIn 분할 매수 정책(null = 단일 진입).
     * @param fixedStopLossPct 평단 대비 손절%(null = 손절 exit 미평가).
     * @param takeProfitPct 평단 대비 전량 익절%(null = 전량 익절 미평가).
     * @param partialExits 분할 익절 단계(null/empty = 분할 익절 미평가).
     * @param buySignalFired 이 boundary에서 진입 조건(BUY DSL)이 참이었는지 — 진입 평가 게이트(spec §5-1).
     * @param sliceBudgetQuantity 회차 수량(호출부가 종목예산×weight%/price로 산정). 0이면 호출부가 산정.
     * @param lastSliceFillPrice 직전 회차 체결가(PRICE_DROP_PCT 평가용).
     * @param lastSliceEpochSec 직전 회차 체결 epoch 초(minSliceInterval 평가용).
     * @param nowEpochSec 현재 epoch 초(minSliceInterval 평가용).
     */
    fun evaluate(
        position: VirtualStrategyPosition?,
        currentPrice: BigDecimal,
        scaleIn: ScaleInPlan?,
        fixedStopLossPct: BigDecimal?,
        takeProfitPct: BigDecimal?,
        partialExits: List<PartialExit>?,
        buySignalFired: Boolean,
        sliceBudgetQuantity: Int = 0,
        lastSliceFillPrice: BigDecimal? = null,
        lastSliceEpochSec: Long? = null,
        nowEpochSec: Long? = null,
    ): ScaleInExitDecision =
        ScaleInExitCalculator.evaluate(
            ScaleInExitInput(
                position = position?.toStrategyPosition(),
                currentPrice = currentPrice,
                scaleIn = scaleIn,
                fixedStopLossPct = fixedStopLossPct,
                takeProfitPct = takeProfitPct,
                partialExits = partialExits,
                buySignalFired = buySignalFired,
                sliceBudgetQuantity = sliceBudgetQuantity,
                lastSliceFillPrice = lastSliceFillPrice,
                lastSliceEpochSec = lastSliceEpochSec,
                nowEpochSec = nowEpochSec,
            ),
        )

    private fun VirtualStrategyPosition.toStrategyPosition(): StrategyPosition =
        StrategyPosition(
            userId = "SIMULATION",
            strategyId = 0L,
            code = code,
            quantity = quantity,
            avgPrice = avgPrice,
            filledLots = filledLots,
            executedExitStages = executedExitStages,
            openedAt = PLACEHOLDER_TS,
            updatedAt = PLACEHOLDER_TS,
        )
}

/**
 * 시뮬레이션 가상 포지션 — calculator가 읽는 lot 상태만 담는 경량 값(리플레이 ledger·백테스트 openPosition 공용).
 * 실 [StrategyPosition] 의 id/userId/strategyId/timestamp는 시뮬레이션에 무의미하므로 제외한다.
 */
data class VirtualStrategyPosition(
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val filledLots: Int = 0,
    val executedExitStages: Set<Int> = emptySet(),
)
