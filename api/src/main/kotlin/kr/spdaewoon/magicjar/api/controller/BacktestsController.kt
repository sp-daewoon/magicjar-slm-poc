package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPageDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPerformanceDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPositionDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPositionResultDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestResultPageDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunDetailDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunPersistResponse
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunRequest
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunSummaryDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestTradeDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestTradeResultDto
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestTradeJpaEntity
import kr.spdaewoon.magicjar.api.service.backtest.BacktestPersister
import kr.spdaewoon.magicjar.api.service.backtest.BacktestQueryService
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 백테스트 영속 REST 컨트롤러 (ADR 0061 §4 C-1 채택 — 5 endpoint).
 *
 * ### endpoint 목록
 * 1. POST /api/backtests — 실행 + 즉시 영속 (PENDING→RUNNING→SUCCEEDED/FAILED)
 * 2. GET /api/backtests — 사용자 목록 (페이징)
 * 3. GET /api/backtests/{id} — 단건 메타 + KPI
 * 4. GET /api/backtests/{id}/result — 거래·포지션 시계열 (페이징)
 * 5. DELETE /api/backtests/{id} — 삭제 (CASCADE)
 *
 * ### Swagger 4단 어노테이션 (feedback_swagger_pattern.md 정합)
 * 1. @Tag: 컨트롤러 레벨
 * 2. @Operation: 메서드 레벨
 * 3. @Parameter/@RequestParam + @ApiResponses: 파라미터/응답 레벨
 * 4. DTO @Schema: DTO 필드 레벨 (BacktestPersistenceDto.kt)
 *
 * ### fitness gap 검증
 * - 룰 5 준수: LLM provider import 0건
 * - feedback_no_mock_data.md: NO_CANDLE_DATA 시 FAILED status로 저장 + 503 반환
 * - ADR 0061 §4 정합: 기존 /api/backtest/run endpoint 기능 유지 (BacktestController) + 신규 영속 API
 */
