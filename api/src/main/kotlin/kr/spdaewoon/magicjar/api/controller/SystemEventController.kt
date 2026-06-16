package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.SystemEventQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * P2 — 시스템 운영 이벤트 REST API.
 *
 * ### 3 endpoint (spec §13)
 * - `GET    /api/system-event`              : 목록 + 필터·페이지네이션 + unread count
 * - `PATCH  /api/system-event/{id}/viewed`  : 사용자 확인 표시 (viewed_at 마킹)
 * - `PATCH  /api/system-event/{id}/resolved`: 해결 처리 표시 (resolved_at 마킹)
 *
 * frontend `useSystemEventList` (REST 30s polling fallback) + `NotificationCenter` (벨 클릭 viewed) +
 * `/system Dashboard` (운영자 dismiss resolved) 가 본 API consumer.
 *
 * STOMP push (P2 Task 1 [kr.spdaewoon.magicjar.api.messaging.SystemEventStompNotifier]) 가 1차 path,
 * 본 REST 는 새로고침 / 페이지네이션 / fallback polling 보조.
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6 + §13
 * plan: `_workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md` Task 2
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@RestController
@RequestMapping("/api/system-event")
@Tag(
    name = "SystemEvent",
    description = "시스템 운영 이벤트 (Reconcile·WebSocket·Kafka·KIS·DB·Redis fail catalog)",
)
class SystemEventController(
    private val service: SystemEventQueryService,
) {
    @Operation(
        summary = "시스템 이벤트 목록 조회",
        description = "필터(사용자·미확인) + 페이지네이션 + unread count. STOMP push 의 fallback / 새로고침 path.",
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID (null = 전체 system-wide 포함)", example = "74683223")
        @RequestParam(required = false) userId: String?,
        @Parameter(description = "미확인 (viewed_at IS NULL) 만 — userId 있을 때만 적용", example = "false")
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @Parameter(description = "페이지 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기 (default 20, max 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): SystemEventPageDto = service.findList(userId, unreadOnly, page, size.coerceIn(1, 100))

    @Operation(
        summary = "사용자 확인 표시",
        description = "viewed_at 컬럼에 현재 시각 마킹 — 벨 badge unread count 감소 trigger.",
    )
    @PatchMapping("/{id}/viewed")
    fun markViewed(
        @Parameter(description = "system_event row ID", example = "42")
        @PathVariable id: Long,
    ): ResponseEntity<SystemEventResponseDto> {
        val response = service.markViewed(id)
        return if (response != null) ResponseEntity.ok(response) else ResponseEntity.notFound().build()
    }

    @Operation(
        summary = "해결 처리 표시",
        description = "resolved_at 컬럼에 현재 시각 마킹 — dashboard dismiss / 운영자 acknowledged 표시.",
    )
    @PatchMapping("/{id}/resolved")
    fun markResolved(
        @Parameter(description = "system_event row ID", example = "42")
        @PathVariable id: Long,
    ): ResponseEntity<SystemEventResponseDto> {
        val response = service.markResolved(id)
        return if (response != null) ResponseEntity.ok(response) else ResponseEntity.notFound().build()
    }
}
