package kr.spdaewoon.magicjar.api.controller.reconcile

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.service.reconcile.ManualTriggerResult
import kr.spdaewoon.magicjar.api.service.reconcile.ReconcileManualTriggerService
import kr.spdaewoon.magicjar.api.service.reconcile.ReconcileQueryService
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Reconcile REST 컨트롤러 — T-RECON Phase 2 (ADR 0041·0070 §6 + spec §2-3).
 *
 * ### 엔드포인트 3건
 * - POST /api/risk/reconcile/{userId}         — 수동 reconcile trigger (idempotency)
 * - GET  /api/risk/reconcile/runs             — 이력 시계열 페이징 조회
 * - GET  /api/risk/reconcile/runs/{id}        — 단건 상세 조회
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * userId mismatch 시 404 LOCK (ADR 0010 multi-tenant 격리 정합).
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Tag(name = "Reconcile", description = "KIS 잔고 정합 작업 (broker reconciliation) — ADR 0041·0070")
@RestController
@RequestMapping("/api/risk/reconcile")
class ReconcileController(
    private val reconcileManualTriggerService: ReconcileManualTriggerService,
    private val reconcileQueryService: ReconcileQueryService,
) {
    private val log = LoggerFactory.getLogger(ReconcileController::class.java)

    // ─── POST /api/risk/reconcile/{userId} — 수동 trigger ────────────────────

    @Operation(
        summary = "수동 reconcile trigger",
        description =
            "사용자가 즉시 1회 KIS 잔고를 가져와 내부 position과 정합 비교합니다. " +
                "5분 내 중복 호출은 직전 실행 결과를 반환합니다 (idempotency — Redis reconcile:lastrun 캐시). " +
                "KIS 잔고와 내부 position 수량이 1주 이상 차이나면 drift 검출 → KillSwitch 자동 ON. " +
                "ADR 0041·0070 §2-11 자본 보호 흐름 정합.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "신규 reconcile trigger 완료 (Kafka 비동기 처리 시작)",
            content = [Content(schema = Schema(implementation = ReconcileTriggerResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "200",
            description = "idempotency cache hit — 5분 내 직전 실행 결과 반환",
            content = [Content(schema = Schema(implementation = ReconcileTriggerResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "다른 reconcile 실행 중 (lock busy — 5분 후 재시도)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{userId}")
    fun triggerManualReconcile(
        @Parameter(description = "대상 사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @PathVariable userId: Long,
    ): ResponseEntity<ReconcileTriggerResponseDto> {
        log.info("POST /api/risk/reconcile/{} — 수동 trigger", userId)
        val result = reconcileManualTriggerService.trigger(userId)
        return when (result) {
            is ManualTriggerResult.Triggered ->
                ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(
                        ReconcileTriggerResponseDto(
                            triggered = true,
                            cached = false,
                            eventId = result.eventId,
                            lastRunId = null,
                            lastRun = null,
                        ),
                    )
            is ManualTriggerResult.Cached ->
                ResponseEntity.ok(
                    ReconcileTriggerResponseDto(
                        triggered = false,
                        cached = true,
                        eventId = null,
                        lastRunId = result.lastRunId,
                        lastRun = result.run?.let { toDto(it) },
                    ),
                )
        }
    }

    // ─── GET /api/risk/reconcile/runs — 이력 페이징 조회 ─────────────────────

    @Operation(
        summary = "reconcile 실행 이력 조회",
        description =
            "사용자별 reconcile_run 시계열 조회 (페이징, started_at DESC). " +
                "status 필드: RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR. " +
                "drift 검출 이력 모니터링, KillSwitch auto-ON 이력 추적에 활용합니다. " +
                "ADR 0041·0070 §2-3 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "이력 목록 조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "파라미터 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/runs")
    fun listRuns(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지당 row 수 (최대 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): ReconcileRunPageResponse {
        val effectiveSize = size.coerceIn(1, 100)
        log.debug("GET /api/risk/reconcile/runs — userId={} page={} size={}", userId, page, effectiveSize)
        val runs = reconcileQueryService.listRuns(userId, page, effectiveSize)
        return ReconcileRunPageResponse(
            content = runs.content.map { toDto(it) },
            page = runs.number,
            size = runs.size,
            totalElements = runs.totalElements,
            totalPages = runs.totalPages,
        )
    }

    // ─── GET /api/risk/reconcile/runs/{id} — 단건 상세 ────────────────────────

    @Operation(
        summary = "reconcile 단건 상세 조회",
        description =
            "특정 reconcile_run의 상세 정보를 조회합니다. " +
                "drift_count·last_error·trigger_source 포함. " +
                "userId 불일치 시 404 반환 (multi-tenant 격리 — ADR 0010). " +
                "ADR 0041·0070 §6-3 정합.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "단건 조회 성공",
            content = [Content(schema = Schema(implementation = ReconcileRunDto::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "해당 run 없음 또는 userId 불일치",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/runs/{id}")
    fun findDetail(
        @Parameter(description = "reconcile run ID", example = "1001", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID (격리 검증 — 불일치 시 404)", example = "12345678", required = true)
        @RequestParam userId: Long,
    ): ReconcileRunDto {
        log.debug("GET /api/risk/reconcile/runs/{} — userId={}", id, userId)
        val run = reconcileQueryService.findRunDetail(id, userId)
        return toDto(run)
    }

    // --- private helper ---

    private fun toDto(run: ReconcileRun): ReconcileRunDto =
        ReconcileRunDto(
            id = run.id,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            driftCount = run.driftCount,
            status = run.status.name,
            lastError = run.lastError,
            triggerSource = run.triggerSource.name,
        )
}

/**
 * 수동 reconcile trigger 응답 DTO.
 *
 * - triggered=true, cached=false → 202 Accepted (Kafka 비동기 trigger 완료)
 * - triggered=false, cached=true → 200 OK (5분 내 idempotency cache hit)
 */
@Schema(description = "수동 reconcile trigger 응답")
data class ReconcileTriggerResponseDto(
    @field:Schema(description = "신규 reconcile trigger 완료 여부", example = "true")
    val triggered: Boolean,
    @field:Schema(description = "idempotency cache hit 여부 (5분 내 재호출)", example = "false")
    val cached: Boolean,
    @field:Schema(description = "Kafka 이벤트 ID (triggered=true 시)", example = "550e8400-e29b-41d4-a716-446655440000")
    val eventId: String?,
    @field:Schema(description = "직전 reconcile run ID (cached=true 시)", example = "999")
    val lastRunId: Long?,
    @field:Schema(description = "직전 reconcile run 상세 (cached=true + DB 조회 성공 시)")
    val lastRun: ReconcileRunDto?,
)

/**
 * reconcile 실행 기록 DTO (Swagger 4단 — 사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "reconcile 실행 기록 (broker reconciliation run)")
data class ReconcileRunDto(
    @field:Schema(description = "실행 ID (BIGSERIAL)", example = "1001")
    val id: Long?,
    @field:Schema(description = "실행 시작 시각 (ISO-8601 UTC)", example = "2026-05-14T01:00:00Z")
    val startedAt: Instant?,
    @field:Schema(description = "실행 종료 시각 (RUNNING 일 때 null)", example = "2026-05-14T01:00:02Z")
    val finishedAt: Instant?,
    @field:Schema(description = "drift 검출 종목 수 (0이면 정합)", example = "2")
    val driftCount: Int,
    @field:Schema(
        description = "실행 상태 (RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR)",
        example = "DRIFT",
        allowableValues = ["RUNNING", "RECONCILED", "DRIFT", "DRIFT_PRICE", "ERROR"],
    )
    val status: String,
    @field:Schema(description = "오류 메시지 (status=ERROR 일 때만)", example = "EGW00201 (TPS 초과)")
    val lastError: String?,
    @field:Schema(
        description = "트리거 출처 (CRON·MANUAL·API)",
        example = "MANUAL",
        allowableValues = ["CRON", "MANUAL", "API"],
    )
    val triggerSource: String,
)

/**
 * reconcile 실행 이력 페이징 응답 DTO.
 */
@Schema(description = "reconcile 실행 이력 페이징 응답")
data class ReconcileRunPageResponse(
    @field:Schema(description = "실행 기록 목록")
    val content: List<ReconcileRunDto>,
    @field:Schema(description = "현재 페이지 번호 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지당 row 수", example = "20")
    val size: Int,
    @field:Schema(description = "전체 항목 수", example = "42")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "3")
    val totalPages: Int,
)
