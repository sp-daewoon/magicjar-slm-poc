package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.risk.RiskEventQueryService
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * RiskEvent REST 컨트롤러 — T-RISK Phase 2 (ADR 0067 §5-2 G-1).
 *
 * ### 엔드포인트
 * - GET /api/risk-events           위반 이력 시계열 페이징 조회 (ts DESC)
 * - GET /api/risk-events/{id}      단건 조회
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Tag(name = "RiskEvent", description = "Risk 위반 이벤트 이력 조회 — T-RISK Phase 2 (ADR 0067 §5-2 G-1)")
@RestController
@RequestMapping("/api/risk-events")
class RiskEventController(
    private val riskEventQueryService: RiskEventQueryService,
) {
    private val log = LoggerFactory.getLogger(RiskEventController::class.java)

    // ─── GET /api/risk-events ─────────────────────────────────────────────────

    @Operation(
        summary = "Risk 위반 이벤트 시계열 페이징 조회",
        description =
            "사용자의 risk 위반 이벤트 목록을 시계열로 조회합니다 (ts DESC). " +
                "from·to는 ISO-8601 UTC 형식 (예: `2026-05-12T00:00:00Z`). " +
                "BLOCKED 이벤트 위주로 영속됩니다 (ADR 0067 §9-1). " +
                "ADR 0067 §5-2 G-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "위반 이벤트 목록 조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "파라미터 형식 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(
            description = "조회 시작 시각 (ISO-8601 UTC)",
            example = "2026-05-01T00:00:00Z",
            required = true,
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant,
        @Parameter(
            description = "조회 종료 시각 (ISO-8601 UTC)",
            example = "2026-05-12T23:59:59Z",
            required = true,
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "50")
        @RequestParam(defaultValue = "50") size: Int,
    ): RiskEventPageResponse {
        if (from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 이전이어야 합니다")
        }
        log.debug("risk_event 목록 조회: userId={} from={} to={} page={} size={}", userId, from, to, page, size)
        val result = riskEventQueryService.list(userId, from, to, page, size)
        return RiskEventPageResponse(
            content = result.content.map { toResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    // ─── GET /api/risk-events/{id} ────────────────────────────────────────────

    @Operation(
        summary = "Risk 위반 이벤트 단건 조회",
        description = "특정 risk 위반 이벤트를 조회합니다. ADR 0067 §5-2 G-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "위반 이벤트 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이벤트 없음 또는 접근 권한 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "이벤트 ID", example = "123", required = true)
        @PathVariable id: Long,
    ): RiskEventResponse {
        log.debug("risk_event 단건 조회: userId={} id={}", userId, id)
        val event = riskEventQueryService.get(userId, id)
        return toResponse(event)
    }

    // --- private helper ---

    private fun toResponse(event: RiskEvent): RiskEventResponse =
        RiskEventResponse(
            id = event.id ?: 0L,
            userId = event.userId,
            signalId = event.signalId,
            rule = event.rule.name,
            verdict = event.verdict.name,
            reason = event.reason,
            createdAt = event.createdAt.toString(),
        )
}

/**
 * RiskEvent 단건 응답 DTO.
 */
@Schema(description = "Risk 위반 이벤트 응답")
data class RiskEventResponse(
    @Schema(description = "이벤트 ID", example = "123")
    val id: Long,
    @Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @Schema(description = "관련 시그널 ID (null이면 직접 위반)", example = "456")
    val signalId: Long?,
    @Schema(
        description =
            "위반 룰 (POSITION_LIMIT·DAILY_LOSS_LIMIT·MAX_CONCURRENT_POSITIONS·" +
                "TRADE_FREQUENCY_MINUTE·TRADE_FREQUENCY_DAY·KILL_SWITCH_ON·USER_DISABLED)",
        example = "POSITION_LIMIT",
    )
    val rule: String,
    @Schema(description = "평가 결과 (ALLOWED·BLOCKED)", example = "BLOCKED")
    val verdict: String,
    @Schema(description = "위반 사유", example = "포지션 한도 초과: 요청 200만원 > 한도 100만원")
    val reason: String?,
    @Schema(description = "이벤트 생성 시각 (ISO-8601 UTC)", example = "2026-05-12T09:30:00Z")
    val createdAt: String,
)

/**
 * RiskEvent 페이징 응답 DTO.
 */
@Schema(description = "Risk 위반 이벤트 페이징 응답")
data class RiskEventPageResponse(
    @Schema(description = "이벤트 목록")
    val content: List<RiskEventResponse>,
    @Schema(description = "현재 페이지 번호 (0-based)", example = "0")
    val page: Int,
    @Schema(description = "페이지 크기", example = "50")
    val size: Int,
    @Schema(description = "전체 항목 수", example = "123")
    val totalElements: Long,
    @Schema(description = "전체 페이지 수", example = "3")
    val totalPages: Int,
)
