package kr.spdaewoon.magicjar.domain.audit

import kotlinx.datetime.Instant

/**
 * 감사 로그 엔티티 (ADR 0015 §감사).
 * 주문 거절·KillSwitch 변경·시스템 설정 변경 등 주요 이벤트 기록.
 */
data class AuditTrailEntry(
    val id: Long? = null,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val detailJson: String?,
    val occurredAt: Instant,
)
