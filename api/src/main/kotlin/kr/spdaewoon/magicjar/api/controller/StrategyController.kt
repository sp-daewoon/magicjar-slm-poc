package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * 전략 DSL REST 컨트롤러 — CRUD + 검증 + import (spec §6-4 LOCK).
 *
 * 등록 흐름: POST → KisYamlDslParser.parse() → StrategyService.validateAndComputeComplexity() → DB persist → 200 + complexity.
 * 평가 흐름: POST /{id}/evaluate → StrategyEvaluatorPort → strategy-engine JvmStrategyEvaluatorAdapter.
 *
 * Swagger 4단 어노테이션 (메모리 feedback_swagger_pattern 정합):
 * - @Tag: 컨트롤러 레벨
 * - @Operation: 메서드 레벨
 * - @Parameter: 파라미터 레벨
 * - DTO @Schema: DTO 필드 레벨
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 * ADR 0051 준수: Repository interface body fun 0건.
 */
@Tag(name = "Strategy", description = "전략 DSL CRUD + 검증 + KIS .kis.yaml import/export — T-DSL W2")
@RestController
@RequestMapping("/api/strategy")
class StrategyController(
    private val strategyService: StrategyService,
    private val strategyEvaluatorPort: StrategyEvaluatorPort,
    private val kisYamlDslParser: KisYamlDslParser,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    @Value("\${magicjar.strategy.evaluate.candle-lookback:200}") private val evaluateCandleLookback: Int,
) {
    private val log = LoggerFactory.getLogger(StrategyController::class.java)

    @Operation(
        summary = "전략 등록",
        description =
            "텍스트 DSL 1줄(예: `ma(5) crosses_above ma(20)`)을 등록합니다. " +
                "ADR 0047 정합 — static 검증 후 complexity 응답 반환. " +
                "ADR 0022 §2-1 KIS 호환 DSL 표현식 지원.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 등록 성공 — strategyId + complexity 반환"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류 / AST depth 초과 / 노드 수 초과 / 지표 호출 수 초과 / 화이트리스트 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping
    fun create(
        @RequestBody req: CreateStrategyRequest,
    ): CreateStrategyResponse {
        log.info("전략 등록 요청: name={}", req.name)
        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.create(
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
                stopLossPct = req.stopLossPct,
                takeProfitPct = req.takeProfitPct,
                viActionPolicy = parseViActionPolicy(req.viActionPolicy),
            )
        return CreateStrategyResponse(
            strategyId = strategy.id ?: 0L,
            complexity = toComplexityDto(strategy.complexity),
        )
    }

    @Operation(
        summary = "전략 목록 조회",
        description = "사용자의 전략 목록을 반환합니다. userId 파라미터로 소유자 필터.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 목록 조회 성공"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @org.springframework.web.bind.annotation.RequestParam userId: Long,
    ): List<StrategyResponse> = strategyService.findAllByUserId(userId).map { toStrategyResponse(it) }

    @Operation(
        summary = "전략 단건 조회",
        description = "전략 ID로 단건 조회합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): StrategyResponse {
        val strategy = strategyService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return toStrategyResponse(strategy)
    }

    @Operation(
        summary = "전략 삭제",
        description = "전략 ID로 삭제합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "전략 삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ) {
        strategyService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategyService.deleteById(id)
    }

    @Operation(
        summary = "DSL 식 검증 (등록 전 미리보기)",
        description =
            "DSL 표현식의 AST depth·노드 수·indicator 호출 수·complexity를 반환합니다. " +
                "DB 저장 없음 — 등록 전 사전 검증용.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검증 성공 — complexity 응답"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류 / 한도 초과 / 화이트리스트 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/validate")
    fun validate(
        @RequestBody req: ValidateRequest,
    ): StrategyComplexityDto {
        val ast = parseOneDsl(req.expr)
        val complexity = strategyService.validateAndComputeComplexity(ast)
        return toComplexityDto(complexity)
    }

    @Operation(
        summary = "auto-disabled 전략 재활성화",
        description = "사용자가 식 수정 후 명시적으로 전략을 다시 활성화합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 재활성화 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}/enable")
    fun enable(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): StrategyResponse {
        strategyService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategyService.enable(id)
        val updated = strategyService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return toStrategyResponse(updated)
    }

    @Operation(
        summary = "전략 평가",
        description =
            "전략 DSL을 최신 캔들 데이터로 평가하여 BUY/SELL/HOLD 시그널을 반환합니다. " +
                "StrategyEvaluatorPort를 통해 strategy-engine JvmStrategyEvaluatorAdapter를 호출합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "평가 성공 — signal + strength + reason"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{id}/evaluate")
    fun evaluate(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: EvaluateRequest,
    ): EvaluateResponse {
        val strategy = strategyService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        val ast =
            strategy.definition.dsl.buyCondition
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 매수 조건 없음: id=$id")
        val candleRepository =
            candleRepositoryProvider.ifAvailable
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "캔들 저장소 bean 없음")
        val interval = parseInterval(req.interval)
        val candles = candleRepository.findLatestByCodeAndInterval(req.code, interval, evaluateCandleLookback)
        if (candles.isEmpty()) {
            throw StrategyException.InsufficientCandlesException(required = 1, actual = 0)
        }
        val result = strategyEvaluatorPort.evaluate(strategy.id ?: id, ast, candles)
        return EvaluateResponse(
            strategyId = id,
            signal = result.signal.name,
            strength = result.strength,
            reason = result.reason,
            evalTimeMs = result.evalTimeMs,
        )
    }

    // --- private helper ---

    private fun parseDslTexts(
        entry: String,
        exit: String?,
    ): Pair<DslNode, DslNode?> {
        val entryAst = parseOneDsl(entry)
        val exitAst = exit?.let { parseOneDsl(it) }
        return entryAst to exitAst
    }

    private fun parseOneDsl(text: String): DslNode {
        if (text.isBlank()) {
            throw StrategyException.DslSyntaxException("DSL 표현식이 비어 있습니다")
        }
        val ast = kisYamlDslParser.parse(text)
        kisYamlDslParser.validateStatic(ast)
        return ast
    }

    private fun parseInterval(interval: String): CandleInterval =
        runCatching { CandleInterval.fromCode(interval) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 캔들 인터벌: $interval")
        }

    private fun parseViActionPolicy(value: String?): ViActionPolicy? {
        val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ViActionPolicy.valueOf(normalized) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 VI 정책: $value")
        }
    }

    private fun toStrategyResponse(strategy: Strategy): StrategyResponse =
        StrategyResponse(
            id = strategy.id ?: 0L,
            userId = strategy.userId,
            name = strategy.definition.dsl.name,
            nameKo = strategy.definition.dsl.nameKo ?: "",
            universe = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            stopLossPct = strategy.definition.fixedStopLoss,
            takeProfitPct = strategy.definition.takeProfitPct,
            viActionPolicy = strategy.definition.viActionPolicy.name,
            complexity = toComplexityDto(strategy.complexity),
            disabledReason = strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt?.toString(),
            enabled = strategy.isEnabled(),
        )

    private fun toComplexityDto(complexity: StrategyComplexity): StrategyComplexityDto =
        StrategyComplexityDto(
            astDepth = complexity.astDepth,
            nodeCount = complexity.nodeCount,
            indicatorCallCount = complexity.indicatorCallCount,
            estimatedEvalTimeMs = complexity.estimatedEvalTimeMs,
            complexityClass = complexity.complexityClass.name,
        )
}

