package kr.spdaewoon.magicjar.strategy.integrity

import kotlinx.datetime.Instant

/**
 * strategy_position 드리프트 자동 보정 산정 단일 순수 함수 — ADR 0115 §P1-1 (R1).
 *
 * `sum(strategy_position.quantity WHERE user+code)`(내부 leg ledger 합)이 `position.quantity`(KIS 잔고 권위)와
 * 어긋났을 때, **권위(position)에 맞춰 strategy_position을 어떻게 정렬할지**를 결정론적으로 산정한다.
 *
 * 2026-06-08 MOCK 알람 폭주 incident(strategy_position 7 vs position(KIS) 5)의 뿌리는
 * `verifyDrift`가 WARN만 발사하고 보정하지 않아 유령 2주가 손절 매도 폭주를 유발한 것이다(ADR 0115 §1).
 * 본 함수는 그 보정 "수량 산정"만 순수하게 책임진다. 실제 upsert·이벤트 발사·DB write는 R2 어댑터가 배선한다.
 *
 * ## 순수성 (LOCK — [kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitCalculator] · EntryPricePolicy 선례 1:1)
 * - Spring·JPA·Jackson·Clock·IO·KIS 의존 0. 입력([DriftCorrectionInput]) → 출력([DriftCorrectionResult]) 결정론.
 * - 권위수량·현재 leg 목록·시각 등 외부 상태는 **입력으로 주입**한다. 함수 내부에서 조회하지 않는다.
 * - 동일 입력 → 동일 출력(결정론 — 보정 재현성·다전략 차감 순서 안정).
 * - AI 호출 0 (룰 기반 자본 흐름 순수 함수 — ADR 0023 §2-4 정합).
 *
 * ## 보정 규칙 (ADR 0115 §P1-1 LOCK)
 * 1. **초과(strategySum > authority) → 내림(보정)**: 초과분을 leg에서 차감한다. 차감 순서는
 *    **`updatedAt` 최신 leg부터**(가장 최근 잘못 누적된 leg 우선 정정). 동일 `updatedAt`은 **`strategyId` 큰 쪽 먼저**
 *    (완전 결정론 tie-break). 각 leg는 0 미만으로 내리지 않고([Int.coerceAtLeast]), 초과분 소진 시 중단.
 * 2. **부족(strategySum < authority) → 보정하지 않음(WARN 유지)**: 없는 물량 인식 risk 회피.
 *    외부 HTS 매수·backfill 지연 등 정상 사유 가능 → [DriftCorrectionResult.WarnUnderflow]로 신호만.
 * 3. **일치(strategySum == authority) → [DriftCorrectionResult.NoChange]**.
 * 4. **하한 0**: 어떤 leg도 음수 quantity로 내리지 않는다.
 * 5. **realized_pnl 절대 미변경**: 보정은 수량 정렬일 뿐 손익 이벤트가 아니다. 본 함수는 **수량(newQuantity)만 반환**하며
 *    realized_pnl·avgPrice 등 다른 필드는 산정/반환하지 않는다(R2가 quantity==0 도달 시 reset 규칙을 적용). 줄인 수량은
 *    "체결되지 않았던 유령"이므로 손익 0(ADR 0115 §P1-1 — realized 보존 LOCK).
 *
 * ## 책임 경계 (R1)
 * - 본 함수는 **leg별 목표 수량**만 산정한다. quantity==0 도달 시 avgPrice/filledLots/executedExitStages reset은
 *   R2 어댑터가 [StrategyPositionLeg.willBeClosed] 신호를 보고 적용한다(applySell nextQty==0 분기 정합).
 * - 모드 무관(REAL/MOCK 동일 — position 권위는 두 모드 공통, ADR 0115 §P1-1).
 *
 * @see DriftCorrectionInput 입력 계약
 * @see DriftCorrectionResult 출력 계약(R2가 소비)
 */
object PositionDriftCorrection {
    /**
     * 드리프트 보정 산정 단일 진입점.
     *
     * @return 보정 비활성([DriftCorrectionInput.enabled]=false)이면 [DriftCorrectionResult.Disabled].
     *   초과면 [DriftCorrectionResult.Adjust](leg별 목표 수량), 부족이면 [DriftCorrectionResult.WarnUnderflow],
     *   일치면 [DriftCorrectionResult.NoChange].
     */
    fun evaluate(input: DriftCorrectionInput): DriftCorrectionResult {
        // 보정 토글 off(ENV) → 산정하지 않음. R2가 현행 WARN-only로 fallback.
        if (!input.enabled) {
            return DriftCorrectionResult.Disabled
        }

        val authority = input.authorityQuantity.coerceAtLeast(0)
        val strategySum = input.legs.sumOf { it.quantity }

        if (strategySum == authority) {
            return DriftCorrectionResult.NoChange
        }

        // 부족(외부 매수·backfill 지연 등) — 보정하지 않고 WARN 신호만. 없는 물량 인식 회피(LOCK).
        if (strategySum < authority) {
            return DriftCorrectionResult.WarnUnderflow(
                strategySum = strategySum,
                authorityQuantity = authority,
            )
        }

        // === 초과(strategySum > authority) → 초과분을 leg에서 내림 ===
        // 차감 순서: updatedAt 최신 우선 → 동일 시 strategyId 큰 쪽 우선(완전 결정론 tie-break).
        var excess = strategySum - authority
        val ordered =
            input.legs.sortedWith(
                compareByDescending<StrategyPositionLeg> { it.updatedAt }
                    .thenByDescending { it.strategyId },
            )

        val adjustments = mutableListOf<LegCorrection>()
        for (leg in ordered) {
            if (excess <= 0) break
            if (leg.quantity <= 0) continue
            val cut = minOf(excess, leg.quantity)
            val newQuantity = (leg.quantity - cut).coerceAtLeast(0)
            excess -= cut
            adjustments +=
                LegCorrection(
                    strategyId = leg.strategyId,
                    previousQuantity = leg.quantity,
                    newQuantity = newQuantity,
                )
        }

        return DriftCorrectionResult.Adjust(
            strategySum = strategySum,
            authorityQuantity = authority,
            corrections = adjustments.toList(),
        )
    }
}

