package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.toKotlinLocalDate
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.OverfitDiagnostics
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SensitivityRequest
import kr.spdaewoon.magicjar.domain.backtest.SensitivityResult
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 백테스트 서비스 — 요청 검증 + BacktestEngine 위임 (ADR 0056 §2-1).
 *
 * ### 책임
 * 1. 요청 파라미터 검증 (날짜 형식, 범위, universe 비어있지 않음)
 * 2. 문자열 파라미터 → 도메인 타입 변환
 * 3. BacktestEngine.run() 위임
 * 4. 결과 반환 (stateless — DB 영속 0, backlog §27-2 합류)
 *
 * ### 사용자 메모리 정합
 * - feedback_no_mock_data.md: candle 0건 → BacktestResult.Aborted(reason="NO_CANDLE_DATA") 반환
 * - 직접 오류 throw는 HTTP 503 매핑을 BacktestExceptionHandler에 위임
 */
@Service
class BacktestService(
    private val backtestEngine: BacktestEngine,
    private val overfitDetector: OverfitDetector,
    private val sensitivityAnalyzer: SensitivityAnalyzer,
) {
    private val log = LoggerFactory.getLogger(BacktestService::class.java)

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 백테스트를 실행한다.
     *
     * @param strategyId 전략 ID
     * @param universe 종목코드 리스트
     * @param interval 캔들 인터벌 문자열
     * @param fromStr 시작일 문자열 (yyyy-MM-dd)
     * @param toStr 종료일 문자열 (yyyy-MM-dd)
     * @param initialCapital 초기 자본
     * @param taxModel 세금 모델 키
     * @param commissionModel 수수료 모델 키
     * @param slippageModel 슬리피지 모델 키
     * @param priceAdjustmentMode 가격 조정 모드 문자열
     */
    fun runBacktest(
        strategyId: Long,
        universe: List<String>,
        interval: String,
        fromStr: String,
        toStr: String,
        initialCapital: BigDecimal,
        taxModel: String = "korean_default",
        commissionModel: String = "kis_direct",
        slippageModel: String = "fixed_0.05",
        priceAdjustmentMode: String = "ADJUSTED",
        sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
    ): BacktestResult {
        // 파라미터 검증
        if (universe.isEmpty()) {
            throw BacktestException.InvalidRequestException("universe는 비어있을 수 없습니다.")
        }
        if (initialCapital <= BigDecimal.ZERO) {
            throw BacktestException.InvalidRequestException("initialCapital은 0보다 커야 합니다: $initialCapital")
        }

        val from: kotlinx.datetime.LocalDate = parseDate(fromStr)
        val to: kotlinx.datetime.LocalDate = parseDate(toStr)

        if (from > to) {
            throw BacktestException.InvalidRequestException("from($fromStr)은 to($toStr)보다 클 수 없습니다.")
        }

        val adjMode =
            try {
                PriceAdjustmentMode.valueOf(priceAdjustmentMode.uppercase())
            } catch (e: IllegalArgumentException) {
                throw BacktestException.InvalidRequestException(
                    "priceAdjustmentMode 값이 올바르지 않습니다: '$priceAdjustmentMode'. 허용 값: ${PriceAdjustmentMode.entries.map { it.name }}",
                )
            }

        val request =
            BacktestRequest(
                strategyId = strategyId,
                universe = universe,
                interval = interval,
                from = from,
                to = to,
                initialCapital = initialCapital,
                taxModel = taxModel,
                commissionModel = commissionModel,
                slippageModel = slippageModel,
                priceAdjustmentMode = adjMode,
                sizingPolicy = sizingPolicy,
            )

        log.info("백테스트 서비스 요청: strategyId={}, universe={}, interval={}, from={}, to={}", strategyId, universe, interval, from, to)

        return backtestEngine.run(request)
    }

    /**
     * 과최적화 탐지(IS/OOS 분할)를 실행한다 (ADR 0117 §2-6 · spec §3-R3).
     *
     * 백테스트 기간을 학습/검증 두 구간으로 나눠 각각 백테스트하고, 검증 구간에서도 돈이 되는지(과최적화 아닌지)를 판정한다.
     */
    fun detectOverfit(
        strategyId: Long,
        universe: List<String>,
        interval: String,
        fromStr: String,
        toStr: String,
        outOfSampleSplitStr: String,
        initialCapital: BigDecimal,
        taxModel: String = "korean_default",
        commissionModel: String = "kis_direct",
        slippageModel: String = "fixed_0.05",
        priceAdjustmentMode: String = "ADJUSTED",
        sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
    ): OverfitDiagnostics {
        val request =
            buildRequest(
                strategyId = strategyId,
                universe = universe,
                interval = interval,
                fromStr = fromStr,
                toStr = toStr,
                initialCapital = initialCapital,
                taxModel = taxModel,
                commissionModel = commissionModel,
                slippageModel = slippageModel,
                priceAdjustmentMode = priceAdjustmentMode,
                outOfSampleSplit = parseDate(outOfSampleSplitStr),
                sizingPolicy = sizingPolicy,
            )
        log.info("과최적화 탐지 요청: strategyId={}, split={}", strategyId, outOfSampleSplitStr)
        return overfitDetector.detect(request)
    }

    /**
     * 파라미터 민감도 분석을 실행한다 (ADR 0117 §2-6 · spec §3-R3).
     *
     * 청산 파라미터 1개를 그리드로 sweep해 수익률 분포·고원(plateau) 안정성을 측정한다.
     */
    fun analyzeSensitivity(
        strategyId: Long,
        universe: List<String>,
        interval: String,
        fromStr: String,
        toStr: String,
        initialCapital: BigDecimal,
        paramName: String,
        grid: List<BigDecimal>,
        taxModel: String = "korean_default",
        commissionModel: String = "kis_direct",
        slippageModel: String = "fixed_0.05",
        priceAdjustmentMode: String = "ADJUSTED",
        sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
    ): SensitivityResult {
        val base =
            buildRequest(
                strategyId = strategyId,
                universe = universe,
                interval = interval,
                fromStr = fromStr,
                toStr = toStr,
                initialCapital = initialCapital,
                taxModel = taxModel,
                commissionModel = commissionModel,
                slippageModel = slippageModel,
                priceAdjustmentMode = priceAdjustmentMode,
                outOfSampleSplit = null,
                sizingPolicy = sizingPolicy,
            )
        val sensitivityRequest =
            try {
                SensitivityRequest(base = base, paramName = paramName, grid = grid)
            } catch (e: IllegalArgumentException) {
                throw BacktestException.InvalidRequestException(e.message ?: "민감도 요청이 올바르지 않습니다.")
            }
        log.info("민감도 분석 요청: strategyId={}, paramName={}, gridSize={}", strategyId, paramName, grid.size)
        return sensitivityAnalyzer.analyze(sensitivityRequest)
    }

    /** 공통 [BacktestRequest] 빌더 — 파라미터 검증 + 문자열 파싱(단일 run·과최적화·민감도 공유). */
    private fun buildRequest(
        strategyId: Long,
        universe: List<String>,
        interval: String,
        fromStr: String,
        toStr: String,
        initialCapital: BigDecimal,
        taxModel: String,
        commissionModel: String,
        slippageModel: String,
        priceAdjustmentMode: String,
        outOfSampleSplit: kotlinx.datetime.LocalDate?,
        sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
    ): BacktestRequest {
        if (universe.isEmpty()) {
            throw BacktestException.InvalidRequestException("universe는 비어있을 수 없습니다.")
        }
        if (initialCapital <= BigDecimal.ZERO) {
            throw BacktestException.InvalidRequestException("initialCapital은 0보다 커야 합니다: $initialCapital")
        }
        val from = parseDate(fromStr)
        val to = parseDate(toStr)
        if (from > to) {
            throw BacktestException.InvalidRequestException("from($fromStr)은 to($toStr)보다 클 수 없습니다.")
        }
        val adjMode =
            try {
                PriceAdjustmentMode.valueOf(priceAdjustmentMode.uppercase())
            } catch (e: IllegalArgumentException) {
                throw BacktestException.InvalidRequestException(
                    "priceAdjustmentMode 값이 올바르지 않습니다: '$priceAdjustmentMode'. 허용 값: ${PriceAdjustmentMode.entries.map { it.name }}",
                )
            }
        return try {
            BacktestRequest(
                strategyId = strategyId,
                universe = universe,
                interval = interval,
                from = from,
                to = to,
                initialCapital = initialCapital,
                taxModel = taxModel,
                commissionModel = commissionModel,
                slippageModel = slippageModel,
                priceAdjustmentMode = adjMode,
                sizingPolicy = sizingPolicy,
                outOfSampleSplit = outOfSampleSplit,
            )
        } catch (e: IllegalArgumentException) {
            throw BacktestException.InvalidRequestException(e.message ?: "백테스트 요청이 올바르지 않습니다.")
        }
    }

    private fun parseDate(dateStr: String): kotlinx.datetime.LocalDate =
        try {
            LocalDate.parse(dateStr, dateFormatter).toKotlinLocalDate()
        } catch (e: DateTimeParseException) {
            throw BacktestException.InvalidRequestException("날짜 형식이 올바르지 않습니다: '$dateStr'. 형식: yyyy-MM-dd")
        }
}
