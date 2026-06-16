package kr.spdaewoon.magicjar.api.controller.order

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.service.order.OrderDetail
import kr.spdaewoon.magicjar.api.service.order.OrderQueryService
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 주문 조회 REST 컨트롤러 (ADR 0068 §15-3 GET 2 endpoint + Swagger 4단 한국어).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Tag(name = "주문 (T-O)", description = "자동매매 주문 의도·체결·취소 조회 API (ADR 0068 §15-3)")
@RestController
@RequestMapping("/api/orders")
class OrderQueryController(
    private val orderQueryService: OrderQueryService,
    private val instrumentJpaRepository: InstrumentJpaRepository,
) {
    private val log = LoggerFactory.getLogger(OrderQueryController::class.java)

    // ─── GET /api/orders ─────────────────────────────────────────────────────

    @Operation(
        summary = "주문 목록 조회",
        description =
            "사용자별 주문 의도 시계열. status·date range 필터 + 페이징. " +
                "userId 격리 — 다른 사용자 row 반환 안 함 (ADR 0010 multi-tenant).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 목록 조회 성공"),
        ApiResponse(responseCode = "400", description = "파라미터 형식 오류"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "주문 상태 필터 (복수 가능)", example = "REQUESTED,ACK")
        @RequestParam(required = false) status: List<OrderStatus>?,
        @Parameter(description = "조회 시작 시점 (ISO-8601 UTC)", example = "2026-05-13T00:00:00Z")
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "조회 종료 시점 (ISO-8601 UTC)", example = "2026-05-13T23:59:59Z")
        @RequestParam(required = false) to: Instant?,
        @Parameter(description = "주문 출처 필터", example = "MANUAL")
        @RequestParam(required = false) source: OrderSource?,
        @Parameter(description = "매수/매도 필터", example = "BUY")
        @RequestParam(required = false) side: OrderSide?,
        @Parameter(description = "종목코드 검색", example = "005930")
        @RequestParam(required = false) code: String?,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): OrderListPageResponse {
        log.debug(
            "주문 목록 조회: userId={} status={} from={} to={} source={} side={} code={} page={} size={}",
            userId,
            status,
            from,
            to,
            source,
            side,
            code,
            page,
            size,
        )
        val result = orderQueryService.listByUser(userId, from, to, status, source, side, code, page, size)
        // Watchlist 패턴 정합 — instrument 테이블 batch lookup으로 종목명 채움 (N+1 회피)
        val nameByCode = resolveNamesByCodes(result.content.map { it.code }.toSet())
        return OrderListPageResponse(
            content = result.content.map { toListItem(it, nameByCode[it.code]) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast,
        )
    }

    // ─── GET /api/orders/{id} ─────────────────────────────────────────────────

    @Operation(
        summary = "주문 단건 조회",
        description =
            "주문 ID로 단건 조회합니다. request + 체결 목록 + 감사 시계열 집합. " +
                "userId 불일치 시 404 반환 (ADR 0010 격리).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 단건 조회 성공"),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 소유자 불일치"),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "주문 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "소유자 사용자 ID (격리 검증용)", example = "1", required = true)
        @RequestParam userId: String,
    ): OrderDetailResponse {
        val detail =
            orderQueryService.findDetail(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND: $id")
        return toDetailResponse(detail)
    }

    // ─── private helper ───────────────────────────────────────────────────────

    /**
     * Watchlist 패턴 정합 (WatchlistService.list) — instrument 테이블 batch lookup.
     *  - findAllById(codes) IN 절 단일 쿼리 → N+1 회피
     *  - stub row(name=code, market='UNKNOWN')는 사용자에게 종목명으로 부적합 → null fallback
     *  - 미동기화 종목(존재 X)도 null → frontend가 code만 표시 (graceful)
     */
    private fun resolveNamesByCodes(codes: Set<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        return instrumentJpaRepository
            .findAllById(codes)
            .asSequence()
            .filter { it.market != "UNKNOWN" }
            .filter { it.name.isNotBlank() && it.name != it.code }
            .associate { it.code to it.name }
    }

    private fun toListItem(
        req: OrderRequest,
        name: String?,
    ): OrderListItemDto =
        OrderListItemDto(
            id = req.id ?: 0L,
            userId = req.userId,
            code = req.code,
            name = name,
            side = req.side.name,
            qty = req.quantity,
            priceType = req.priceType.name,
            limitPrice = req.limitPrice?.toString(),
            status = req.status.name,
            mode = req.mode.name,
            kisOrderNo = req.kisOrderNo,
            rejectReason = req.rejectReason,
            requestedAt = req.requestedAt.toString(),
            completedAt = req.completedAt?.toString(),
            source = req.source.name,
            // ADR 0115 §2-P2-⑥ — 발사 출처 키 사슬 노출 (사인→주문→체결→보유 추적성, 불변식 4)
            orderTrigger = req.orderTrigger?.name,
            triggerRefId = req.triggerRefId,
        )

    private fun toDetailResponse(detail: OrderDetail): OrderDetailResponse {
        // 단건 — 1회 lookup만 (batch 불필요)
        val name = resolveNamesByCodes(setOf(detail.request.code))[detail.request.code]
        return OrderDetailResponse(
            request = toListItem(detail.request, name),
            executions = detail.executions.map { toExecutionDto(it) },
            auditEvents = detail.auditEvents.map { toAuditDto(it) },
        )
    }

    private fun toExecutionDto(exec: OrderExecution): OrderExecutionDto =
        OrderExecutionDto(
            id = exec.id ?: 0L,
            orderId = exec.orderId,
            executedQty = exec.executedQuantity,
            executedPrice = exec.executedPrice.toString(),
            executedAt = exec.executedAt.toString(),
            fee = exec.fee?.toString() ?: "0",
            tax = exec.tax?.toString() ?: "0",
        )

    private fun toAuditDto(audit: OrderAuditEvent): OrderAuditEventDto =
        OrderAuditEventDto(
            id = audit.id ?: 0L,
            orderId = audit.orderId,
            eventType = audit.eventType.name,
            payloadJson = audit.payloadJson,
            ts = audit.createdAt.toString(),
        )
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "주문 목록 아이템")
data class OrderListItemDto(
    @field:Schema(description = "주문 ID", example = "1")
    val id: Long,
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드", example = "005930")
    val code: String,
    @field:Schema(
        description = "종목명 (instrument 테이블 join · null=미동기화/stub)",
        example = "삼성전자",
        nullable = true,
    )
    val name: String?,
    @field:Schema(description = "매수/매도", example = "BUY", allowableValues = ["BUY", "SELL"])
    val side: String,
    @field:Schema(description = "주문 수량", example = "10")
    val qty: Int,
    @field:Schema(description = "가격 유형", example = "MARKET", allowableValues = ["MARKET", "LIMIT"])
    val priceType: String,
    @field:Schema(description = "지정가 (LIMIT 시만)", example = "73000")
    val limitPrice: String?,
    @field:Schema(description = "주문 상태", example = "FILLED")
    val status: String,
    @field:Schema(description = "거래 모드", example = "MOCK", allowableValues = ["MOCK", "REAL"])
    val mode: String,
    @field:Schema(description = "KIS 주문번호", example = "0000123456")
    val kisOrderNo: String?,
    @field:Schema(description = "거절 사유 (REJECTED 상태 시)", example = "REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND")
    val rejectReason: String?,
    @field:Schema(description = "주문 접수 시각 (ISO-8601 UTC)", example = "2026-05-13T09:00:00Z")
    val requestedAt: String,
    @field:Schema(description = "주문 종료 시각 (ISO-8601 UTC)", example = "2026-05-13T09:00:01Z")
    val completedAt: String?,
    @field:Schema(description = "주문 출처", example = "MANUAL", allowableValues = ["AUTO", "MANUAL", "EXTERNAL"])
    val source: String,
    @field:Schema(
        description =
            "발사 출처 (ADR 0115 키 사슬) — STRATEGY_SIGNAL=시그널 / AUTO_EXIT=자동익절 / STOP_LOSS=손절 / " +
                "VI_AUTO_EXIT=VI자동청산 / PARTIAL_TAKE_PROFIT=분할익절 / MANUAL=수동 / EXTERNAL=외부. null=V80 이전 legacy 미분류",
        example = "STRATEGY_SIGNAL",
        nullable = true,
        allowableValues = ["STRATEGY_SIGNAL", "AUTO_EXIT", "STOP_LOSS", "VI_AUTO_EXIT", "PARTIAL_TAKE_PROFIT", "MANUAL", "EXTERNAL"],
    )
    val orderTrigger: String?,
    @field:Schema(
        description = "유발 근거 실키 (signal id / strategy id 등 — 사인·전략 역추적). null=근거 키 없음",
        example = "42",
        nullable = true,
    )
    val triggerRefId: Long?,
)

