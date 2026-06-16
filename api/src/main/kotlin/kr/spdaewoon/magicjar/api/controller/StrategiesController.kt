package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.ExitLimits
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * 전략 REST 컨트롤러 Phase 2 — CRUD + status 토글 + reactivate (ADR 0066 §2-6 F-1).
 *
 * 경로: `/api/strategies` (Phase 2 신규 — 복수형)
 * 기존 `/api/strategy` (StrategyController)는 하위 호환성 유지.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Tag(name = "Strategies v2", description = "전략 CRUD + status 토글 — T-SIG Phase 2 (ADR 0066 §2-6 F-1)")
@RestController
@RequestMapping("/api/strategies")
class StrategiesController(
    private val strategyService: StrategyService,
    private val kisYamlDslParser: KisYamlDslParser,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategiesController::class.java)

    // ─── POST /api/strategies ─────────────────────────────────────────────────

    @Operation(
        summary = "전략 등록",
        description =
            "텍스트 DSL 1줄(예: `ma(5) crosses_above ma(20)`)을 등록합니다. " +
                "ADR 0047 정합 — static 검증 후 complexity 응답. " +
                "등록 후 status=PAUSED로 시작하며 백테스트 준비 후 사용자가 ACTIVE로 전환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "전략 등록 성공 — strategyId + complexity 반환"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류 / AST depth 초과 / 노드 수 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody req: StrategyCreateRequest,
    ): StrategyDetailResponse {
        log.info("전략 등록 요청: userId={} name={}", req.userId, req.name)
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
                codes = req.codes,
                intervals = req.intervals,
                stopLossPct = req.stopLossPct,
                takeProfitPct = req.takeProfitPct,
                scaleIn = toScaleInDomain(req.scaleIn),
                partialExits = toPartialExitsDomain(req.partialExits),
                minHoldBars = req.minHoldBars,
                viActionPolicy = parseViActionPolicy(req.viActionPolicy),
                requireCodes = true,
            )
        return toDetailResponse(strategy)
    }

    // ─── GET /api/strategies ──────────────────────────────────────────────────

    @Operation(
        summary = "전략 목록 조회 (페이징)",
        description = "사용자의 전략 목록을 반환합니다. DELETED 상태 제외. userId 파라미터로 소유자 필터.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 목록 조회 성공"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): StrategyPageResponse {
        val result = strategyService.listByUserId(userId, page, size)
        return StrategyPageResponse(
            content = result.content.map { toDetailResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast,
        )
    }

    // ─── GET /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 단건 조회",
        description = "전략 ID로 단건 조회합니다. DELETED 상태는 404 반환.",
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
    ): StrategyDetailResponse {
        val strategy =
            strategyService.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return toDetailResponse(strategy)
    }

    // ─── PUT /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 수정",
        description =
            "전략 DSL을 수정합니다. " +
                "수정 후 complexity 재계산. DELETED 상태 전략은 수정 불가.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}")
    fun update(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyUpdateRequest,
    ): StrategyDetailResponse {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")

        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.update(
                id = id,
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
                codes = req.codes,
                intervals = req.intervals,
                stopLossPct = req.stopLossPct,
                takeProfitPct = req.takeProfitPct,
                scaleIn = toScaleInDomain(req.scaleIn),
                partialExits = toPartialExitsDomain(req.partialExits),
                minHoldBars = req.minHoldBars,
                viActionPolicy = parseViActionPolicy(req.viActionPolicy),
                requireCodes = true,
            )
        log.info("전략 수정: id={}", id)
        return toDetailResponse(strategy)
    }

    // ─── DELETE /api/strategies/{id} ──────────────────────────────────────────

    @Operation(
        summary = "전략 삭제 (soft delete)",
        description =
            "전략을 soft delete 합니다 (status=DELETED). " +
                "audit 보존 — DB 레코드는 유지됨. ADR 0066 §2-6 F-1 ANY → DELETED.",
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ) {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategyService.softDelete(id)
    }

    // ─── PUT /api/strategies/{id}/status ──────────────────────────────────────

    @Operation(
        summary = "전략 상태 토글",
        description =
            "전략 status를 전환합니다 (ADR 0066 §2-6 F-1 transition 매트릭스).\n\n" +
                "허용 전환:\n" +
                "- `ACTIVE → PAUSED` (일시 중지)\n" +
                "- `PAUSED → ACTIVE` (재개)\n" +
                "- `BUY_BLOCKED → ACTIVE` (신규 매수 재개)\n" +
                "- `BUY_BLOCKED → PAUSED` (전체 자동매매 중지)\n" +
                "- `DISABLED → ACTIVE` (사용자 명시 reactivate)\n" +
                "- `ANY → DELETED` (soft delete — DELETE /api/strategies/{id} 권장)\n\n" +
                "DISABLED·BUY_BLOCKED 직접 설정 불가 (시스템 보호 장치 전용).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 전환 성공"),
        ApiResponse(
            responseCode = "400",
            description = "허용되지 않은 전환 (예: DISABLED 직접 설정)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}/status")
    fun toggleStatus(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyStatusRequest,
    ): StrategyDetailResponse {
        val newStatus =
            runCatching { StrategyStatus.valueOf(req.status.uppercase()) }.getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효하지 않은 status 값: ${req.status} (허용: ACTIVE·PAUSED·DELETED)",
                )
            }
        val updated = strategyService.toggleStatus(id, newStatus)
        return toDetailResponse(updated)
    }

    // ─── GET /api/strategies/{id}/position ────────────────────────────────────

    @Operation(
        summary = "전략별 포지션 조회",
        description =
            "이 전략이 산 물량(전략별 포지션 귀속 — ADR 0110)을 조회합니다. " +
                "수량·평단·진행 회차(filledLots)·실현손익을 반환합니다. " +
                "보유가 없으면 quantity=0(또는 행 없음) — '보유 없음'으로 표시하세요(mock 합성 0건). " +
                "전략에 종목 1개 매핑이 일반적이므로 단건이지만 다종목 전략 대비 배열로 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략별 포지션 조회 성공 (보유 없으면 빈 배열)"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/position")
    fun getPosition(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): List<StrategyPositionResponse> {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return strategyPositionReadJpaRepository
            .findByStrategyId(id)
            .map { entity ->
                StrategyPositionResponse(
                    strategyId = entity.strategyId,
                    userId = entity.userId,
                    code = entity.code,
                    codeName = codeNamesFor(listOf(entity.code))[entity.code],
                    quantity = entity.quantity,
                    avgPrice = entity.avgPrice,
                    filledLots = entity.filledLots,
                    realizedPnl = entity.realizedPnl,
                    isClosed = entity.quantity == 0,
                    updatedAt = entity.updatedAt.toString(),
                )
            }
    }

    // ─── private helper ───────────────────────────────────────────────────────

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

    private fun parseViActionPolicy(value: String?): ViActionPolicy? {
        val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ViActionPolicy.valueOf(normalized) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 VI 정책: $value")
        }
    }

    private fun toDetailResponse(strategy: Strategy): StrategyDetailResponse {
        val activationBlockedReason = strategyService.activationBlockedReason(strategy)
        return StrategyDetailResponse(
            id = strategy.id ?: 0L,
            userId = strategy.userId,
            name = strategy.definition.dsl.name,
            nameKo = strategy.definition.dsl.nameKo ?: "",
            origin = strategy.origin.name,
            universe = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            stopLossPct = strategy.definition.fixedStopLoss,
            takeProfitPct = strategy.definition.takeProfitPct,
            minHoldBars = strategy.definition.minHoldBars,
            viActionPolicy = strategy.definition.viActionPolicy.name,
            warnings = strategyService.exitNoiseWarnings(strategy),
            codes = strategy.codes,
            codeNames = codeNamesFor(strategy.codes),
            intervals = strategy.intervals.ifEmpty { listOf(strategy.definition.tf) },
            buyCondition = strategy.definition.dsl.buyConditionText,
            sellCondition = strategy.definition.dsl.sellConditionText,
            complexity = toComplexityDto(strategy.complexity),
            scaleIn = toScaleInResponse(strategy.definition.scaleIn),
            partialExits = toPartialExitsResponse(strategy.definition.partialExits),
            disabledReason = strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt?.toString(),
            enabled = strategy.isEnabled(),
            status = strategy.status.name,
            activationReady = activationBlockedReason == null,
            activationBlockedReason = activationBlockedReason,
            createdAt = strategy.createdAt.toString(),
            updatedAt = strategy.updatedAt.toString(),
        )
    }

    /**
     * 분할 매수 요청 DTO → 도메인 [ScaleInPlan] (ADR 0111 · 분할매수 R3-a).
     *
     * 도메인 init이 cap(회수 1~10·비중 합 100±0.01) 검증 — [IllegalArgumentException]을 400으로 변환해
     * 사용자에게 입력 오류를 그대로 노출한다. null이면 단일 진입(현행 하위호환).
     */
    private fun toScaleInDomain(req: ScaleInRequest?): ScaleInPlan? {
        if (req == null) return null
        return runCatching {
            ScaleInPlan(
                totalSlices = req.totalSlices,
                sliceWeights = req.sliceWeights?.takeIf { it.isNotEmpty() },
                reentryTrigger =
                    req.reentryTrigger
                        ?.let { ReentryTrigger.valueOf(it.uppercase()) }
                        ?: ReentryTrigger.SIGNAL_REPEAT,
                priceDropPct = req.priceDropPct,
                minSliceIntervalSec = req.minSliceIntervalSec,
            )
        }.getOrElse { e ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할 매수 설정이 올바르지 않습니다: ${e.message}")
        }
    }

    /**
     * 분할 익절 요청 DTO 리스트 → 도메인 [PartialExit] 리스트 (ADR 0111 · 분할매수 R3-a).
     *
     * 개별 단계 검증(triggerProfitPct>0·exitWeightPct∈(0,100])은 [PartialExit] init,
     * 단계 cap(≤5)·weight 합(≤100)은 [StrategyDefinition] init 책임 — 위반 시 400.
     */
    private fun toPartialExitsDomain(reqs: List<PartialExitRequest>?): List<PartialExit>? {
        if (reqs.isNullOrEmpty()) return null
        return runCatching {
            val exits = reqs.map { PartialExit(triggerProfitPct = it.triggerProfitPct, exitWeightPct = it.exitWeightPct) }
            // 단계 cap(≤5)·weight 합(≤100) 교차 검증 — StrategyDefinition init과 동일 규칙을 컨트롤러에서 선검증해
            // service의 StrategyDefinition 생성 시 IllegalArgumentException(→500)이 아니라 400으로 노출.
            ExitLimits.validatePartialExits(exits)
            exits
        }.getOrElse { e ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할 익절 설정이 올바르지 않습니다: ${e.message}")
        }
    }

    private fun toScaleInResponse(plan: ScaleInPlan?): ScaleInResponse? {
        if (plan == null) return null
        return ScaleInResponse(
            totalSlices = plan.totalSlices,
            sliceWeights = plan.sliceWeights,
            reentryTrigger = plan.reentryTrigger.name,
            priceDropPct = plan.priceDropPct,
            minSliceIntervalSec = plan.minSliceIntervalSec,
        )
    }

    private fun toPartialExitsResponse(exits: List<PartialExit>?): List<PartialExitResponse>? {
        if (exits.isNullOrEmpty()) return null
        return exits.map { PartialExitResponse(triggerProfitPct = it.triggerProfitPct, exitWeightPct = it.exitWeightPct) }
    }

    private fun codeNamesFor(codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        return instrumentJpaRepository
            .findAllById(codes)
            .associate { it.code to it.name }
    }

    private fun toComplexityDto(complexity: StrategyComplexity): StrategyComplexityResponse =
        StrategyComplexityResponse(
            astDepth = complexity.astDepth,
            nodeCount = complexity.nodeCount,
            indicatorCallCount = complexity.indicatorCallCount,
            estimatedEvalTimeMs = complexity.estimatedEvalTimeMs,
            complexityClass = complexity.complexityClass.name,
        )

    @Suppress("unused")
    private val complexityClassRef = ComplexityClass.LOW
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "전략 등록 요청 (Phase 2)")
data class StrategyCreateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(
        description = "매수 조건 DSL 텍스트",
        example = "ma(5) crosses_above ma(20)",
        required = true,
    )
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장 (KOSPI·KOSDAQ)", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임 (D1·5m·15m 등)", example = "D1")
    val tf: String = "D1",
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(
        description = "잔익절 방어 B안 — 최소 보유 캔들 수. 진입 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절은 항상 작동). null·0=비활성(현행)",
        example = "3",
    )
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD). 생략 시 전략명 기반 기본값", example = "ALERT_ONLY")
    val viActionPolicy: String? = null,
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입). toggle OFF 동안 설정 시 ACTIVE 차단(DRAFT 저장 가능)")
    val scaleIn: ScaleInRequest? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절). 단계 ≤ 5 · weight 합 ≤ 100")
    val partialExits: List<PartialExitRequest>? = null,
)

