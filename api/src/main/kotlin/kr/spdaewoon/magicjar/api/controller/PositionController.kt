package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.risk.PositionQueryService
import kr.spdaewoon.magicjar.domain.position.Position
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * Position REST 컨트롤러 — T-RISK Phase 2 (ADR 0067 §5-4 G-1 + ADR 0042 정합).
 *
 * ### 엔드포인트
 * - GET /api/positions             현재 보유 포지션 목록 (qty > 0, source 필터 선택)
 * - GET /api/positions/{id}        단건 조회
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 사용자 메모리 feedback_no_mock_data 정합 — mock 합성 0건, 실제 DB 데이터만.
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Tag(name = "Position", description = "보유 포지션 조회 — T-RISK Phase 2 (ADR 0067 §5-4 G-1 + ADR 0042 정합)")
@RestController
@RequestMapping("/api/positions")
class PositionController(
    private val positionQueryService: PositionQueryService,
) {
    private val log = LoggerFactory.getLogger(PositionController::class.java)

    // ─── GET /api/positions ───────────────────────────────────────────────────

    @Operation(
        summary = "현재 보유 포지션 목록 조회",
        description =
            "사용자의 현재 보유 포지션(qty > 0)을 조회합니다. " +
                "source 파라미터로 MOCK·REAL 필터 가능 (없으면 전체). " +
                "0건일 경우 빈 배열 반환 (mock 데이터 0건). " +
                "ADR 0067 §5-4 G-1 + ADR 0042 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "포지션 목록 조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "source 파라미터 오류 (MOCK·REAL만 허용)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(
            description = "거래 모드 필터 (MOCK·REAL) — 없으면 전체 조회",
            example = "MOCK",
        )
        @RequestParam(required = false) source: String?,
    ): List<PositionResponse> {
        if (source != null && source !in listOf("MOCK", "REAL")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "source는 MOCK 또는 REAL이어야 합니다")
        }
        log.debug("포지션 목록 조회: userId={} source={}", userId, source)
        val positions = positionQueryService.listCurrentByUser(userId, source)
        return positions.map { toResponse(it) }
    }

    // ─── GET /api/positions/{id} ──────────────────────────────────────────────

    @Operation(
        summary = "포지션 단건 조회",
        description = "특정 포지션을 조회합니다. ADR 0067 §5-4 G-1 + ADR 0042 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "포지션 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "포지션 없음 또는 접근 권한 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "포지션 ID", example = "42", required = true)
        @PathVariable id: Long,
    ): PositionResponse {
        log.debug("포지션 단건 조회: userId={} id={}", userId, id)
        val position = positionQueryService.get(userId, id)
        return toResponse(position)
    }

    // --- private helper ---

    private fun toResponse(position: Position): PositionResponse =
        PositionResponse(
            id = position.id ?: 0L,
            userId = position.userId,
            code = position.code,
            quantity = position.quantity,
            avgPrice = position.avgPrice,
            isClosed = position.isClosed,
            updatedAt = position.updatedAt.toString(),
        )
}

/**
 * Position 응답 DTO.
 */
@Schema(description = "포지션 응답")
data class PositionResponse(
    @Schema(description = "포지션 ID", example = "42")
    val id: Long,
    @Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val code: String,
    @Schema(description = "보유 수량 (0=청산)", example = "10")
    val quantity: Int,
    @Schema(description = "평균 매수 단가", example = "70000.0000")
    val avgPrice: BigDecimal,
    @Schema(description = "포지션 청산 여부 (qty=0)", example = "false")
    val isClosed: Boolean,
    @Schema(description = "마지막 갱신 시각 (ISO-8601 UTC)", example = "2026-05-12T09:30:00Z")
    val updatedAt: String,
)