@Schema(description = "체결 단건 DTO")
data class OrderExecutionDto(
    @field:Schema(description = "체결 ID", example = "1")
    val id: Long,
    @field:Schema(description = "주문 ID (FK)", example = "1")
    val orderId: Long,
    @field:Schema(description = "체결 수량", example = "10")
    val executedQty: Int,
    @field:Schema(description = "체결 단가", example = "73000")
    val executedPrice: String,
    @field:Schema(description = "체결 시각 (ISO-8601 UTC)", example = "2026-05-13T09:00:01Z")
    val executedAt: String,
    @field:Schema(description = "수수료", example = "150")
    val fee: String,
    @field:Schema(description = "세금", example = "220")
    val tax: String,
)

@Schema(description = "감사 이벤트 단건 DTO")
data class OrderAuditEventDto(
    @field:Schema(description = "감사 이벤트 ID", example = "1")
    val id: Long,
    @field:Schema(description = "주문 ID (IDEMPOTENCY_SKIPPED 시 null)", example = "1")
    val orderId: Long?,
    @field:Schema(description = "이벤트 유형", example = "KIS_ACK")
    val eventType: String,
    @field:Schema(description = "이벤트 페이로드 JSON", example = "{}")
    val payloadJson: String,
    @field:Schema(description = "이벤트 발생 시각 (ISO-8601 UTC)", example = "2026-05-13T09:00:01Z")
    val ts: String,
)

@Schema(description = "주문 단건 상세 응답 (request + executions + audit)")
data class OrderDetailResponse(
    @field:Schema(description = "주문 의도")
    val request: OrderListItemDto,
    @field:Schema(description = "체결 목록 (분할 체결 1:N)")
    val executions: List<OrderExecutionDto>,
    @field:Schema(description = "감사 이벤트 시계열")
    val auditEvents: List<OrderAuditEventDto>,
)

@Schema(description = "주문 목록 페이징 응답")
data class OrderListPageResponse(
    @field:Schema(description = "주문 목록")
    val content: List<OrderListItemDto>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @field:Schema(description = "총 주문 수", example = "100")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "5")
    val totalPages: Int,
    @field:Schema(description = "마지막 페이지 여부", example = "false")
    val last: Boolean,
)