@Schema(description = "전략 수정 요청 (Phase 2)")
data class StrategyUpdateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "ma(5) crosses_above ma(20)", required = true)
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String = "D1",
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(
        description = "잔익절 방어 B안 — 최소 보유 캔들 수. 진입 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절은 항상 작동). null·0=비활성(현행)",
        example = "3",
    )
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD). 생략 시 기존값 유지", example = "ALERT_ONLY")
    val viActionPolicy: String? = null,
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입)")
    val scaleIn: ScaleInRequest? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절). 단계 ≤ 5 · weight 합 ≤ 100")
    val partialExits: List<PartialExitRequest>? = null,
)

@Schema(description = "전략 상태 전환 요청")
data class StrategyStatusRequest(
    @field:Schema(
        description = "새 status (ACTIVE·PAUSED·DELETED — DISABLED·BUY_BLOCKED 직접 설정 불가)",
        example = "PAUSED",
        required = true,
        allowableValues = ["ACTIVE", "PAUSED", "DELETED"],
    )
    val status: String,
)

@Schema(description = "전략 상세 응답 (Phase 2)")
data class StrategyDetailResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val id: Long,
    @field:Schema(description = "소유자 사용자 ID", example = "1")
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20")
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String = "",
    @field:Schema(
        description = "전략 출처 (USER_DSL=일반 전략, PAPER_DISCOVERY=단타 발굴 페이퍼 승격 전략)",
        example = "USER_DSL",
        allowableValues = ["USER_DSL", "PAPER_DISCOVERY"],
    )
    val origin: String = "USER_DSL",
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String,
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String,
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(description = "잔익절 방어 B안 — 최소 보유 캔들 수. null=비활성(현행)", example = "3")
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD)", example = "ALERT_ONLY")
    val viActionPolicy: String = "ALERT_ONLY",
    @field:Schema(
        description = "잔익절 방어 A안 — 저장 경고(비차단). 익절 목표가 왕복 거래비용 근접·미만 시 1건. 빈 배열=경고 없음",
        example = "[]",
    )
    val warnings: List<String> = emptyList(),
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "종목 코드별 종목명", example = "{\"005930\":\"삼성전자\"}")
    val codeNames: Map<String, String> = emptyMap(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "ma(5) crosses_above ma(20)")
    val buyCondition: String? = null,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val sellCondition: String? = null,
    @field:Schema(description = "DSL 복잡도 메타")
    val complexity: StrategyComplexityResponse,
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입)")
    val scaleIn: ScaleInResponse? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절)")
    val partialExits: List<PartialExitResponse>? = null,
    @field:Schema(description = "비활성화 사유 (NULL=정상)", example = "NULL")
    val disabledReason: String,
    @field:Schema(description = "마지막 auto-disable 시각", example = "null")
    val lastDisabledAt: String?,
    @field:Schema(description = "전략 활성 여부", example = "true")
    val enabled: Boolean,
    @field:Schema(
        description = "전략 상태 (ACTIVE·BUY_BLOCKED·PAUSED·DISABLED·DELETED)",
        example = "ACTIVE",
        allowableValues = ["ACTIVE", "BUY_BLOCKED", "PAUSED", "DISABLED", "DELETED"],
    )
    val status: String,
    @field:Schema(description = "자동매매 시작 가능 여부", example = "false")
    val activationReady: Boolean = false,
    @field:Schema(description = "자동매매 시작 차단 사유", example = "성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다")
    val activationBlockedReason: String? = null,
    @field:Schema(description = "생성 시각", example = "2026-05-29T03:51:36Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각", example = "2026-05-29T03:51:36Z")
    val updatedAt: String,
)

