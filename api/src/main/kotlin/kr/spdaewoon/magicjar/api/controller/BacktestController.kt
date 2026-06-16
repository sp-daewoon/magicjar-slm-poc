package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPerformanceDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPositionDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunRequest
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunResponse
import kr.spdaewoon.magicjar.api.controller.dto.BacktestTradeDto
import kr.spdaewoon.magicjar.api.controller.dto.OverfitDetectRequest
import kr.spdaewoon.magicjar.api.controller.dto.OverfitDetectResponse
import kr.spdaewoon.magicjar.api.controller.dto.SensitivityAnalyzeRequest
import kr.spdaewoon.magicjar.api.controller.dto.SensitivityAnalyzeResponse
import kr.spdaewoon.magicjar.api.controller.dto.SensitivityPointDto
import kr.spdaewoon.magicjar.api.controller.dto.SizingPolicyEchoDto
import kr.spdaewoon.magicjar.api.controller.dto.SizingPolicyRequestDto
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import kr.spdaewoon.magicjar.domain.backtest.OverfitDiagnostics
import kr.spdaewoon.magicjar.domain.backtest.SensitivityResult
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 백테스트 REST 컨트롤러 (ADR 0056 §5-1 LOCK).
 *
 * ### Swagger 4단 어노테이션 (feedback_swagger_pattern.md 정합)
 * 1. @Tag: 컨트롤러 레벨
 * 2. @Operation: 메서드 레벨
 * 3. @RequestBody + @ApiResponses: 파라미터/응답 레벨
 * 4. DTO @Schema: DTO 필드 레벨 (BacktestDto.kt)
 *
 * ### fitness gap
 * - 룰 5 준수: LLM provider import 0건
 * - ADR 0047 정합: evaluateBacktest 경유 — controller 레이어는 service 위임만
 * - feedback_no_mock_data.md: Aborted(NO_CANDLE_DATA) 시 503 반환
 */
