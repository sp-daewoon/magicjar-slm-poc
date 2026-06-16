package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * P2 REST DTO — `GET /api/system-event` 응답 row.
 *
 * 직렬화 source: [kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity]
 * (mapper는 [kr.spdaewoon.magicjar.api.service.SystemEventQueryService] 내부 private fun)
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.1 + §13
 * plan: `_workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md` Task 2
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@Schema(description = "시스템 운영 이벤트 응답 — 알림센터·dashboard 표시 row")
data class SystemEventResponseDto(
    @field:Schema(description = "DB ID", example = "42")
    val id: Long,
    @field:Schema(
        description = "카테고리",
        example = "RECONCILE",
        allowableValues = ["RECONCILE", "WEBSOCKET", "KAFKA", "KIS_RATELIMIT", "DB", "REDIS", "OTHER"],
    )
    val category: String,
    @field:Schema(
        description = "심각도 (CRITICAL = toast 트리거)",
        example = "WARN",
        allowableValues = ["INFO", "WARN", "ERROR", "CRITICAL"],
    )
    val severity: String,
    @field:Schema(description = "발사 component", example = "OrderReconciliationWorker")
    val actor: String,
    @field:Schema(description = "이벤트 코드 (catalog)", example = "RECONCILE_FALLBACK_RESOLVED")
    val action: String,
    @field:Schema(description = "사용자 ID (null = system-wide broadcast)", example = "74683223", nullable = true)
    val userId: String?,
    @field:Schema(description = "동일 사건 묶기 UUID (WS disconnect→reconnect)", nullable = true)
    val correlationId: String?,
    @field:Schema(description = "자유 형식 JSON (재발생 사유·retry count 등)", example = "{\"orderId\":5}")
    val detailJson: String,
    @field:Schema(description = "발생 시각 (ISO-8601 UTC)", example = "2026-05-20T15:00:00Z")
    val occurredAt: Instant,
    @field:Schema(description = "사용자 확인 시각 (read 표시)", nullable = true)
    val viewedAt: Instant?,
    @field:Schema(description = "해결 처리 시각 (dismiss 표시)", nullable = true)
    val resolvedAt: Instant?,
)

/**
 * 페이지네이션 응답 — 알림센터 unread count + dashboard total/page navigation.
 */
@Schema(description = "시스템 이벤트 페이지네이션 응답")
data class SystemEventPageDto(
    @field:Schema(description = "현재 페이지 이벤트 목록 (최신순)")
    val items: List<SystemEventResponseDto>,
    @field:Schema(description = "전체 row 개수 (DB count)", example = "1234")
    val totalCount: Long,
    @field:Schema(description = "사용자 미확인 row 개수 (벨 badge)", example = "5")
    val unreadCount: Long,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
)