@Schema(description = "전략 페이징 응답")
data class StrategyPageResponse(
    @field:Schema(description = "전략 목록")
    val content: List<StrategyDetailResponse>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @field:Schema(description = "총 전략 수", example = "5")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "1")
    val totalPages: Int,
    @field:Schema(description = "마지막 페이지 여부", example = "true")
    val last: Boolean,
)

@Schema(description = "DSL 복잡도 응답")
data class StrategyComplexityResponse(
    @field:Schema(description = "AST 최대 depth", example = "3")
    val astDepth: Int,
    @field:Schema(description = "AST 총 노드 수", example = "7")
    val nodeCount: Int,
    @field:Schema(description = "지표 호출 수", example = "2")
    val indicatorCallCount: Int,
    @field:Schema(description = "예상 평가 시간 ms", example = "4")
    val estimatedEvalTimeMs: Int,
    @field:Schema(description = "복잡도 등급 (LOW·MEDIUM·HIGH·CRITICAL)", example = "LOW")
    val complexityClass: String,
)

@Schema(description = "분할 매수(scale-in) 요청 — ADR 0111 §2-1")
data class ScaleInRequest(
    @field:Schema(description = "분할 회수 N (1~10). 1이면 단일 진입(현행)", example = "3", required = true)
    val totalSlices: Int,
    @field:Schema(description = "회당 비중%(합 100±0.01). null이면 균등 분할", example = "[50, 30, 20]")
    val sliceWeights: List<BigDecimal>? = null,
    @field:Schema(
        description = "추가 진입 트리거 (SIGNAL_REPEAT·PRICE_DROP_PCT). default SIGNAL_REPEAT",
        example = "SIGNAL_REPEAT",
        allowableValues = ["SIGNAL_REPEAT", "PRICE_DROP_PCT"],
    )
    val reentryTrigger: String? = null,
    @field:Schema(description = "PRICE_DROP_PCT 시 직전 회차 체결가 대비 하락폭%(음수)", example = "-3.0")
    val priceDropPct: BigDecimal? = null,
    @field:Schema(description = "회차 간 최소 간격(초). default 60", example = "60")
    val minSliceIntervalSec: Int? = null,
)

