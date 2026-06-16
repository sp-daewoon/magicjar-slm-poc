package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.signal.EnrichedSignal
import kr.spdaewoon.magicjar.api.service.signal.SignalQueryService
import kr.spdaewoon.magicjar.api.service.signal.SignalTrace
import kr.spdaewoon.magicjar.api.service.signal.SignalTraceService
import kr.spdaewoon.magicjar.api.service.signal.TracedOrder
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * 시그널 REST 컨트롤러 — T-SIG Phase 2 (ADR 0066 §5-3 + §7-2 단계 5).
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Tag(name = "Signal", description = "시그널 시계열 조회 — T-SIG Phase 2 (ADR 0066 §5-3)")
@RestController
@RequestMapping("/api/signals")
class SignalController(
    private val signalQueryService: SignalQueryService,
    private val signalTraceService: SignalTraceService,
) {
    private val log = LoggerFactory.getLogger(SignalController::class.java)

    // ─── GET /api/signals ─────────────────────────────────────────────────────

    @Operation(
        summary = "시그널 시계열 조회 (페이징)",
        description =
            "사용자의 시그널 목록을 시계열로 조회합니다 (ts DESC). " +
                "from·to는 ISO-8601 UTC 형식 (예: `2026-05-12T00:00:00Z`). " +
                "ADR 0066 §5-3 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시그널 목록 조회 성공"),
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
        @Parameter(description = "종목 코드 부분 검색 (선택)", example = "005930")
        @RequestParam(required = false) code: String?,
        @Parameter(description = "평가 인터벌 필터 (선택)", example = "1m")
        @RequestParam(required = false) interval: String?,
        @Parameter(
            description = "매매 방향 필터 (선택)",
            example = "BUY",
            schema = Schema(allowableValues = ["BUY", "SELL", "HOLD"]),
        )
        @RequestParam(required = false) side: String?,
        @Parameter(description = "발행 전략 ID 필터 (선택)", example = "8")
        @RequestParam(required = false) strategyId: Long?,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "50")
        @RequestParam(defaultValue = "50") size: Int,
    ): SignalPageResponse {
        if (from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 이전이어야 합니다")
        }
        log.debug(
            "시그널 목록 조회: userId={} from={} to={} code={} interval={} side={} strategyId={} page={} size={}",
            userId,
            from,
            to,
            code,
            interval,
            side,
            strategyId,
            page,
            size,
        )
        val result = signalQueryService.list(userId, from, to, code, interval, side, strategyId, page, size)
        return SignalPageResponse(
            content = result.content.map { toResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast,
        )
    }

    // ─── GET /api/signals/{id} ────────────────────────────────────────────────

    @Operation(
        summary = "시그널 단건 조회",
        description = "시그널 ID로 단건 조회합니다. userId 불일치 시 404 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시그널 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "시그널 없음 또는 소유자 불일치",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "시그널 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "소유자 사용자 ID (검증용)", example = "1", required = true)
        @RequestParam userId: String,
    ): SignalResponse {
        val signal = signalQueryService.get(userId, id)
        return toResponse(signal)
    }

    // ─── GET /api/signals/{id}/trace ──────────────────────────────────────────

    @Operation(
        summary = "시그널 키 사슬 추적 (드릴다운)",
        description =
            "ADR 0115 §2-B 불변식 #4 (화면 추적성) — 최고 관리자 직접 요구. " +
                "1 사인이 **몇 주 주문**을 냈고, **몇 주가 그 전략으로 사지고 팔아졌는지**를 키로 끊김 없이 추적합니다. " +
                "사인(시각·rule·strength) → 연결 주문 N건(수량·상태·발사 출처) → 체결 타임라인" +
                "(부분체결 각각 수량·가격·시각) → 현재 그 전략 보유 수량 + **정합 판정**" +
                "(전략 귀속 보유 합 == KIS 권위 position이면 정합, 다르면 보정 대기). " +
                "read-only — 자본 write 0. 데이터 없으면 빈 배열 (mock 합성 0건). " +
                "userId 불일치 시 404 반환 (ADR 0010 격리).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시그널 추적 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "시그널 없음 또는 소유자 불일치",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/trace")
    fun trace(
        @Parameter(description = "시그널 ID", example = "709", required = true)
        @PathVariable id: Long,
        @Parameter(description = "소유자 사용자 ID (격리 검증용)", example = "1", required = true)
        @RequestParam userId: String,
    ): SignalTraceResponse {
        log.debug("시그널 추적 조회: userId={} signalId={}", userId, id)
        return toTraceResponse(signalTraceService.trace(userId, id))
    }

    // ─── private helper ───────────────────────────────────────────────────────

    private fun toTraceResponse(trace: SignalTrace): SignalTraceResponse {
        val orderedQty = trace.orders.sumOf { it.order.quantity }
        val filledQty =
            trace.orders.sumOf { traced -> traced.executions.sumOf { it.executedQuantity } }
        // 정합 판정 — 전략 귀속 보유 == KIS 권위(user+code 합산)면 RECONCILED, 다르면 PENDING_CORRECTION.
        val reconciled = trace.strategyHeldQty == trace.authorityHeldQty
        val sp = trace.strategyPosition
        return SignalTraceResponse(
            signal = toResponse(trace.enriched),
            orders = trace.orders.map { toTracedOrderDto(it) },
            summary =
                SignalTraceSummaryDto(
                    orderCount = trace.orders.size,
                    orderedQty = orderedQty,
                    filledQty = filledQty,
                    strategyHeldQty = trace.strategyHeldQty,
                    authorityHeldQty = trace.authorityHeldQty,
                    reconciled = reconciled,
                    strategyAvgPrice = sp?.avgPrice?.toString(),
                    strategyFilledLots = sp?.filledLots,
                    strategyRealizedPnl = sp?.realizedPnl?.toString(),
                    strategyPositionUpdatedAt = sp?.updatedAt?.toString(),
                ),
        )
    }

    private fun toTracedOrderDto(traced: TracedOrder): TracedOrderDto {
        val req = traced.order
        return TracedOrderDto(
            id = req.id ?: 0L,
            code = req.code,
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
            orderTrigger = req.orderTrigger?.name,
            triggerRefId = req.triggerRefId,
            filledQty = traced.executions.sumOf { it.executedQuantity },
            executions =
                traced.executions.map { exec ->
                    TracedExecutionDto(
                        id = exec.id ?: 0L,
                        orderId = exec.orderId,
                        executedQty = exec.executedQuantity,
                        executedPrice = exec.executedPrice.toString(),
                        executedAt = exec.executedAt.toString(),
                        fee = exec.fee?.toString() ?: "0",
                        tax = exec.tax?.toString() ?: "0",
                    )
                },
        )
    }

    private fun toResponse(enriched: EnrichedSignal): SignalResponse {
        val signal = enriched.signal
        return SignalResponse(
            id = signal.id ?: 0L,
            strategyId = signal.strategyId,
            strategyName = enriched.strategyName,
            strategyNameKo = enriched.strategyNameKo,
            buyCondition = enriched.buyCondition,
            sellCondition = enriched.sellCondition,
            userId = signal.userId,
            code = signal.code,
            codeName = enriched.codeName,
            interval = signal.interval.code,
            ts = signal.ts.toString(),
            side = signal.side.name,
            strength = signal.strength,
            reason = signal.reason,
            priceAtSignal = signal.priceAtSignal,
            contextJson = signal.contextJson,
            createdAt = signal.createdAt?.toString(),
        )
    }
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "시그널 단건 응답")
data class SignalResponse(
    @field:Schema(description = "시그널 ID", example = "1")
    val id: Long,
    @field:Schema(description = "시그널 발행 전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "발행 전략명", example = "ma20_test")
    val strategyName: String?,
    @field:Schema(description = "발행 전략명(한글)", example = "이평선 돌파")
    val strategyNameKo: String?,
    @field:Schema(description = "발행 전략 매수 조건 DSL", example = "ma(5) crosses_above ma(20)")
    val buyCondition: String?,
    @field:Schema(description = "발행 전략 매도 조건 DSL", example = "close < ma(10)")
    val sellCondition: String?,
    @field:Schema(description = "전략 소유자 userId", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val code: String,
    @field:Schema(description = "종목명 (instrument 테이블 join · 미동기화/stub 시 null)", example = "삼성전자")
    val codeName: String?,
    @field:Schema(description = "평가 인터벌 code", example = "5m")
    val interval: String,
    @field:Schema(description = "boundary closing ts (ISO-8601 UTC)", example = "2026-05-12T00:05:00Z")
    val ts: String,
    @field:Schema(
        description = "시그널 방향",
        example = "BUY",
        allowableValues = ["BUY", "SELL", "HOLD"],
    )
    val side: String,
    @field:Schema(description = "룰 매칭 강도 0~100", example = "80")
    val strength: Int,
    @field:Schema(description = "룰 매칭 사유", example = "MA(5) > MA(20) at 09:05")
    val reason: String,
    @field:Schema(description = "평가 시점 종가", example = "73400.0000")
    val priceAtSignal: BigDecimal,
    @field:Schema(description = "평가 context JSON (indicator values)", example = "null")
    val contextJson: String?,
    @field:Schema(description = "시그널 생성 시각 (ISO-8601 UTC)", example = "2026-05-12T00:05:01Z")
    val createdAt: String?,
)

@Schema(description = "시그널 페이징 응답")
data class SignalPageResponse(
    @field:Schema(description = "시그널 목록")
    val content: List<SignalResponse>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "50")
    val size: Int,
    @field:Schema(description = "총 시그널 수", example = "100")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "2")
    val totalPages: Int,
    @field:Schema(description = "마지막 페이지 여부", example = "true")
    val last: Boolean,
)