@Tag(name = "Backtest", description = "백테스트 — DSL 전략을 과거 candle 데이터로 시뮬레이션 · W3 T-BT")
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
) {
    private val log = LoggerFactory.getLogger(BacktestController::class.java)

    @Operation(
        summary = "백테스트 실행",
        description =
            "DSL 전략을 과거 candle 데이터로 시뮬레이션하여 매매 기록 + 성과 지표를 반환합니다. " +
                "ADR 0047 §2-5 — 평가 timeout 1000ms · 누적 50회 초과 시 abort (HTTP 206). " +
                "ADR 0023 정합 — 룰 매칭만, AI 호출 0건. " +
                "feedback_no_mock_data.md 정합 — candle 데이터 0건 시 HTTP 503 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "백테스트 성공 — 매매 기록 + 성과 지표"),
        ApiResponse(responseCode = "206", description = "백테스트 abort — 부분 결과 + 사유 (TIMEOUT_QUOTA_EXCEEDED 등)"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — strategyId 미존재 / 기간 역전 / universe 0건 / 잘못된 파라미터",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "캔들 데이터 부족 — backfill 미완료 (NO_CANDLE_DATA)",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
    )
    @PostMapping("/run")
    fun run(
        @RequestBody req: BacktestRunRequest,
    ): ResponseEntity<BacktestRunResponse> {
        log.info(
            "POST /api/backtest/run: strategyId={}, universe={}, interval={}, from={}, to={}",
            req.strategyId,
            req.universe,
            req.interval,
            req.from,
            req.to,
        )

        val result: BacktestResult =
            try {
                backtestService.runBacktest(
                    strategyId = req.strategyId,
                    universe = req.universe,
                    interval = req.interval,
                    fromStr = req.from,
                    toStr = req.to,
                    initialCapital = req.initialCapital,
                    taxModel = req.taxModel,
                    commissionModel = req.commissionModel,
                    slippageModel = req.slippageModel,
                    priceAdjustmentMode = req.priceAdjustmentMode,
                    sizingPolicy = toSizingPolicy(req.sizingPolicy),
                )
            } catch (e: BacktestException.StrategyNotFoundException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidRequestException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidStrategyDslException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InsufficientCandleDataException) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            }

        return when (result) {
            is BacktestResult.Success -> {
                val response =
                    BacktestRunResponse(
                        status = "SUCCESS",
                        trades = result.trades.map { toTradeDto(it) },
                        positions = result.positions.map { toPositionDto(it) },
                        finalCapital = result.finalCapital,
                        performance = toPerformanceDto(result.performance),
                        abortReason = null,
                        durationMs = result.durationMs,
                        sizingPolicy = toSizingEcho(result.request.sizingPolicy),
                    )
                ResponseEntity.ok(response)
            }

            is BacktestResult.Aborted -> {
                val httpStatus =
                    if (result.reason == "NO_CANDLE_DATA") {
                        HttpStatus.SERVICE_UNAVAILABLE
                    } else {
                        HttpStatus.PARTIAL_CONTENT
                    }

                val response =
                    BacktestRunResponse(
                        status = "ABORTED",
                        trades = result.partialTrades.map { toTradeDto(it) },
                        positions = emptyList(),
                        finalCapital = java.math.BigDecimal.ZERO,
                        performance = null,
                        abortReason = result.reason,
                        durationMs = result.durationMs,
                        sizingPolicy = toSizingEcho(result.request.sizingPolicy),
                    )
                ResponseEntity.status(httpStatus).body(response)
            }
        }
    }

    // =====================================================================
    // 과최적화 탐지 — IS/OOS 분할 (ADR 0117 §2-6 · spec §3-R3, add-only 신규 엔드포인트)
    // =====================================================================

    @Operation(
        summary = "과최적화 탐지 (IS/OOS 분할 백테스트)",
        description =
            "백테스트 기간을 학습(In-Sample)·검증(Out-of-Sample) 두 구간으로 분할해 각각 백테스트하고, " +
                "검증 구간에서도 기대값이 양(+)이고 표본·낙폭이 건전한지(과최적화 아닌지)를 판정합니다. " +
                "ADR 0117 §2-6 · spec §3-R3 LOCK — 판정: OOS 총수익률>0 && 거래≥100 && MDD≤8%. " +
                "ADR 0056 코어 산식 무변경(엔진 2-run orchestration). AI 호출 0건.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "탐지 성공 — IS/OOS 성과 + degradation + 판정"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — split 범위 밖 / 기간 역전 / strategyId 미존재",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "캔들 데이터 부족 — IS 또는 OOS 구간 백테스트 실패",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
    )
    @PostMapping("/overfit")
    fun detectOverfit(
        @RequestBody req: OverfitDetectRequest,
    ): ResponseEntity<OverfitDetectResponse> {
        log.info(
            "POST /api/backtest/overfit: strategyId={}, from={}, split={}, to={}",
            req.strategyId,
            req.from,
            req.outOfSampleSplit,
            req.to,
        )
        val diagnostics: OverfitDiagnostics =
            try {
                backtestService.detectOverfit(
                    strategyId = req.strategyId,
                    universe = req.universe,
                    interval = req.interval,
                    fromStr = req.from,
                    toStr = req.to,
                    outOfSampleSplitStr = req.outOfSampleSplit,
                    initialCapital = req.initialCapital,
                    taxModel = req.taxModel,
                    commissionModel = req.commissionModel,
                    slippageModel = req.slippageModel,
                    priceAdjustmentMode = req.priceAdjustmentMode,
                    sizingPolicy = toSizingPolicy(req.sizingPolicy),
                )
            } catch (e: BacktestException.StrategyNotFoundException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidRequestException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidStrategyDslException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InsufficientCandleDataException) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            }
        return ResponseEntity.ok(toOverfitResponse(diagnostics))
    }

    @Operation(
        summary = "파라미터 민감도 분석",
        description =
            "전략의 청산 파라미터 1개(손절%·익절%·트레일링%)를 그리드로 sweep해 수익률 분포·안정성을 측정합니다. " +
                "특정값에서만 좋고 인접값에서 급락하면 과최적화 의심(isPlateau=false), 넓은 구간에서 고르면 robust(isPlateau=true). " +
                "ADR 0117 §2-6 · spec §3-R3 LOCK — 엔진 그리드 루프 orchestration, 코어 산식 무변경. AI 호출 0건.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "분석 성공 — 그리드 각 점 성과 + plateau 판정"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — 알 수 없는 paramName / 그리드 점<2 / strategyId 미존재",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
    )
    @PostMapping("/sensitivity")
    fun analyzeSensitivity(
        @RequestBody req: SensitivityAnalyzeRequest,
    ): ResponseEntity<SensitivityAnalyzeResponse> {
        log.info(
            "POST /api/backtest/sensitivity: strategyId={}, paramName={}, gridSize={}",
            req.strategyId,
            req.paramName,
            req.grid.size,
        )
        val result: SensitivityResult =
            try {
                backtestService.analyzeSensitivity(
                    strategyId = req.strategyId,
                    universe = req.universe,
                    interval = req.interval,
                    fromStr = req.from,
                    toStr = req.to,
                    initialCapital = req.initialCapital,
                    paramName = req.paramName,
                    grid = req.grid,
                    taxModel = req.taxModel,
                    commissionModel = req.commissionModel,
                    slippageModel = req.slippageModel,
                    priceAdjustmentMode = req.priceAdjustmentMode,
                    sizingPolicy = toSizingPolicy(req.sizingPolicy),
                )
            } catch (e: BacktestException.StrategyNotFoundException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidRequestException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidStrategyDslException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            }
        return ResponseEntity.ok(toSensitivityResponse(result))
    }

    private fun toOverfitResponse(d: OverfitDiagnostics): OverfitDetectResponse =
        OverfitDetectResponse(
            inSample = toPerformanceDto(d.inSample),
            outOfSample = toPerformanceDto(d.outOfSample),
            returnDegradation = d.returnDegradation,
            sharpeDegradation = d.sharpeDegradation,
            passed = d.passed,
            oosExpectancyPositive = d.oosExpectancyPositive,
            oosTradesSufficient = d.oosTradesSufficient,
            oosDrawdownControlled = d.oosDrawdownControlled,
            degradationUndefinedReason = d.degradationUndefinedReason,
        )

    private fun toSensitivityResponse(r: SensitivityResult): SensitivityAnalyzeResponse =
        SensitivityAnalyzeResponse(
            paramName = r.paramName,
            points =
                r.points.map { p ->
                    SensitivityPointDto(
                        paramValue = p.paramValue,
                        performance = p.performance?.let { toPerformanceDto(it) },
                        skippedReason = p.skippedReason,
                    )
                },
            isPlateau = r.isPlateau,
            bestParamValue = r.bestParamValue,
            returnSpread = r.returnSpread,
            meanAdjacentVariation = r.meanAdjacentVariation,
        )

    // =====================================================================
    // 사이징 정책 매핑 (ADR 0121 §2-A — DTO ↔ 도메인. 미지정 시 AllCapital 회귀 0)
    // =====================================================================

    /**
     * 요청 DTO → 도메인 [SizingPolicy] (ADR 0121 §2-A).
     *
     * null 또는 positionPercent 미지정이면 [SizingPolicy.AllCapital](기존 회귀 0). positionPercent가 있으면
     * [SizingPolicy.FixedFractional]로 매핑한다(범위 검증은 FixedFractional.init이 require — 위반 시 IllegalArgumentException → 400).
     */
    private fun toSizingPolicy(dto: SizingPolicyRequestDto?): SizingPolicy {
        val pct = dto?.positionPercent ?: return SizingPolicy.AllCapital
        return SizingPolicy.FixedFractional(
            positionPercent = pct,
            maxConcurrent = dto.maxConcurrent,
            maxPositionAmount = dto.maxPositionAmount,
        )
    }

    /** 도메인 [SizingPolicy] → echo DTO (ADR 0121 §2-2 — G3 MDD 판정 맥락 노출). */
    private fun toSizingEcho(policy: SizingPolicy): SizingPolicyEchoDto =
        when (policy) {
            is SizingPolicy.FixedFractional ->
                SizingPolicyEchoDto(
                    type = "FIXED_FRACTIONAL",
                    positionPercent = policy.positionPercent,
                    maxConcurrent = policy.maxConcurrent,
                    maxPositionAmount = policy.maxPositionAmount,
                )
            SizingPolicy.AllCapital -> SizingPolicyEchoDto(type = "ALL_CAPITAL")
            is SizingPolicy.Kelly -> SizingPolicyEchoDto(type = "KELLY")
        }

    // =====================================================================
    // 도메인 → DTO 변환 (private helper — ADR 0051 Repository body fun 함정 무관, controller helper)
    // =====================================================================

    private fun toTradeDto(trade: BacktestTrade): BacktestTradeDto =
        BacktestTradeDto(
            code = trade.code,
            side = trade.side.name,
            quantity = trade.quantity,
            executedPrice = trade.executedPrice,
            rawPrice = trade.rawPrice,
            executedAt = trade.executedAt.toString(),
            commission = trade.commission,
            tax = trade.tax,
            slippage = trade.slippage,
        )

    private fun toPositionDto(pos: BacktestPosition): BacktestPositionDto =
        BacktestPositionDto(
            code = pos.code,
            quantity = pos.quantity,
            avgEntryPrice = pos.avgEntryPrice,
            openedAt = pos.openedAt.toString(),
            closedAt = pos.closedAt?.toString(),
            realizedPnl = pos.realizedPnl,
        )

    private fun toPerformanceDto(perf: BacktestPerformance): BacktestPerformanceDto =
        BacktestPerformanceDto(
            totalReturn = perf.totalReturn,
            cagr = perf.cagr,
            maxDrawdown = perf.maxDrawdown,
            sharpeRatio = perf.sharpeRatio,
            winRate = perf.winRate,
            totalTrades = perf.totalTrades,
            winningTrades = perf.winningTrades,
            losingTrades = perf.losingTrades,
            avgWin = perf.avgWin,
            avgLoss = perf.avgLoss,
            profitFactor = perf.profitFactor,
            tradingFrequency = perf.tradingFrequency,
        )

    /**
     * 에러 응답 DTO — Swagger @Schema 참조용.
     */
    @Schema(description = "에러 응답")
    data class ErrorResponseDto(
        @field:Schema(description = "에러 코드", example = "BACKTEST_STRATEGY_NOT_FOUND")
        val code: String,
        @field:Schema(description = "에러 메시지", example = "전략을 찾을 수 없습니다: strategyId=12")
        val message: String,
    )
}
