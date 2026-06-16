package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * 백테스트 실행 요청 도메인 객체 (ADR 0056 §2-1 LOCK).
 *
 * 이 클래스는 도메인 모듈에만 위치하며 Spring·JPA·Kafka import 절대 금지 (헥사고날 정합).
 *
 * @param strategyId 평가할 전략 ID
 * @param universe 시뮬레이션 종목코드 리스트 (생존자 편향 보정은 universe resolver 별도)
 * @param interval 캔들 인터벌 ("1d" · "1m" · "5m" · "15m" · "1h")
 * @param from 시작일 (포함)
 * @param to 종료일 (포함)
 * @param initialCapital 초기 자본 (KRW)
 * @param taxModel 세금 모델 키 ("korean_default" · "none")
 * @param commissionModel 수수료 모델 키 ("kis_direct" · "none")
 * @param slippageModel 슬리피지 모델 키 ("fixed_0.05" · "none")
 * @param priceAdjustmentMode 가격 모드 (RAW · ADJUSTED)
 * @param sizingPolicy 포지션 사이징 정책 (default: AllCapital · ADR 0121 §2-A: FixedFractional 활성). copy로 overfit/sensitivity 자동 전파.
 * @param outOfSampleSplit IS/OOS 분할일 (ADR 0117 §2-6 · spec §3-R3 LOCK). **null이면 단일 run(현행 동작 무변경)**.
 *   지정 시 과최적화 탐지 orchestrator([kr.spdaewoon.magicjar.api.service.backtest.OverfitDetector])가 이 날짜를 경계로
 *   from~splitDate=In-Sample(학습)·splitDate~to=Out-of-Sample(검증) 2 run으로 분할 실행한다. from < splitDate ≤ to 이어야 한다.
 *   엔진 [run]은 이 필드를 **무시**한다(단일 run만 수행) — 분할은 전적으로 orchestrator 책임(코어 산식 무변경 LOCK).
 * @param exitParamOverride 청산 파라미터 1개 override (spec §3-R3 민감도 sweep 전용 · add-only). **null이면 전략 정의 그대로(현행)**.
 *   지정 시 엔진이 조회한 전략 정의의 손절%·익절%·트레일링% 중 1개를 이 값으로 치환해 시뮬레이션한다(DB write 0 — 메모리 override).
 *   민감도 분석([SensitivityRequest])이 그리드 각 점마다 이 필드만 바꿔 [run]을 반복 호출하는 용도.
 */
data class BacktestRequest(
    val strategyId: Long,
    val universe: List<String>,
    val interval: String,
    val from: LocalDate,
    val to: LocalDate,
    val initialCapital: BigDecimal,
    val taxModel: String = "korean_default",
    val commissionModel: String = "kis_direct",
    val slippageModel: String = "fixed_0.05",
    val priceAdjustmentMode: PriceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
    val sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
    val outOfSampleSplit: LocalDate? = null,
    val exitParamOverride: ExitParamOverride? = null,
) {
    init {
        require(from <= to) { "from must be <= to: from=$from, to=$to" }
        require(initialCapital > BigDecimal.ZERO) { "initialCapital must be positive: $initialCapital" }
        require(universe.isNotEmpty()) { "universe must be non-empty" }
        outOfSampleSplit?.let {
            require(it in from..to) { "outOfSampleSplit must be within [from, to]: split=$it, from=$from, to=$to" }
        }
    }
}

/**
 * 청산 파라미터 override (spec §3-R3 민감도 sweep 전용 · add-only).
 *
 * 민감도 분석은 전략의 **핵심 청산 파라미터 1개**를 그리드로 sweep해 수익률 분포·안정성을 측정한다.
 * 전략 정의는 DB 영속이므로, sweep 매 점마다 DB를 건드리지 않고 **메모리에서만** 해당 파라미터를 치환하기 위해
 * 이 객체를 [BacktestRequest.exitParamOverride]로 주입한다. 엔진은 조회한 [kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition]의
 * 대응 필드를 이 값으로 copy해 시뮬레이션한다(buy/sell DSL AST는 불변 — 청산 knob만 영향).
 *
 * 백테스트로 재현 가능한 candle-only knob(손절·익절·트레일링)만 대상이다(spec §3-R3 — 체결강도/CTTR 제외).
 *
 * @param target 치환 대상 파라미터
 * @param value 치환 값 (%) — 예: 손절 3.0 → fixedStopLoss=3.0%
 */
