package kr.spdaewoon.magicjar.api.service.backtest

import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.ExitParamOverride
import kr.spdaewoon.magicjar.domain.backtest.SensitivityPoint
import kr.spdaewoon.magicjar.domain.backtest.SensitivityRequest
import kr.spdaewoon.magicjar.domain.backtest.SensitivityResult
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 파라미터 민감도 분석 — 청산 knob 1개 그리드 sweep (ADR 0117 §2-6 · spec §3-R3).
 *
 * ## 책임 (add-only — ADR 0056 코어 산식 무변경 LOCK)
 * 기준 [SensitivityRequest.base] 요청의 청산 파라미터 1개([SensitivityRequest.paramName])를 그리드 각 값으로
 * [ExitParamOverride] 치환해 [BacktestEngine.run]을 **그리드 점 수만큼 반복** 호출하고, 수익률 분포로 고원(plateau) 여부를 판정한다.
 * 백테스트 시뮬·성과 산식은 손대지 않는다(엔진 재사용 그리드 루프만).
 *
 * ## plateau(고원) 판정 — 과최적화 안정성 lens
 * 인접 그리드 점의 totalReturn **상대 변동**(|Δreturn| / (|base return| + ε))을 모두 구해 평균([SensitivityResult.meanAdjacentVariation])을 낸다.
 * 평균 상대 변동이 [PLATEAU_VARIATION_THRESHOLD] 이하이면 넓은 구간에서 성과가 고르다(고원·robust) → isPlateau=true.
 * 크면 특정 값에서만 좋고 인접값에서 급락(절벽) → 과최적화 의심 → isPlateau=false.
 *
 * ## 정직 한계
 * 단일 파라미터 1차원 sweep이다(2D 그리드·교호작용은 post-R3 backlog). 일부 그리드 점이 Aborted면 그 점은
 * performance=null로 기록하고 plateau 판정에서 제외한다(유효 점 < 2면 isPlateau=false 보수 판정).
 */
@Service
class SensitivityAnalyzer(
    private val backtestEngine: BacktestEngine,
) {
    private val log = LoggerFactory.getLogger(SensitivityAnalyzer::class.java)

    companion object {
        private val MC = MathContext(10, RoundingMode.HALF_EVEN)
        private const val SCALE = 6

        /**
         * 고원 판정 임계 — 인접 점 평균 상대 변동 ≤ 0.25(25%)면 robust(고원).
         * 즉 파라미터를 한 칸 옮길 때 수익률이 평균 25% 넘게 출렁이면 불안정(과최적화 의심)으로 본다.
         */
        val PLATEAU_VARIATION_THRESHOLD: BigDecimal = BigDecimal("0.25")

        /** 상대 변동 분모 0 방어 epsilon. */
        private val EPSILON: BigDecimal = BigDecimal("0.0001")
    }

    /**
     * 그리드 sweep을 실행하고 민감도 결과를 산출한다.
     *
     * @param request 기준 요청 + 대상 파라미터 + 그리드.
     * @return [SensitivityResult] — 각 점 성과 + plateau 판정 + best 값.
     */
    fun analyze(request: SensitivityRequest): SensitivityResult {
        val param = request.exitParam
        log.info(
            "민감도 분석 시작: strategyId={} paramName={} grid={}",
            request.base.strategyId,
            request.paramName,
            request.grid,
        )

        val points: List<SensitivityPoint> =
            request.grid.map { value ->
                val override = ExitParamOverride(target = param, value = value)
                val runReq = request.base.copy(exitParamOverride = override, outOfSampleSplit = null)
                when (val result = backtestEngine.run(runReq)) {
                    is BacktestResult.Success ->
                        SensitivityPoint(paramValue = value, performance = result.performance)

                    is BacktestResult.Aborted ->
                        SensitivityPoint(paramValue = value, performance = null, skippedReason = result.reason)
                }
            }

        return summarize(points, request.paramName)
    }

    /** 그리드 점들 → plateau 판정 + best/spread. 순수 함수 — 테스트 직접 호출 가능. */
    fun summarize(
        points: List<SensitivityPoint>,
        paramName: String,
    ): SensitivityResult {
        val valid = points.filter { it.performance != null }
        val returns = valid.map { it.performance!!.totalReturn }

        val bestParamValue =
            valid.maxByOrNull { it.performance!!.totalReturn }?.paramValue
                ?: points.first().paramValue

        val returnSpread =
            if (returns.isEmpty()) {
                BigDecimal.ZERO
            } else {
                returns.max().subtract(returns.min()).setScale(SCALE, RoundingMode.HALF_EVEN)
            }

        // 인접 점 상대 변동 평균: 유효 점 ≥ 2 필요. 미만이면 판정 불가 → 보수적으로 비고원(false).
        val (meanVariation, isPlateau) =
            if (valid.size < 2) {
                BigDecimal.ZERO to false
            } else {
                val variations =
                    returns.zipWithNext { a, b ->
                        val denom = a.abs().add(EPSILON)
                        b.subtract(a).abs().divide(denom, MC)
                    }
                val mean =
                    variations
                        .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                        .divide(BigDecimal(variations.size), MC)
                        .setScale(SCALE, RoundingMode.HALF_EVEN)
                mean to (mean <= PLATEAU_VARIATION_THRESHOLD)
            }

        log.info(
            "민감도 결과: paramName={} validPoints={}/{} isPlateau={} meanVariation={} returnSpread={} best={}",
            paramName,
            valid.size,
            points.size,
            isPlateau,
            meanVariation,
            returnSpread,
            bestParamValue,
        )

        return SensitivityResult(
            points = points,
            isPlateau = isPlateau,
            bestParamValue = bestParamValue,
            paramName = paramName,
            returnSpread = returnSpread,
            meanAdjacentVariation = meanVariation,
        )
    }
}