@Schema(description = "분할 매수(scale-in) 응답 — ADR 0111 §2-1")
data class ScaleInResponse(
    @field:Schema(description = "분할 회수 N", example = "3")
    val totalSlices: Int,
    @field:Schema(description = "회당 비중%(null이면 균등)", example = "[50, 30, 20]")
    val sliceWeights: List<BigDecimal>? = null,
    @field:Schema(description = "추가 진입 트리거", example = "SIGNAL_REPEAT")
    val reentryTrigger: String,
    @field:Schema(description = "PRICE_DROP_PCT 하락폭%(음수)", example = "-3.0")
    val priceDropPct: BigDecimal? = null,
    @field:Schema(description = "회차 간 최소 간격(초)", example = "60")
    val minSliceIntervalSec: Int? = null,
)

@Schema(description = "분할 익절(partial take-profit) 단계 요청 — ADR 0111 §2-2")
data class PartialExitRequest(
    @field:Schema(description = "평단 대비 수익률%(양수) 도달 시 발화", example = "3.0", required = true)
    val triggerProfitPct: BigDecimal,
    @field:Schema(description = "보유 수량의 청산 비율%((0, 100])", example = "50", required = true)
    val exitWeightPct: BigDecimal,
)

@Schema(description = "분할 익절(partial take-profit) 단계 응답 — ADR 0111 §2-2")
data class PartialExitResponse(
    @field:Schema(description = "평단 대비 수익률%", example = "3.0")
    val triggerProfitPct: BigDecimal,
    @field:Schema(description = "보유 수량의 청산 비율%", example = "50")
    val exitWeightPct: BigDecimal,
)

@Schema(description = "전략별 포지션 응답 — ADR 0110 전략별 포지션 귀속")
data class StrategyPositionResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val code: String,
    @field:Schema(description = "종목명", example = "삼성전자")
    val codeName: String? = null,
    @field:Schema(description = "이 전략 보유 수량 (분할 누적 - 청산). 0=청산", example = "10")
    val quantity: Int,
    @field:Schema(description = "이 전략 평단 (분할 가중평균)", example = "70000.0000")
    val avgPrice: BigDecimal,
    @field:Schema(description = "scale-in 진행 회차 (filledLots)", example = "2")
    val filledLots: Int,
    @field:Schema(description = "이 전략 누적 실현 손익", example = "15000.0000")
    val realizedPnl: BigDecimal,
    @field:Schema(description = "전량 청산 여부 (quantity=0)", example = "false")
    val isClosed: Boolean,
    @field:Schema(description = "마지막 갱신 시각 (ISO-8601 UTC)", example = "2026-06-05T09:30:00Z")
    val updatedAt: String,
)
