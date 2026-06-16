package kr.spdaewoon.magicjar.api.controller.stomp

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * STOMP 이벤트 backfill REST 컨트롤러 — ADR 0084 P1-2 (spec 2026-05-18 order-path-stability §2-6).
 *
 * frontend STOMP 클라이언트 disconnect 동안 누락된 이벤트를 재접속 시 backfill.
 *
 * ### 엔드포인트
 * - GET /api/stomp/events?userId={id}&since={iso8601}[&channel={ch}][&limit={n}]
 *
 * ### 정렬
 * created_at DESC — 최신 이벤트 먼저. frontend는 표시 시점에 reverse 또는 모달 큐잉으로 처리.
 *
 * ### multi-tenant 격리
 * userId 필수 — caller가 자신의 userId만 조회 가능 (인증 layer는 별도 ADR 권한 + Spring Security).
 * 본 controller는 userId path 정합만 책임 (인증 검증은 SecurityConfig 단).
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Tag(name = "STOMP Event Backfill", description = "STOMP 재접속 시 누락 이벤트 backfill (ADR 0084 P1-2)")
@RestController
@RequestMapping("/api/stomp")
class StompEventBackfillController(
    private val stompEventRepository: StompEventRepository,
) {
    private val log = LoggerFactory.getLogger(StompEventBackfillController::class.java)

    @Operation(
        summary = "STOMP 이벤트 backfill 조회",
        description =
            "사용자의 STOMP 이벤트를 since 시각(포함) 이후 created_at DESC 순으로 조회합니다. " +
                "frontend WebSocket 재접속 시 lastSeenAt 보존 후 본 API 호출 → 모달/toast 큐잉 표시. " +
                "channel 파라미터로 특정 토픽 그룹만 필터 가능 (order · signal · risk · recon 등). " +
                "ADR 0084 P1-2 정합.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "backfill 이벤트 목록 (created_at DESC, 최대 limit)",
            content = [Content(schema = Schema(implementation = StompEventBackfillResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "since 파라미터 형식 오류 (ISO-8601 필수)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/events")
    fun findSince(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(
            description = "조회 시작 시각 (ISO-8601 UTC, inclusive)",
            example = "2026-05-19T01:00:00Z",
            required = true,
        )
        @RequestParam since: String,
        @Parameter(
            description = "STOMP topic 그룹 필터 (선택 — order · signal · risk · recon 등)",
            example = "order",
        )
        @RequestParam(required = false) channel: String?,
        @Parameter(description = "최대 row 수 (1~500)", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
    ): StompEventBackfillResponse {
        val sinceInstant =
            try {
                Instant.parse(since)
            } catch (e: DateTimeParseException) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "since 파라미터 형식 오류 (ISO-8601 필수): $since",
                    e,
                )
            }
        val effectiveLimit = limit.coerceIn(1, 500)
        log.debug(
            "GET /api/stomp/events — userId={} since={} channel={} limit={}",
            userId,
            sinceInstant,
            channel,
            effectiveLimit,
        )
        val events = stompEventRepository.findSince(userId, sinceInstant, channel, effectiveLimit)
        return StompEventBackfillResponse(
            content = events.map { toDto(it) },
            count = events.size,
            since = since,
            channel = channel,
        )
    }

    @Operation(
        summary = "STOMP 미수신 이벤트 count + bounds 조회 (invalidate-query)",
        description =
            "frontend STOMP 클라이언트 onConnect 시 호출 — lastSeenAt 이후 누락 이벤트 수와 시간 범위를 반환합니다. " +
                "count > 0 이면 frontend가 /api/stomp/events 본 backfill을 추가 호출하여 페이로드를 받아오는 2단계 패턴. " +
                "1단계(count)에서 0이면 backfill 자체를 생략 — DB·네트워크 부하 절감. " +
                "ADR 0084 P1-3 정합 (frontend PR #549 hook 의존).",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "미수신 count + bounds (count=0 시 firstTs·lastTs null)",
            content = [Content(schema = Schema(implementation = UnreadStompEventCountResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "since 파라미터 형식 오류 (ISO-8601 필수)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/events/unread")
    fun countUnread(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(
            description = "조회 시작 시각 (ISO-8601 UTC, inclusive) — frontend lastSeenAt",
            example = "2026-05-19T01:00:00Z",
            required = true,
        )
        @RequestParam since: String,
        @Parameter(
            description = "STOMP topic 그룹 필터 (선택 — order · signal · risk · recon 등)",
            example = "order",
        )
        @RequestParam(required = false) channel: String?,
    ): UnreadStompEventCountResponse {
        val sinceInstant =
            try {
                Instant.parse(since)
            } catch (e: DateTimeParseException) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "since 파라미터 형식 오류 (ISO-8601 필수): $since",
                    e,
                )
            }
        log.debug(
            "GET /api/stomp/events/unread — userId={} since={} channel={}",
            userId,
            sinceInstant,
            channel,
        )
        val count = stompEventRepository.countUnreadSince(userId, sinceInstant, channel)
        val bounds =
            if (count > 0L) {
                stompEventRepository.findUnreadBoundsSince(userId, sinceInstant, channel)
            } else {
                null
            }
        return UnreadStompEventCountResponse(
            count = count,
            firstTs = bounds?.firstTs,
            lastTs = bounds?.lastTs,
            since = since,
            channel = channel,
        )
    }

    // --- private helper ---

    private fun toDto(event: StompEvent): StompEventDto =
        StompEventDto(
            id = event.id,
            userId = event.userId,
            channel = event.channel,
            eventType = event.eventType,
            payloadJson = event.payloadJson,
            createdAt = event.createdAt,
        )
}

/**
 * STOMP 이벤트 backfill 응답 DTO.
 */
@Schema(description = "STOMP 이벤트 backfill 응답")
data class StompEventBackfillResponse(
    @field:Schema(description = "이벤트 목록 (created_at DESC)")
    val content: List<StompEventDto>,
    @field:Schema(description = "반환 row 수", example = "12")
    val count: Int,
    @field:Schema(description = "조회 시작 시각 (요청 echo)", example = "2026-05-19T01:00:00Z")
    val since: String,
    @field:Schema(description = "channel 필터 (요청 echo, null 가능)", example = "order")
    val channel: String?,
)

/**
 * STOMP 미수신 이벤트 count + bounds 응답 — frontend invalidate-query 1단계 (ADR 0084 P1-3).
 *
 * count == 0 이면 firstTs·lastTs 둘 다 null — frontend는 backfill 본 API 호출을 생략합니다.
 * count > 0 이면 firstTs·lastTs 메타로 사용자에게 "N건 누락 (가장 오래된 / 가장 최근)" 안내 가능.
 */
@Schema(description = "STOMP 미수신 이벤트 count + bounds (P1-3 invalidate-query)")
data class UnreadStompEventCountResponse(
    @field:Schema(description = "since 이후 미수신 이벤트 수", example = "7")
    val count: Long,
    @field:Schema(
        description = "가장 오래된 미수신 이벤트 시각 (count=0 시 null)",
        example = "2026-05-19T01:00:01Z",
    )
    val firstTs: Instant?,
    @field:Schema(
        description = "가장 최근 미수신 이벤트 시각 (count=0 시 null)",
        example = "2026-05-19T01:23:45Z",
    )
    val lastTs: Instant?,
    @field:Schema(description = "조회 시작 시각 (요청 echo)", example = "2026-05-19T01:00:00Z")
    val since: String,
    @field:Schema(description = "channel 필터 (요청 echo, null 가능)", example = "order")
    val channel: String?,
)

/**
 * STOMP 이벤트 단건 DTO — Swagger 4단 정합.
 *
 * payloadJson은 publish 시점의 직렬화 결과를 그대로 반환합니다. frontend는 channel·eventType으로
 * 디코더 분기 후 본인 화면에서 재구성합니다.
 */
@Schema(description = "STOMP 이벤트 단건 (stomp_event row)")
data class StompEventDto(
    @field:Schema(description = "이벤트 ID (BIGSERIAL)", example = "1001")
    val id: Long?,
    @field:Schema(description = "사용자 ID", example = "12345678")
    val userId: Long,
    @field:Schema(
        description = "STOMP topic 그룹",
        example = "order",
        allowableValues = ["order", "signal", "risk", "recon", "notification"],
    )
    val channel: String,
    @field:Schema(
        description = "이벤트 세부 유형 (channel별 의미 다름)",
        example = "FILLED",
    )
    val eventType: String,
    @field:Schema(
        description = "STOMP 페이로드 JSON 직렬화 결과 — frontend channel별 디코더에서 재구성",
        example = "{\"event\":\"FILLED\",\"orderId\":1,\"code\":\"005930\"}",
    )
    val payloadJson: String,
    @field:Schema(description = "이벤트 생성 시각 (ISO-8601 UTC)", example = "2026-05-19T01:00:01Z")
    val createdAt: Instant,
)
