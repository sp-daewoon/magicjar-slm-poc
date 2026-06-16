package kr.spdaewoon.magicjar.domain.audit.port

import kr.spdaewoon.magicjar.domain.audit.AuditTrailEntry

/**
 * 감사 로그 영속 포트 (ADR 0015).
 */
interface AuditTrailRepository {
    fun save(entry: AuditTrailEntry): AuditTrailEntry

    fun findByEntityTypeAndId(
        entityType: String,
        entityId: String,
        limit: Int = 50,
    ): List<AuditTrailEntry>
}