// ─── 키 사슬 추적 DTO (ADR 0115 §2-B 불변식 #4) ────────────────────────────────

@Schema(description = "체결 단건 (추적용 — 부분체결 타임라인)")
data class TracedExecutionDto(
    @field:Schema(description = "체결 ID", example = "1")
    val id: Long,
    @field:Schema(description = "주문 ID (FK)", example = "223")
    val orderId: Long,
    @field:Schema(description = "체결 수량", example = "3")
    val executedQty: Int,
    @field:Schema(description = "체결 단가", example = "73000")
    val executedPrice: String,
    @field:Schema(description = "체결 시각 (ISO-8601 UTC)", example = "2026-06-10T05:29:14Z")
    val executedAt: String,
    @field:Schema(description = "수수료", example = "150")
    val fee: String,
    @field:Schema(description = "세금", example = "220")
    val tax: String,
)

@Schema(description = "사인이 발사한 주문 + 체결 타임라인 (1 주문 : N 부분체결)")
data class TracedOrderDto(
    @field:Schema(description = "주문 ID", example = "223")
    val id: Long,
    @field:Schema(description = "종목 코드", example = "005930")
    val code: String,
    @field:Schema(description = "매수/매도", example = "BUY", allowableValues = ["BUY", "SELL"])
    val side: String,
    @field:Schema(description = "주문 수량", example = "15")
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
    @field:Schema(description = "거절 사유 (REJECTED 시)", example = "INSUFFICIENT_BALANCE")
    val rejectReason: String?,
    @field:Schema(description = "주문 접수 시각 (ISO-8601 UTC)", example = "2026-06-10T05:29:00Z")
    val requestedAt: String,
    @field:Schema(description = "주문 종료 시각 (ISO-8601 UTC)", example = "2026-06-10T05:29:14Z")
    val completedAt: String?,
    @field:Schema(description = "주문 출처", example = "AUTO", allowableValues = ["AUTO", "MANUAL", "EXTERNAL"])
    val source: String,
    @field:Schema(
        description = "발사 출처 (ADR 0115 키 사슬). null=legacy 미분류",
        example = "STRATEGY_SIGNAL",
        nullable = true,
        allowableValues = ["STRATEGY_SIGNAL", "AUTO_EXIT", "STOP_LOSS", "VI_AUTO_EXIT", "PARTIAL_TAKE_PROFIT", "MANUAL", "EXTERNAL"],
    )
    val orderTrigger: String?,
    @field:Schema(description = "유발 근거 실키 (이 사인 id)", example = "709", nullable = true)
    val triggerRefId: Long?,
    @field:Schema(description = "이 주문의 체결 합계 수량 (부분체결 sum)", example = "11")
    val filledQty: Int,
    @field:Schema(description = "체결 타임라인 (부분체결 N건, 시각 ASC)")
    val executions: List<TracedExecutionDto>,
)

