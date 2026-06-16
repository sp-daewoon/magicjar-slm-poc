package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 전략 신뢰도 산출 순수 계산기 — spec `2026-06-16-strategy-daily-performance.md` §4-5 정합.
 *
 * 누적 일자행([StrategyDailyPerformance]) 에서 **읽기 시 계산**한다(저장 안 함). 표본수(누적 왕복·거래일)와
 * 일관성(일별 수익률 표준편차·Sharpe)으로 "이 전략을 믿어도 되는가 / 표본이 부족한가"를 라벨로 표현한다.
 *
 * ## 라벨 임계치 (companion const 로 노출 — 추후 튜닝)
 * - 누적 왕복 < [MIN_ROUND_TRIPS_LOW] (10) **또는** 거래일 < [MIN_TRADING_DAYS_LOW] (5) → LOW
 * - 누적 왕복 < [MIN_ROUND_TRIPS_MEDIUM] (30) → MEDIUM
 * - 그 외 → HIGH
 *
 * ## 통계 정의
 * - meanReturnPct = 일별 returnPct(거래 발생일 한정) 의 산술 평균.
 * - stdDevReturnPct = 모표준편차(population, N 으로 나눔). 표본 < 2 면 분산 무의미 → null.
 * - sharpe = mean / std (무위험수익률 0 가정, 일별 기준). std = 0 또는 null 이면 null.
 *
 * // AI 호출 0 — 순수 통계 (spec §4-5, mock·임의 합성 금지)
 */
object StrategyConfidenceCalculator {
    const val MIN_ROUND_TRIPS_LOW = 10
    const val MIN_TRADING_DAYS_LOW = 5
    const val MIN_ROUND_TRIPS_MEDIUM = 30

    private const val MEAN_SCALE = 4
    private const val STD_SCALE = 4
    private const val SHARPE_SCALE = 4
    private val MC = MathContext.DECIMAL64

    /**
     * 누적 일자행 → 신뢰도.
     *
     * @param dailyRows 한 전략의 일자별 성과 행들(순서 무관). returnPct = null(진입금액 0) 인 행은
     *                  수익률 통계 표본에서 제외하되 roundTripCount·거래일 집계엔 포함.
     */
    fun compute(dailyRows: List<StrategyDailyPerformance>): StrategyConfidence {
        val sampleRoundTrips = dailyRows.sumOf { it.roundTripCount }
        val tradingDays = dailyRows.count { it.roundTripCount > 0 }

        val returns = dailyRows.mapNotNull { it.returnPct }
        val mean = mean(returns)
        val std = stdDev(returns, mean)
        val sharpe = sharpe(mean, std)

        val label = label(sampleRoundTrips, tradingDays)

        return StrategyConfidence(
            label = label,
            sampleRoundTrips = sampleRoundTrips,
            tradingDays = tradingDays,
            meanReturnPct = mean?.setScale(MEAN_SCALE, RoundingMode.HALF_UP),
            stdDevReturnPct = std?.setScale(STD_SCALE, RoundingMode.HALF_UP),
            sharpe = sharpe?.setScale(SHARPE_SCALE, RoundingMode.HALF_UP),
        )
    }

    /** 라벨 분류 — companion 임계치 기준 (spec §4-5). */
    fun label(
        sampleRoundTrips: Int,
        tradingDays: Int,
    ): ConfidenceLabel =
        when {
            sampleRoundTrips < MIN_ROUND_TRIPS_LOW || tradingDays < MIN_TRADING_DAYS_LOW -> ConfidenceLabel.LOW
            sampleRoundTrips < MIN_ROUND_TRIPS_MEDIUM -> ConfidenceLabel.MEDIUM
            else -> ConfidenceLabel.HIGH
        }

    // --- private helpers (헌법 §6) ---

    private fun mean(values: List<BigDecimal>): BigDecimal? {
        if (values.isEmpty()) return null
        val sum = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        return sum.divide(BigDecimal(values.size), MC)
    }

    /** 모표준편차(population, /N). 표본 < 2 면 null. */
    private fun stdDev(
        values: List<BigDecimal>,
        mean: BigDecimal?,
    ): BigDecimal? {
        if (values.size < 2 || mean == null) return null
        val variance =
            values
                .fold(BigDecimal.ZERO) { acc, v ->
                    val d = v.subtract(mean)
                    acc + d.multiply(d)
                }.divide(BigDecimal(values.size), MC)
        return variance.sqrt(MC)
    }

    private fun sharpe(
        mean: BigDecimal?,
        std: BigDecimal?,
    ): BigDecimal? {
        if (mean == null || std == null || std.signum() == 0) return null
        return mean.divide(std, MC)
    }
}
