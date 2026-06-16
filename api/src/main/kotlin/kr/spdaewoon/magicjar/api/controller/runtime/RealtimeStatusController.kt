package kr.spdaewoon.magicjar.api.controller.runtime

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.runtime.CandleFreshnessDto
import kr.spdaewoon.magicjar.api.service.runtime.ExecutionNoticeDto
import kr.spdaewoon.magicjar.api.service.runtime.RealtimeStatusService
import kr.spdaewoon.magicjar.api.service.runtime.SlotStatusDto
import kr.spdaewoon.magicjar.api.service.runtime.SubscriptionStatusDto
import kr.spdaewoon.magicjar.api.service.runtime.WsStatusDto
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 실시간 구독 상태 조회 REST 컨트롤러 (최고 관리자 결재 — 장애 처리용 단일 관측 화면).
 *
 * "구독 중인 모든 토픽·내부 상태까지 한 화면에서 보고 장애를 처리한다"는 요구를 위해, consumer가
 * Redis `admin:realtime-status`에 5초 주기로 발행한 KIS WS 세션·슬롯 회계·구독 owner·체결통보 상태를
 * read → 종목명 join + stale 판정 + ACTIVE 전략/실시간 체결 구독 종목 1m 캔들 신선도를 덧붙여 반환한다(read-only 진단).
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 *
 * // AI 호출 0 — 관측 read-only (ADR 0023 §2-4)
 */
@Tag(name = "Realtime Status", description = "관리자 실시간 구독 상태 — KIS WS·슬롯·구독 owner·체결통보·캔들 신선도 통합 관측")
@RestController
@RequestMapping("/api/admin")
class RealtimeStatusController(
    private val realtimeStatusService: RealtimeStatusService,
) {
    private val log = LoggerFactory.getLogger(RealtimeStatusController::class.java)

    @Operation(
        summary = "실시간 구독 상태 조회",
        description =
            "현재 KIS WebSocket 세션·구독 슬롯 회계·체결가(H0UNCNT0)/호가(H0UNASP0) 구독 owner·체결통보(H0STCNI0/9) " +
                "상태를 한 번에 반환합니다. consumer가 Redis `admin:realtime-status`에 5초 주기로 발행한 스냅샷을 read하며, " +
                "스냅샷이 15초 이상 미갱신이면 `stale=true`(consumer 발행 정지·장애 신호)로 표시합니다. " +
                "subscriptions의 각 종목은 instrument 테이블로 종목명을 join하고, ACTIVE 전략 종목과 " +
                "체결가(H0UNCNT0) 구독 종목은 1m 캔들 신선도(candleFreshness)를 함께 반영합니다. " +
                "본 응답은 관측 전용 — 구독·매매 로직을 변경하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "실시간 구독 상태 응답 (스냅샷 부재 시 stale=true + 빈 컬렉션)",
            content = [Content(schema = Schema(implementation = RealtimeStatusResponse::class))],
        ),
    )
    @GetMapping("/realtime-status")
    fun getRealtimeStatus(): RealtimeStatusResponse {
        val response = realtimeStatusService.getStatus()
        log.debug(
            "GET /api/admin/realtime-status — stale={} wsConnected={} slotsUsed={} subs={}",
            response.stale,
            response.ws.connected,
            response.slots.used,
            response.subscriptions.size,
        )
        return response
    }
}

/**
 * 관리자 실시간 구독 상태 응답 DTO (최고 관리자 결재 계약 LOCK — frontend 병렬 화면 의존).
 *
 * consumer Redis 스냅샷 + api 가공(종목명 join·stale 판정·1m 캔들 신선도)의 합성 결과.
 */
@Schema(description = "관리자 실시간 구독 상태 응답 (KIS WS·슬롯·구독·체결통보·캔들 신선도 통합)")
data class RealtimeStatusResponse(
    @field:Schema(description = "consumer 스냅샷 발행 시각(ISO-8601, 미발행 시 null)", nullable = true)
    val updatedAt: String?,
    @field:Schema(description = "스냅샷 stale 여부(15초 이상 미갱신=consumer 발행 정지·장애)", example = "false")
    val stale: Boolean,
    @field:Schema(description = "KIS WebSocket 세션 상태")
    val ws: WsStatusDto,
    @field:Schema(description = "WS 구독 슬롯 회계")
    val slots: SlotStatusDto,
    @field:Schema(description = "토픽별 구독 항목 (체결가·호가)")
    val subscriptions: List<SubscriptionStatusDto>,
    @field:Schema(description = "체결통보 구독 상태")
    val executionNotice: ExecutionNoticeDto,
    @field:Schema(description = "ACTIVE 전략 + 체결가(H0UNCNT0) 구독 종목 1m 캔들 신선도")
    val candleFreshness: List<CandleFreshnessDto>,
)