@Schema(description = "사인 추적 요약 + 정합 판정")
data class SignalTraceSummaryDto(
    @field:Schema(description = "이 사인이 낸 주문 건수", example = "1")
    val orderCount: Int,
    @field:Schema(description = "주문한 총 수량 (Σ order.qty)", example = "15")
    val orderedQty: Int,
    @field:Schema(description = "실제 체결된 총 수량 (Σ execution.qty)", example = "11")
    val filledQty: Int,
    @field:Schema(description = "현재 그 전략이 보유한 수량 (strategy_position)", example = "11")
    val strategyHeldQty: Int,
    @field:Schema(description = "KIS 권위 보유 수량 (position user+code 합산)", example = "16")
    val authorityHeldQty: Int,
    @field:Schema(
        description =
            "정합 여부 — 전략 귀속 보유(strategyHeldQty) == KIS 권위(authorityHeldQty)면 true(정합), " +
                "다르면 false(보정 대기 — ADR 0115 §C2 미귀속 또는 다전략 혼재)",
        example = "false",
    )
    val reconciled: Boolean,
    @field:Schema(description = "전략 보유 평단 (없으면 null)", example = "73250.0000", nullable = true)
    val strategyAvgPrice: String?,
    @field:Schema(description = "분할매수 진행 회차 (없으면 null)", example = "2", nullable = true)
    val strategyFilledLots: Int?,
    @field:Schema(description = "전략 실현손익 (없으면 null)", example = "0.0000", nullable = true)
    val strategyRealizedPnl: String?,
    @field:Schema(description = "전략 포지션 갱신 시각 (없으면 null)", example = "2026-06-10T05:29:15Z", nullable = true)
    val strategyPositionUpdatedAt: String?,
)

@Schema(
    description =
        "시그널 키 사슬 추적 응답 (ADR 0115 §2-B 불변식 #4) — 사인→주문→체결→보유+정합 끊김 없는 join",
)
data class SignalTraceResponse(
    @field:Schema(description = "사인 (전략명·조건 enrich)")
    val signal: SignalResponse,
    @field:Schema(description = "이 사인이 발사한 주문 + 각 주문 체결 타임라인")
    val orders: List<TracedOrderDto>,
    @field:Schema(description = "추적 요약 + 정합 판정")
    val summary: SignalTraceSummaryDto,
)
