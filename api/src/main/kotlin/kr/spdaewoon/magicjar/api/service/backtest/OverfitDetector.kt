package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.OverfitDiagnostics
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 과최적화 탐지 orchestrator — IS/OOS 분할 백테스트 (ADR 0117 §2-6 · spec §3-R3).
 *
 * ## 책임 (add-only — ADR 0056 코어 산식 무변경 LOCK)
 * 기존 [BacktestEngine.run]을 **두 번** 호출해 In-Sample(학습)·Out-of-Sample(검증) 성과를 각각 산출하고,
 * IS 대비 OOS 저하율(degradation) + 판정 LOCK(OOS 기대값>0 && 거래≥100 && MDD≤8%)을 [OverfitDiagnostics]로 묶는다.
 * 백테스트 시뮬레이션·성과 산식은 일절 손대지 않는다(엔진 재사용 orchestration만).
 *
 * ## IS/OOS 분할 규칙
 * `outOfSampleSplit` 날짜 경계로 분할한다. IS = [from, splitDate-1일], OOS = [splitDate, to].
 * splitDate가 from이면 IS 0일이 되므로 init(BacktestRequest)에서 from < split ≤ to 를 보장한다.
 * 룩어헤드 가드·비용모델·ScaleInExitCalculator는 엔진이 각 구간 독립 run으로 그대로 적용한다.
 *
 * ## 정직 한계
 * IS/OOS는 단순 **시간 순서 분할(holdout)** 이다. rolling walk-forward(다중 fold)는 post-R3 backlog.
 * OOS 구간 candle이 0건이거나 run이 Aborted면 진단 불가 → [BacktestException]로 표면화(503/206 매핑).
 */
@Service
class OverfitDetector(
    private val backtestEngine: BacktestEngine,
) {
    private val log = LoggerFactory.getLogger(OverfitDetector::class.java)

    companion object {
        private val MC = MathContext(10, RoundingMode.HALF_EVEN)
        private const val SCALE = 6
    }

    /**
     * IS/OOS 분할 백테스트를 실행하고 과최적화 진단을 산출한다.
     *
     * @param request `outOfSampleSplit`이 지정된 백테스트 요청. 지정 안 됐으면 [BacktestException.InvalidRequestException].
     * @return [OverfitDiagnostics] — IS·OOS 성과 + degradation + 판정.
     * @throws BacktestException.InvalidRequestException split 미지정
     * @throws BacktestException.InsufficientCandleDataException 두 구간 중 한쪽이라도 candle 0건/Aborted
     */
    fun detect(request: BacktestRequest): OverfitDiagnostics {
        val split =
            request.outOfSampleSplit
                ?: throw BacktestException.InvalidRequestException("과최적화 탐지는 outOfSampleSplit이 필요합니다.")

        // IS = [from, split-1일] · OOS = [split, to]. exitParamOverride는 양 구간 동일 전파(전략 그대로 분할 검증).
        val isTo = split.plus(-1, DateTimeUnit.DAY)
        val isRequest = request.copy(from = request.from, to = isTo, outOfSampleSplit = null)
        val oosRequest = request.copy(from = split, to = request.to, outOfSampleSplit = null)

        log.info(
            "과최적화 탐지 시작: strategyId={} IS=[{}~{}] OOS=[{}~{}]",
            request.strategyId,
            request.from,
            isTo,
            split,
            request.to,
        )

        val isPerf = runOrThrow(isRequest, "IN_SAMPLE")
        val oosPerf = runOrThrow(oosRequest, "OUT_OF_SAMPLE")

        return diagnose(isPerf, oosPerf)
    }

    /** 한 구간 백테스트 실행 — Success면 성과, 그 외(Aborted)면 진단 불가 예외. */
    private fun runOrThrow(
        request: BacktestRequest,
        segment: String,
    ): BacktestPerformance =
        when (val result = backtestEngine.run(request)) {
            is BacktestResult.Success -> result.performance
            is BacktestResult.Aborted ->
                throw BacktestException.InsufficientCandleDataException(
                    code = "overfit/$segment(reason=${result.reason})",
                    interval = request.interval,
                    from = request.from.toString(),
                    to = request.to.toString(),
                )
        }

    /** IS/OOS 성과 → 진단 (degradation 산출 + 판정 LOCK). 순수 함수 — 테스트 직접 호출 가능. */
    fun diagnose(
        isPerf: BacktestPerformance,
        oosPerf: BacktestPerformance,
    ): OverfitDiagnostics {
        // 판정 LOCK 분해 (spec §3-R3 — 제안서 §3.3 + ADR 0056 정합)
        val expectancyOk = oosPerf.totalReturn > OverfitDiagnostics.OOS_MIN_EXPECTANCY
        val tradesOk = oosPerf.totalTrades >= OverfitDiagnostics.OOS_MIN_TRADES
        val drawdownOk = oosPerf.maxDrawdown <= OverfitDiagnostics.OOS_MAX_DRAWDOWN
        val passed = expectancyOk && tradesOk && drawdownOk

        // returnDegradation = (IS - OOS) / IS. IS ≤ 0이면 비율 무의미 → null.
        val (degradation, undefinedReason) =
            if (isPerf.totalReturn <= BigDecimal.ZERO) {
                null to "IS_RETURN_NON_POSITIVE"
            } else {
                isPerf.totalReturn
                    .subtract(oosPerf.totalReturn)
                    .divide(isPerf.totalReturn, MC)
                    .setScale(SCALE, RoundingMode.HALF_EVEN) to null
            }

        // sharpeDegradation = IS.sharpe - OOS.sharpe. 어느 한쪽이라도 null(거래 n<30)이면 null.
        val sharpeDegradation =
            if (isPerf.sharpeRatio != null && oosPerf.sharpeRatio != null) {
                isPerf.sharpeRatio!!.subtract(oosPerf.sharpeRatio!!).setScale(SCALE, RoundingMode.HALF_EVEN)
            } else {
                null
            }

        log.info(
            "과최적화 진단: passed={} (기대값>0={} 거래≥100={} MDD≤8%={}) IS.return={} OOS.return={} degradation={}",
            passed,
            expectancyOk,
            tradesOk,
            drawdownOk,
            isPerf.totalReturn,
            oosPerf.totalReturn,
            degradation,
        )

        return OverfitDiagnostics(
            inSample = isPerf,
            outOfSample = oosPerf,
            returnDegradation = degradation,
            sharpeDegradation = sharpeDegradation,
            passed = passed,
            oosExpectancyPositive = expectancyOk,
            oosTradesSufficient = tradesOk,
            oosDrawdownControlled = drawdownOk,
            degradationUndefinedReason = undefinedReason,
        )
    }
}
