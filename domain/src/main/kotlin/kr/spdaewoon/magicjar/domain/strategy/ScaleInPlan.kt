package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * 분할 매수(scale-in) 정책 — spec §3-2 · ADR 0111 §2-1.
 *
 * 전략당 진입을 N회로 나누고(`totalSlices`), 회당 비중(`sliceWeights`)을 조절한다.
 * scale-in 미설정 전략([StrategyDefinition.scaleIn] == null)은 현행 1회 진입 그대로(하위호환 LOCK).
 *
 * 이 클래스는 **순수 도메인** — Spring·JPA·Jackson 어노테이션 import 절대 금지(헥사고날 정합).
 * 단 jackson이 별도 어노테이션 없이 직렬화할 수 있도록 평탄한 데이터 클래스 형태를 유지한다(R2 영속 wiring).
 *
 * ## 비중(`sliceWeights`) 의미 (spec §3-2)
 * 비중은 **전략 종목 예산(RiskConfig POSITION_LIMIT budget)에 대한 분배**다 — budget을 넘기지 않는다.
 * 회차 수량 = `floor(종목예산 × sliceWeight[filledLots]% / priceAtSignal)`(산정은 R2 [ScaleInExitCalculator]).
 * `null`이면 `100 / totalSlices` 균등 분할(사용자 결재 default, 2026-06-05).
 *
 * ## 진행도 (spec §3-2 · R2 책임)
 * `StrategyPosition.filledLots < totalSlices`일 때만 추가 진입.
 * `filledLots == totalSlices`면 scale-in 완료(이후 BUY 시그널 무시 — 평단 오염 방지).
 *
 * ## strategy 테이블 `scale_in_config` JSONB 직렬화 예시 (V66 · wiring은 R2)
 * ```json
 * {
 *   "totalSlices": 3,
 *   "sliceWeights": [50, 30, 20],
 *   "reentryTrigger": "PRICE_DROP_PCT",
 *   "priceDropPct": -3.0,
 *   "minSliceIntervalSec": 120
 * }
 * ```
 * 균등 분할(`sliceWeights` 생략) 예시:
 * ```json
 * { "totalSlices": 3, "reentryTrigger": "SIGNAL_REPEAT", "minSliceIntervalSec": 60 }
 * ```
 *
 * @param totalSlices 분할 회수 N. 사용자 결재 cap = **1..10**(2026-06-05). 1이면 단일 진입(현행 동작).
 * @param sliceWeights 회당 비중%(합 = 100±0.01). null이면 균등 분할(100/totalSlices). 명시 시 개수 = totalSlices.
 * @param reentryTrigger 추가 진입 조건 (default [ReentryTrigger.SIGNAL_REPEAT]).
 * @param priceDropPct [ReentryTrigger.PRICE_DROP_PCT] 시 직전 회차 체결가 대비 하락폭%(음수, 예: -3.0). 그 외 트리거에선 null.
 * @param minSliceIntervalSec 회차 간 최소 간격(초) — 과다 진입 방지. default 60. null이면 default 적용(R2).
 */
data class ScaleInPlan(
    val totalSlices: Int,
    val sliceWeights: List<BigDecimal>? = null,
    val reentryTrigger: ReentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
    val priceDropPct: BigDecimal? = null,
    val minSliceIntervalSec: Int? = ScaleInLimits.DEFAULT_MIN_SLICE_INTERVAL_SEC,
) {
    init {
        require(totalSlices in ScaleInLimits.MIN_SLICES..ScaleInLimits.MAX_SLICES) {
            "totalSlices must be in ${ScaleInLimits.MIN_SLICES}..${ScaleInLimits.MAX_SLICES}: $totalSlices"
        }
        sliceWeights?.let { weights ->
            require(weights.size == totalSlices) {
                "sliceWeights size must equal totalSlices: weights=${weights.size}, totalSlices=$totalSlices"
            }
            require(weights.all { it > BigDecimal.ZERO }) {
                "each sliceWeight must be positive: $weights"
            }
            val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
            require((sum - ScaleInLimits.WEIGHT_TOTAL).abs() <= ScaleInLimits.WEIGHT_SUM_TOLERANCE) {
                "sliceWeights must sum to 100 (±${ScaleInLimits.WEIGHT_SUM_TOLERANCE}): sum=$sum"
            }
        }
        if (reentryTrigger == ReentryTrigger.PRICE_DROP_PCT) {
            val drop =
                requireNotNull(priceDropPct) {
                    "priceDropPct is required when reentryTrigger=PRICE_DROP_PCT"
                }
            require(drop < BigDecimal.ZERO) {
                "priceDropPct must be negative (a price drop): $drop"
            }
        }
        minSliceIntervalSec?.let {
            require(it >= 0) { "minSliceIntervalSec must be >= 0: $it" }
        }
    }

    /**
     * 회차별 비중% 리스트로 해석한다. `sliceWeights`가 null이면 `100/totalSlices` 균등 분배.
     * 균등 분배 시 나머지(반올림 오차)는 마지막 회차에 흡수해 합이 정확히 100이 되도록 한다.
     * (수량 산정의 base — 실제 수량 floor 적용은 R2 [ScaleInExitCalculator] 책임.)
     */
    fun resolvedWeights(): List<BigDecimal> {
        sliceWeights?.let { return it }
        val even = ScaleInLimits.WEIGHT_TOTAL.divide(BigDecimal(totalSlices), 4, java.math.RoundingMode.DOWN)
        val head = List(totalSlices - 1) { even }
        val last = ScaleInLimits.WEIGHT_TOTAL - even.multiply(BigDecimal(totalSlices - 1))
        return head + last
    }
}