/**
 * [PositionDriftCorrection.evaluate] 입력 — 모든 외부 상태 명시 주입(순수성 LOCK · EntryPriceInput 패턴 정합).
 *
 * @param code 종목 코드(audit·식별용 — 산정 로직엔 미사용, 결과 추적 메타).
 * @param authorityQuantity 권위 수량 = `position.quantity`(KIS 잔고 부트스트랩 ADR 0042 권위). 음수면 0으로 절상.
 * @param legs 동일 user+code 의 strategy_position leg 목록(다전략이면 N개, 단일이면 1개). 빈 목록이면 strategySum=0.
 * @param enabled 보정 활성 토글(ENV `magicjar.position.drift-auto-correct.enabled` 주입 — R2). false면 [DriftCorrectionResult.Disabled].
 *   순수 함수는 값 주입만 받고 ENV를 읽지 않는다(읽기는 R2 Spring). default [DriftCorrectionDefaults.AUTO_CORRECT_ENABLED].
 */
data class DriftCorrectionInput(
    val code: String,
    val authorityQuantity: Int,
    val legs: List<StrategyPositionLeg>,
    val enabled: Boolean = DriftCorrectionDefaults.AUTO_CORRECT_ENABLED,
)

/**
 * 보정 산정 대상 leg — strategy_position 1행의 보정에 필요한 최소 투영(순수 입력).
 *
 * 전체 [kr.spdaewoon.magicjar.domain.position.StrategyPosition] 대신 보정에 필요한 필드만 받아 순수성·테스트성을 높인다.
 * realized_pnl·avgPrice는 보정 산정에 불필요하므로 입력에 포함하지 않는다(realized 불변 LOCK — 함수는 수량만 다룬다).
 *
 * @param strategyId 전략 식별자(차감 결과 귀속 + tie-break 키).
 * @param quantity 현재 leg 보유 수량(≥0 전제).
 * @param updatedAt 마지막 갱신 시각 — 차감 순서(최신 우선) 기준.
 */
data class StrategyPositionLeg(
    val strategyId: Long,
    val quantity: Int,
    val updatedAt: Instant,
)

/**
 * [PositionDriftCorrection.evaluate] 출력 — sealed(보정 사유·동작 audit 명확 · EntryPriceDecision 패턴 정합).
 *
 * 어떤 결과도 자본을 직접 움직이지 않는다 — R2 어댑터가 [Adjust]면 leg별 upsert + `STRATEGY_POSITION_DRIFT_CORRECTED`
 * SystemEvent, [WarnUnderflow]면 현행 `STRATEGY_POSITION_DRIFT` WARN, [NoChange]/[Disabled]면 무동작.
 */
sealed interface DriftCorrectionResult {
    /** 보정 토글 off(ENV) — R2가 현행 WARN-only로 fallback. 산정하지 않음. */
    data object Disabled : DriftCorrectionResult

    /** strategySum == authority — 정합. 동작 없음. */
    data object NoChange : DriftCorrectionResult

    /**
     * 초과(strategySum > authority) — leg별 목표 수량으로 내림 보정.
     *
     * @param strategySum 보정 전 leg 수량 합.
     * @param authorityQuantity 권위(position) 수량 — 보정 후 strategySum 목표.
     * @param corrections 실제 수량이 바뀐 leg만 포함(목록 순서 = 차감 적용 순서 = updatedAt 최신 우선).
     *   `corrections.sumOf { it.newQuantity } + (변경 없는 leg 합) == authorityQuantity` 불변식 성립.
     */
    data class Adjust(
        val strategySum: Int,
        val authorityQuantity: Int,
        val corrections: List<LegCorrection>,
    ) : DriftCorrectionResult

    /**
     * 부족(strategySum < authority) — 보정하지 않음(WARN 유지). 없는 물량 인식 회피(LOCK).
     *
     * @param strategySum leg 수량 합(< authority).
     * @param authorityQuantity 권위 수량.
     */
    data class WarnUnderflow(
        val strategySum: Int,
        val authorityQuantity: Int,
    ) : DriftCorrectionResult
}

/**
 * leg 1행의 보정 결과 — R2가 이 strategyId leg을 newQuantity로 upsert.
 *
 * @param strategyId 보정 대상 전략.
 * @param previousQuantity 보정 전 수량(audit before/after — ADR 0115 §P1-1 감사 기록).
 * @param newQuantity 보정 후 목표 수량(≥0 — 하한 가드 적용됨). **수량만** — realized_pnl·avgPrice는 미포함(불변 LOCK).
 */
data class LegCorrection(
    val strategyId: Long,
    val previousQuantity: Int,
    val newQuantity: Int,
) {
    /** 보정으로 전량 청산(0 도달)되는 leg — R2가 avgPrice/filledLots/executedExitStages reset 적용 신호. */
    val willBeClosed: Boolean get() = newQuantity == 0
}
