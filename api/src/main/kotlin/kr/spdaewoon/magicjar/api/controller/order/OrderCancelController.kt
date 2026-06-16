package kr.spdaewoon.magicjar.api.controller.order

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.order.OrderCancelService
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주문 취소 REST 컨트롤러 (ADR 0068 §15-3 POST cancel — 의제 E-1 + Swagger 4단 한국어).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Tag(name = "주문 (T-O)", description = "자동매매 주문 의도·체결·취소 조회 API (ADR 0068 §15-3)")
@RestController
@RequestMapping("/api/orders")
class OrderCancelController(
    private val orderCancelService: OrderCancelService,
) {
    private val log = LoggerFactory.getLogger(OrderCancelController::class.java)

    // ─── POST /api/orders/{id}/cancel ─────────────────────────────────────────

    @Operation(
        summary = "주문 수동 취소",
        description =
            "사용자가 주문을 수동으로 취소합니다. " +
                "취소 가능 상태: REQUESTED·ACK·PARTIALLY_FILLED. " +
                "취소 불가(409): FILLED·CANCELLED·REJECTED·EXPIRED. " +
                "성공 시 KIS order-rvsecncl ACK 이후 status=CANCELLED로 전이합니다. " +
                "KIS 취소 실패 시 DB 상태를 CANCELLED로 바꾸지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 취소 성공"),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 소유자 불일치"),
        ApiResponse(
            responseCode = "409",
            description = "취소 불가 상태 (FILLED·CANCELLED·REJECTED·EXPIRED)",
            content = [Content(schema = Schema(implementation = Map::class))],
        ),
    )
    @PostMapping("/{id}/cancel")
    fun cancel(
        @Parameter(description = "주문 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "소유자 사용자 ID (격리 검증용)", example = "1", required = true)
        @RequestParam userId: String,
        @RequestBody(required = false) body: CancelOrderRequestDto?,
    ): CancelOrderResponseDto {
        log.info("주문 취소 요청: orderId={} userId={} reason={}", id, userId, body?.reason)
        val updated = orderCancelService.cancel(id, userId, body?.reason)
        return toCancelResponse(updated)
    }

    // ─── private helper ───────────────────────────────────────────────────────

    private fun toCancelResponse(req: OrderRequest): CancelOrderResponseDto =
        CancelOrderResponseDto(
            id = req.id ?: 0L,
            status = req.status.name,
            completedAt = req.completedAt?.toString(),
        )
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "주문 취소 요청 DTO")
data class CancelOrderRequestDto(
    @field:Schema(description = "취소 사유 (선택)", example = "전략 조건 변경으로 인한 수동 취소")
    val reason: String? = null,
)

@Schema(description = "주문 취소 응답 DTO")
data class CancelOrderResponseDto(
    @field:Schema(description = "주문 ID", example = "1")
    val id: Long,
    @field:Schema(description = "변경된 주문 상태", example = "CANCELLED")
    val status: String,
    @field:Schema(description = "종료 시각 (ISO-8601 UTC)", example = "2026-05-13T09:01:00Z")
    val completedAt: String?,
)
