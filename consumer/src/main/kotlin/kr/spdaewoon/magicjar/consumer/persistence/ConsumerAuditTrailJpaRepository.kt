package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * audit_trail Spring Data JPA Repository (consumer 모듈).
 *
 * ★ 헌법 §6 (Repository default fun 함정 · ADR 0051) 정합: interface body 가진 fun 0건.
 *   파생/native @Query 만 사용 (consumer 사본 Repository 동일 함정 회피).
 */
interface ConsumerAuditTrailJpaRepository : JpaRepository<ConsumerAuditTrailJpaEntity, Long> {
    /**
     * 특정 entity_type·entity_id·action 의 최신 occurred_at (없으면 null).
     *
     * R-BF no-advance cooldown 판정용 — entity_id=code, action=NO_ADVANCE 의 직전 마킹 시각 조회.
     * candle-row 기반 progress 와 분리된 별도 progress 테이블 0(Flyway 0) — audit append-only 재사용.
     */
    @Query(
        "SELECT MAX(a.occurredAt) FROM ConsumerAuditTrailJpaEntity a " +
            "WHERE a.entityType = :entityType AND a.entityId = :entityId AND a.action = :action",
    )
    fun findLatestOccurredAt(
        @Param("entityType") entityType: String,
        @Param("entityId") entityId: String,
        @Param("action") action: String,
    ): Instant?
}