// --- DTO ---

@Schema(description = "전략 등록 요청")
data class CreateStrategyRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(
        description = "매수 조건 DSL 텍스트 (KIS 호환 1줄 형식)",
        example = "ma(5) crosses_above ma(20)",
        required = true,
    )
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장 (KOSPI·KOSDAQ·KONEX)", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임 (D1·60m·1d 등)", example = "D1")
    val tf: String = "D1",
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD). 생략 시 전략명 기반 기본값", example = "ALERT_ONLY")
    val viActionPolicy: String? = null,
)

@Schema(description = "전략 등록 응답")
data class CreateStrategyResponse(
    @field:Schema(description = "생성된 전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "DSL 복잡도 메타")
    val complexity: StrategyComplexityDto,
)

@Schema(description = "전략 응답")
data class StrategyResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val id: Long,
    @field:Schema(description = "소유자 사용자 ID", example = "1")
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20")
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String = "",
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String,
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String,
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD)", example = "ALERT_ONLY")
    val viActionPolicy: String = "ALERT_ONLY",
    @field:Schema(description = "DSL 복잡도 메타")
    val complexity: StrategyComplexityDto,
    @field:Schema(description = "비활성화 사유 (NULL=정상)", example = "NULL")
    val disabledReason: String,
    @field:Schema(description = "마지막 auto-disable 시각 (null=정상)", example = "null")
    val lastDisabledAt: String?,
    @field:Schema(description = "전략 활성 여부", example = "true")
    val enabled: Boolean,
)

@Schema(description = "DSL 복잡도")
data class StrategyComplexityDto(
    @field:Schema(description = "AST 최대 depth (한도 32)", example = "3")
    val astDepth: Int,
    @field:Schema(description = "AST 총 노드 수 (한도 10000)", example = "7")
    val nodeCount: Int,
    @field:Schema(description = "지표 호출 수 (한도 50)", example = "2")
    val indicatorCallCount: Int,
    @field:Schema(description = "예상 평가 시간 ms", example = "4")
    val estimatedEvalTimeMs: Int,
    @field:Schema(description = "복잡도 등급 (LOW·MEDIUM·HIGH·CRITICAL)", example = "LOW")
    val complexityClass: String,
)

@Schema(description = "DSL 검증 요청")
data class ValidateRequest(
    @field:Schema(
        description = "검증할 DSL 표현식",
        example = "ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5",
        required = true,
    )
    val expr: String,
)

@Schema(description = "전략 평가 요청")
data class EvaluateRequest(
    @field:Schema(description = "종목코드", example = "005930", required = true)
    val code: String,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String = "1d",
)

@Schema(description = "전략 평가 응답")
data class EvaluateResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "시그널 (BUY·SELL·HOLD)", example = "HOLD")
    val signal: String,
    @field:Schema(description = "시그널 강도 (0.0~1.0)", example = "0.0")
    val strength: BigDecimal,
    @field:Schema(description = "평가 근거 맵")
    val reason: Map<String, Any>,
    @field:Schema(description = "평가 소요 시간 ms", example = "0")
    val evalTimeMs: Long,
)

/** ComplexityClass 컴파일 참조 */
@Suppress("unused")
private val complexityClassRef = ComplexityClass.LOW