@Tag(name = "Backtests", description = "백테스트 실행·영속·조회·삭제 — T-BT R2 W3 (ADR 0061)")
@RestController
@RequestMapping("/api/backtests")
class BacktestsController(
    private val backtestService: BacktestService,
    private val backtestPersister: BacktestPersister,
    private val backtestQueryService: BacktestQueryService,
) {
    private val log = LoggerFactory.getLogger(BacktestsController::class.java)

    // =====================================================================
    // 1. POST /api/backtests — 실행 + 즉시 영속
    // =====================================================================

    @Operation(
        summary = "백테스트 실행 + 영속",
        description =
            "DSL 전략을 과거 캔들 데이터로 시뮬레이션하고 결과를 DB에 영속합니다. " +
                "ADR 0056 §2-5 정합 — timeout 1000ms · abort quota 50회. " +
                "ADR 0061 §4 정합 — status PENDING→RUNNING→SUCCEEDED/FAILED. " +
                "feedback_no_mock_data.md 정합 — NO_CANDLE_DATA 시 503 반환 + FAILED status 저장.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "백테스트 성공 — runId + 매매 기록 + 성과 지표"),
        ApiResponse(responseCode = "206", description = "백테스트 abort — 부분 결과 + 사유"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — strategyId 미존재 / 기간 역전 / universe 0건 / 파라미터 오류",
            content = [Content(schema = Schema(implementation = BacktestErrorResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "캔들 데이터 부족 — backfill 미완료 (NO_CANDLE_DATA). status=FAILED로 저장됨",
            content = [Content(schema = Schema(implementation = BacktestErrorResponseDto::class))],
        ),
    )
    @PostMapping
    fun runAndPersist(
        @RequestBody req: BacktestRunRequest,
        @Parameter(description = "실행 사용자 ID", example = "user001", required = true)
        @RequestParam userId: String,
    ): ResponseEntity<BacktestRunPersistResponse> {
        log.info(
            "POST /api/backtests: userId={}, strategyId={}, universe={}, interval={}, from={}, to={}",
            userId,
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

        // 결과 영속 (NO_CANDLE_DATA 포함 — FAILED status로 저장)
        val runId = backtestPersister.persist(userId = userId, result = result)

        return when (result) {
            is BacktestResult.Success -> {
                val response =
                    BacktestRunPersistResponse(
                        runId = runId,
                        status = "SUCCESS",
                        trades = result.trades.map { toTradeDto(it) },
                        positions = result.positions.map { toPositionDto(it) },
                        finalCapital = result.finalCapital,
                        performance = toPerformanceDto(result.performance),
                        abortReason = null,
                        durationMs = result.durationMs,
                    )
                ResponseEntity.status(HttpStatus.CREATED).body(response)
            }

            is BacktestResult.Aborted -> {
                val httpStatus =
                    if (result.reason == "NO_CANDLE_DATA") {
                        HttpStatus.SERVICE_UNAVAILABLE
                    } else {
                        HttpStatus.PARTIAL_CONTENT
                    }

                val response =
                    BacktestRunPersistResponse(
                        runId = runId,
                        status = "ABORTED",
                        trades = result.partialTrades.map { toTradeDto(it) },
                        positions = emptyList(),
                        finalCapital = java.math.BigDecimal.ZERO,
                        performance = null,
                        abortReason = result.reason,
                        durationMs = result.durationMs,
                    )
                ResponseEntity.status(httpStatus).body(response)
            }
        }
    }

    // =====================================================================
    // 2. GET /api/backtests — 사용자 목록 (페이징)
    // =====================================================================

    @Operation(
        summary = "백테스트 목록 조회",
        description =
            "사용자의 백테스트 실행 목록을 최신 순으로 반환합니다 (페이징). " +
                "ADR 0061 §4 C-1 정합. status별 필터링은 R3 frontend에서 처리.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "목록 조회 성공"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "user001", required = true)
        @RequestParam userId: String,
        @Parameter(description = "페이지 번호 (0-indexed)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기 (최대 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): BacktestPageDto<BacktestRunSummaryDto> {
        val resultPage = backtestQueryService.listByUser(userId, page, size)
        return BacktestPageDto(
            content = resultPage.content.map { toSummaryDto(it) },
            page = resultPage.number,
            size = resultPage.size,
            totalElements = resultPage.totalElements,
            totalPages = resultPage.totalPages,
        )
    }

    // =====================================================================
    // 3. GET /api/backtests/{id} — 단건 메타 + KPI
    // =====================================================================

    @Operation(
        summary = "백테스트 단건 조회",
        description =
            "단건 백테스트 메타 정보 + KPI를 반환합니다. " +
                "사용자 소유 확인 포함 — 다른 사용자의 백테스트는 404.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "단건 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "존재하지 않거나 소유권 불일치",
            content = [Content(schema = Schema(implementation = BacktestErrorResponseDto::class))],
        ),
    )
    @GetMapping("/{id}")
    fun getDetail(
        @Parameter(description = "백테스트 실행 ID", example = "42", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "user001", required = true)
        @RequestParam userId: String,
    ): BacktestRunDetailDto {
        val run = backtestQueryService.getByIdAndUser(id, userId)
        return toDetailDto(run)
    }

    // =====================================================================
    // 4. GET /api/backtests/{id}/result — 거래·포지션 시계열
    // =====================================================================

    @Operation(
        summary = "백테스트 결과 시계열 조회",
        description =
            "단건 백테스트의 거래 기록 + 포지션 기록을 시계열로 반환합니다. " +
                "trade·position 각각 페이징 지원. ADR 0061 §4 C-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시계열 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "존재하지 않거나 소유권 불일치",
            content = [Content(schema = Schema(implementation = BacktestErrorResponseDto::class))],
        ),
    )
    @GetMapping("/{id}/result")
    fun getResult(
        @Parameter(description = "백테스트 실행 ID", example = "42", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "user001", required = true)
        @RequestParam userId: String,
        @Parameter(description = "trade 페이지 (0-indexed)", example = "0")
        @RequestParam(defaultValue = "0") tradePage: Int,
        @Parameter(description = "trade 페이지 크기", example = "100")
        @RequestParam(defaultValue = "100") tradeSize: Int,
        @Parameter(description = "position 페이지 (0-indexed)", example = "0")
        @RequestParam(defaultValue = "0") posPage: Int,
        @Parameter(description = "position 페이지 크기", example = "50")
        @RequestParam(defaultValue = "50") posSize: Int,
    ): BacktestResultPageDto {
        val tradePage2: Page<BacktestTradeJpaEntity> =
            backtestQueryService.listTradesByRunId(id, userId, tradePage, tradeSize)
        val posPage2: Page<BacktestPositionJpaEntity> =
            backtestQueryService.listPositionsByRunId(id, userId, posPage, posSize)

        return BacktestResultPageDto(
            trades =
                BacktestPageDto(
                    content = tradePage2.content.map { toTradeResultDto(it) },
                    page = tradePage2.number,
                    size = tradePage2.size,
                    totalElements = tradePage2.totalElements,
                    totalPages = tradePage2.totalPages,
                ),
            positions =
                BacktestPageDto(
                    content = posPage2.content.map { toPositionResultDto(it) },
                    page = posPage2.number,
                    size = posPage2.size,
                    totalElements = posPage2.totalElements,
                    totalPages = posPage2.totalPages,
                ),
        )
    }

    // =====================================================================
    // 5. DELETE /api/backtests/{id} — 삭제 (CASCADE)
    // =====================================================================

    @Operation(
        summary = "백테스트 삭제",
        description =
            "백테스트 실행 기록 + 연관 trade·position을 CASCADE 삭제합니다. " +
                "멱등성 보장 — 이미 삭제된 runId는 404 반환. " +
                "사용자 소유 확인 포함.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "존재하지 않거나 소유권 불일치",
            content = [Content(schema = Schema(implementation = BacktestErrorResponseDto::class))],
        ),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(description = "백테스트 실행 ID", example = "42", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "user001", required = true)
        @RequestParam userId: String,
    ): ResponseEntity<Void> {
        backtestQueryService.deleteByIdAndUser(id, userId)
        return ResponseEntity.noContent().build()
    }

    // =====================================================================
    // 도메인 → DTO 변환 (private helper — 헌법 §6 caller class 위치)
    // =====================================================================

    private fun toSummaryDto(run: BacktestRunJpaEntity): BacktestRunSummaryDto =
        BacktestRunSummaryDto(
            id = requireNotNull(run.id),
            code = run.code,
            interval = run.interval,
            rangeFrom = run.rangeFrom.toString(),
            rangeTo = run.rangeTo.toString(),
            status = run.status,
            completedAt = run.completedAt?.toString(),
            totalReturn = run.totalReturn,
            winRate = run.winRate,
            mdd = run.mdd,
            sharpe = run.sharpe,
            tradeCount = run.tradeCount,
            createdAt = run.createdAt.toString(),
        )

    private fun toDetailDto(run: BacktestRunJpaEntity): BacktestRunDetailDto =
        BacktestRunDetailDto(
            id = requireNotNull(run.id),
            code = run.code,
            interval = run.interval,
            ruleDsl = run.ruleDsl,
            rangeFrom = run.rangeFrom.toString(),
            rangeTo = run.rangeTo.toString(),
            status = run.status,
            startedAt = run.startedAt?.toString(),
            completedAt = run.completedAt?.toString(),
            totalReturn = run.totalReturn,
            winRate = run.winRate,
            mdd = run.mdd,
            sharpe = run.sharpe,
            tradeCount = run.tradeCount,
            positionCount = run.positionCount,
            createdAt = run.createdAt.toString(),
        )

    private fun toTradeResultDto(entity: BacktestTradeJpaEntity): BacktestTradeResultDto =
        BacktestTradeResultDto(
            id = requireNotNull(entity.id),
            ts = entity.ts.toString(),
            side = entity.side,
            price = entity.price,
            quantity = entity.quantity,
            fee = entity.fee,
            ruleSignal = entity.ruleSignal,
        )

    private fun toPositionResultDto(entity: BacktestPositionJpaEntity): BacktestPositionResultDto =
        BacktestPositionResultDto(
            id = requireNotNull(entity.id),
            openTs = entity.openTs.toString(),
            closeTs = entity.closeTs?.toString(),
            entryPrice = entity.entryPrice,
            exitPrice = entity.exitPrice,
            quantity = entity.quantity,
            pnl = entity.pnl,
            returnPct = entity.returnPct,
        )

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
    data class BacktestErrorResponseDto(
        @field:Schema(description = "에러 코드", example = "BACKTEST_NOT_FOUND")
        val code: String,
        @field:Schema(description = "에러 메시지", example = "백테스트를 찾을 수 없습니다: id=42")
        val message: String,
    )
}
