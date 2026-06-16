package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.risk.RiskConfigService
import kr.spdaewoon.magicjar.api.service.risk.RiskConfigUpdateRequest
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * RiskConfig REST 컨트롤러 — T-RISK Phase 2 (ADR 0067 §5-1 H-1).
 *
 * ### 엔드포인트
 * - GET  /api/risk-config           사용자 risk 설정 조회 (부재 시 H-1 default seed)
 * - PUT  /api/risk-config           risk 설정 갱신
 * - POST /api/risk-config/reset     default seed로 reset
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 * 사용자 메모리 feedback_no_mock_data 정합 — mock 합성 0건.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Tag(name = "RiskConfig", description = "사용자 risk 설정 관리 — T-RISK Phase 2 (ADR 0067 §5-1 H-1)")
@RestController
@RequestMapping("/api/risk-config")
class RiskConfigController(
    private val riskConfigService: RiskConfigService,
) {
    private val log = LoggerFactory.getLogger(RiskConfigController::class.java)

    // ─── GET /api/risk-config ─────────────────────────────────────────────────

    @Operation(
        summary = "Risk 설정 조회",
        description =
            "사용자의 risk 설정을 조회합니다. " +
                "미설정 사용자는 H-1 default seed(포지션한도 100만원·10%·DLL 3%·동시5·분당3·일50·임계10)를 " +
                "자동 생성 후 반환합니다. ADR 0067 §5-1 H-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Risk 설정 조회 성공"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun get(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
    ): RiskConfigResponse {
        log.debug("risk_config 조회: userId={}", userId)
        val config = riskConfigService.getOrDefault(userId)
        return toResponse(config)
    }

    // ─── PUT /api/risk-config ──────────────────────────────────────────────────

    @Operation(
        summary = "Risk 설정 갱신",
        description =
            "사용자의 risk 설정을 갱신합니다. " +
                "null 필드는 기존 값을 유지합니다. " +
                "isDailyLossLimitActive=false이면 DLL을 비활성화합니다. " +
                "활성 상태에서는 dllAbs XOR dllPct (둘 다 non-null=오류). " +
                "ADR 0067 A-1·B-1·C-1·D-1·F-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Risk 설정 갱신 성공"),
        ApiResponse(
            responseCode = "400",
            description = "유효성 검증 실패 (포지션 한도 범위·DLL XOR 위반 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping
    fun update(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @RequestBody request: RiskConfigUpdateBody,
    ): RiskConfigResponse {
        log.info("risk_config 갱신: userId={}", userId)
        val updateRequest =
            RiskConfigUpdateRequest(
                maxPositionAmount = request.maxPositionAmount,
                maxPositionPercent = request.maxPositionPercent,
                isDailyLossLimitActive = request.isDailyLossLimitActive,
                dllAbs = request.dllAbs,
                dllPct = request.dllPct,
                maxConcurrentPositions = request.maxConcurrentPositions,
                maxTradesPerMinute = request.maxTradesPerMinute,
                maxTradesPerDay = request.maxTradesPerDay,
                autoPauseThreshold = request.autoPauseThreshold,
            )
        return try {
            val config = riskConfigService.save(userId, updateRequest)
            toResponse(config)
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                e.message,
            )
        }
    }

    // ─── POST /api/risk-config/reset ──────────────────────────────────────────

    @Operation(
        summary = "Risk 설정 초기화",
        description =
            "사용자의 risk 설정을 H-1 default seed로 초기화합니다. " +
                "(포지션한도 100만원·10%·DLL 3%·동시5·분당3·일50·임계10). " +
                "ADR 0067 §5-1 H-1 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Risk 설정 초기화 성공"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/reset")
    fun reset(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
    ): RiskConfigResponse {
        log.info("risk_config reset: userId={}", userId)
        val config = riskConfigService.reset(userId)
        return toResponse(config)
    }

    // --- private helper ---

    private fun toResponse(config: RiskConfig): RiskConfigResponse =
        RiskConfigResponse(
            userId = config.userId,
            maxPositionAmount = config.maxPositionAmount,
            maxPositionPercent = config.maxPositionPercent,
            dllAbs = config.dailyLossLimitAmount,
            dllPct = config.dailyLossLimitPercent,
            isDailyLossLimitActive = config.isDailyLossLimitActive,
            maxConcurrentPositions = config.maxConcurrentPositions,
            maxTradesPerMinute = config.maxTradesPerMinute,
            maxTradesPerDay = config.maxTradesPerDay,
            autoPauseThreshold = config.autoPauseThreshold,
            updatedAt = config.updatedAt.toString(),
        )
}

/**
 * RiskConfig 응답 DTO.
 */
@Schema(description = "Risk 설정 응답")
data class RiskConfigResponse(
    @Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @Schema(description = "단일 포지션 최대 금액 절대값 (원)", example = "1000000.0000")
    val maxPositionAmount: BigDecimal,
    @Schema(description = "단일 포지션 최대 비율 (0.01~100.00)", example = "10.00")
    val maxPositionPercent: BigDecimal,
    @Schema(description = "일일 손실 한도 절대값 (원) — null이면 비활성", example = "null")
    val dllAbs: BigDecimal?,
    @Schema(description = "일일 손실 한도 비율 (%) — null이면 비활성", example = "3.00")
    val dllPct: BigDecimal?,
    @Schema(description = "일일 손실 한도 활성 여부", example = "true")
    val isDailyLossLimitActive: Boolean,
    @Schema(description = "동시 보유 포지션 최대 수", example = "5")
    val maxConcurrentPositions: Int,
    @Schema(description = "분당 최대 거래 수", example = "3")
    val maxTradesPerMinute: Int,
    @Schema(description = "일별 최대 거래 수", example = "50")
    val maxTradesPerDay: Int,
    @Schema(description = "연속 BLOCKED 시 신규 BUY 차단 임계", example = "10")
    val autoPauseThreshold: Int,
    @Schema(description = "마지막 갱신 시각 (ISO-8601 UTC)", example = "2026-05-12T12:00:00Z")
    val updatedAt: String,
)

/**
 * RiskConfig 갱신 요청 바디 — null이면 기존 값 유지.
 */
@Schema(description = "Risk 설정 갱신 요청")
data class RiskConfigUpdateBody(
    @Schema(description = "단일 포지션 최대 금액 절대값 (원)", example = "2000000.00")
    val maxPositionAmount: BigDecimal? = null,
    @Schema(description = "단일 포지션 최대 비율 (0.01~100.00)", example = "15.00")
    val maxPositionPercent: BigDecimal? = null,
    @Schema(description = "일일 손실 한도 절대값 (원) — dllPct XOR", example = "null")
    val dllAbs: BigDecimal? = null,
    @Schema(description = "일일 손실 한도 비율 (%) — dllAbs XOR", example = "5.00")
    val dllPct: BigDecimal? = null,
    @Schema(description = "일일 손실 한도 활성 여부. false이면 dllAbs·dllPct를 모두 비활성화", example = "true")
    val isDailyLossLimitActive: Boolean? = null,
    @Schema(description = "동시 보유 포지션 최대 수 (1~50)", example = "7")
    val maxConcurrentPositions: Int? = null,
    @Schema(description = "분당 최대 거래 수 (1~60)", example = "5")
    val maxTradesPerMinute: Int? = null,
    @Schema(description = "일별 최대 거래 수 (1~1000)", example = "100")
    val maxTradesPerDay: Int? = null,
    @Schema(description = "연속 BLOCKED 신규 BUY 차단 임계 (1~100)", example = "15")
    val autoPauseThreshold: Int? = null,
)