data class ExitParamOverride(
    val target: ExitParam,
    val value: BigDecimal,
)

/**
 * 민감도 sweep 대상 청산 파라미터 식별자 (spec §3-R3 `paramName` LOCK 정합).
 *
 * 식별자 문자열([paramName])은 [SensitivityRequest.paramName]·[OverfitDiagnostics] 리포트에 그대로 노출된다.
 */
enum class ExitParam(
    val paramName: String,
) {
    /** 고정 손절 비율 (%) — [kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition.fixedStopLoss]. */
    FIXED_STOP_LOSS("fixed_stop_loss_pct"),

    /** 익절 비율 (%) — [kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition.takeProfitPct]. */
    TAKE_PROFIT("take_profit_pct"),

    /** 트레일링 스탑 비율 (%) — [kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition.trailingStop]. */
    TRAILING_STOP("trailing_stop_pct"),

    ;

    companion object {
        /** [paramName] 문자열 → enum (대소문자 무시). 미일치 시 null. */
        fun fromParamName(name: String): ExitParam? = entries.firstOrNull { it.paramName.equals(name, ignoreCase = true) }
    }
}

/** 가격 조정 모드 — RAW(수정 전) · ADJUSTED(수정 주가) */
enum class PriceAdjustmentMode {
    RAW,
    ADJUSTED,
}

/**
 * 포지션 사이징 정책 (ADR 0056 §2-1 · ADR 0121 §2-A 정합).
 *
 * - [AllCapital] = 1포지션 = 보유 자본 100% (default · 기존 호출 회귀 0 LOCK).
 * - [FixedFractional] = 실전 RiskConfig 정합 고정 비율 슬롯 (ADR 0121 §2-A — 1차 승격).
 * - [Kelly] = post-MVP 보류.
 */
sealed class SizingPolicy {
    /** 전액 투입 — 매수 시 보유 자본 전액을 1종목에 투입 (1차 default) */
    object AllCapital : SizingPolicy()

    /**
     * 고정 비율 사이징 — 실전 RiskConfig 정합 (ADR 0121 §2-A LOCK).
     *
     * 1슬롯 = `min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞)`(고정 슬롯 — 복리 누적으로 슬롯이
     * 부풀어 노출이 100%로 수렴하는 것을 막기 위해 base는 `initialCapital` 고정). 이렇게 1회 진입의 자본 노출을
     * positionPercent로 제한해 단일 cash 풀 순차 곡선의 MDD를 분산 포트폴리오에 근사 환산한다.
     *
     * **1차 보수 LOCK (ADR 0121 §2-2)**: 멀티종목 동시 시뮬은 도입하지 않는다. 현 엔진은 한 시점 1종목만 보유하므로
     * [maxConcurrent]는 G3 판정 맥락("N슬롯 가정")을 위한 **메타데이터 echo**로만 기록되고 실제 동시보유 시뮬은 안 한다.
     *
     * @param positionPercent 1포지션당 가용 자본 비율 (%) — 실전 RiskConfig.maxPositionPercent 정합. 0.01~100.00.
     * @param maxConcurrent    동시 보유 가능 포지션 수 상한 — 실전 RiskConfig.maxConcurrentPositions 정합. 1~50. (1차: 메타 echo)
     * @param maxPositionAmount 1포지션 절대 상한 (KRW) — 실전 RiskConfig.maxPositionAmount 정합. null=비활성. min(percent, amount) 적용(A-1 정합).
     */
    data class FixedFractional(
        val positionPercent: BigDecimal,
        val maxConcurrent: Int = 1,
        val maxPositionAmount: BigDecimal? = null,
    ) : SizingPolicy() {
        init {
            require(positionPercent in BigDecimal("0.01")..BigDecimal("100.00")) { "positionPercent 0.01~100.00" }
            require(maxConcurrent in 1..50) { "maxConcurrent 1~50" }
            maxPositionAmount?.let { require(it > BigDecimal.ZERO) { "maxPositionAmount must be positive" } }
        }
    }

    /** 켈리 기준 — 승률·배당 기반 최적 비율 (post-MVP) */
    data class Kelly(
        val winRate: BigDecimal,
        val payoff: BigDecimal,
    ) : SizingPolicy()
}