/**
 * 추가 진입(재진입) 트리거 — spec §3-2 · ADR 0111 §2-1.
 *
 * scale-in 추가 회차를 무엇으로 발화할지 결정한다. DSL 신규 연산자는 도입하지 않는다(spec §6-4 LOCK).
 */
enum class ReentryTrigger {
    /** BUY 시그널이 다시 발화하면 다음 회차 진입 (default — 기존 BUY DSL 재발화 재사용). */
    SIGNAL_REPEAT,

    /** 직전 회차 체결가 대비 [ScaleInPlan.priceDropPct]% 하락 시 다음 회차 진입 (averaging down). */
    PRICE_DROP_PCT,
}

/**
 * 분할 익절(partial take-profit) 단계 — spec §3-3 · ADR 0111 §2-2.
 *
 * 평단([StrategyPosition.avgPrice]) 대비 [triggerProfitPct] 수익률 도달 시 보유 수량의 [exitWeightPct]%만 청산한다.
 * 단계별 1회만 발화(중복 SELL 방지)는 R2 엔진 + `StrategyPosition.executedExitStages` 책임(spec §3-1).
 * 미설정([StrategyDefinition.partialExits] == null)이면 현행 전량 익절 그대로(하위호환 LOCK).
 *
 * 순수 도메인 — Spring·JPA·Jackson 어노테이션 import 금지. jackson 직렬화 호환 평탄 데이터 클래스.
 *
 * ## strategy 테이블 `partial_exits` JSONB 직렬화 예시 (V66 · wiring은 R2)
 * ```json
 * [
 *   { "triggerProfitPct": 3.0, "exitWeightPct": 50 },
 *   { "triggerProfitPct": 7.0, "exitWeightPct": 30 }
 * ]
 * ```
 *
 * @param triggerProfitPct 평단 대비 이 수익률%(양수) 도달 시 발화.
 * @param exitWeightPct 보유 수량의 이 비율%(0 초과 ~ 100 이하) 청산. 단계 weight 합 ≤ 100([ExitLimits.validatePartialExits]).
 */
data class PartialExit(
    val triggerProfitPct: BigDecimal,
    val exitWeightPct: BigDecimal,
) {
    init {
        require(triggerProfitPct > BigDecimal.ZERO) {
            "triggerProfitPct must be positive: $triggerProfitPct"
        }
        require(exitWeightPct > BigDecimal.ZERO && exitWeightPct <= ExitLimits.WEIGHT_TOTAL) {
            "exitWeightPct must be in (0, ${ExitLimits.WEIGHT_TOTAL}]: $exitWeightPct"
        }
    }
}

/**
 * scale-in 사용자 결재 검증 상수 (2026-06-05 확정 — spec §6-4 · ADR 0111 §2-1).
 */
object ScaleInLimits {
    /** 분할 회수 최소 (1 = 단일 진입 = 현행). */
    const val MIN_SLICES: Int = 1

    /** 분할 회수 cap (사용자 결재 2026-06-05 = 10). */
    const val MAX_SLICES: Int = 10

    /** 회차 간 최소 간격 default(초). */
    const val DEFAULT_MIN_SLICE_INTERVAL_SEC: Int = 60

    /** 비중 합 목표값(%). */
    val WEIGHT_TOTAL: BigDecimal = BigDecimal("100")

    /** 비중 합 허용 오차(±). 사용자 결재 = 100±0.01. */
    val WEIGHT_SUM_TOLERANCE: BigDecimal = BigDecimal("0.01")
}

/**
 * 분할 익절 사용자 결재 검증 상수 (2026-06-05 확정 — spec §3-3 · ADR 0111 §2-2).
 */
object ExitLimits {
    /** 분할 익절 단계 cap (사용자 결재 2026-06-05 = 5). */
    const val MAX_PARTIAL_EXIT_STAGES: Int = 5

    /** 익절 비중 합 상한(%). 분할 익절 weight 합 ≤ 100. */
    val WEIGHT_TOTAL: BigDecimal = BigDecimal("100")

    /**
     * 분할 익절 단계 리스트를 검증한다 — 단계 cap(≤5) + weight 합 ≤ 100.
     * [StrategyDefinition.init]에서 호출(개별 [PartialExit]의 단일 검증은 PartialExit.init 책임).
     *
     * @throws IllegalArgumentException 단계 cap 초과 또는 weight 합 > 100
     */
    fun validatePartialExits(partialExits: List<PartialExit>) {
        require(partialExits.isNotEmpty()) {
            "partialExits must be non-empty when present (use null to disable)"
        }
        require(partialExits.size <= MAX_PARTIAL_EXIT_STAGES) {
            "partialExits must have at most $MAX_PARTIAL_EXIT_STAGES stages: ${partialExits.size}"
        }
        val sum = partialExits.fold(BigDecimal.ZERO) { acc, e -> acc + e.exitWeightPct }
        require(sum <= WEIGHT_TOTAL) {
            "partialExits exitWeightPct sum must be <= $WEIGHT_TOTAL: sum=$sum"
        }
    }
}
