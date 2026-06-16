package kr.spdaewoon.magicjar.api.persistence.screener

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * screening_run JPA 엔티티 (ADR 0071 §3 D-2 + Flyway V28).
 *
 * 옵션 영속 (MAGICJAR_SCREENER_PERSIST=true 환경에서만 INSERT).
 * IF NOT EXISTS 강제 LOCK — V28 SQL 파일 정합.
 *
 * 헌법 §6: no-arg constructor 필요 → kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * body 가진 fun 0건 LOCK.
 */
@Entity
@Table(name = "screening_run")
class ScreeningRunJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "universe_filter_hash", length = 64, nullable = false)
    val universeFilterHash: String,
    @Column(name = "dsl_hash", length = 64, nullable = false)
    val dslHash: String,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
    @Column(name = "universe_size", nullable = false)
    var universeSize: Int = 0,
    @Column(name = "evaluated_count", nullable = false)
    var evaluatedCount: Int = 0,
    @Column(name = "matched_count", nullable = false)
    var matchedCount: Int = 0,
    @Column(name = "skipped_count", nullable = false)
    var skippedCount: Int = 0,
    /** RUNNING · COMPLETED · ERROR */
    @Column(name = "status", length = 16, nullable = false)
    var status: String = "RUNNING",
    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,
    /** API · CRON · MANUAL */
    @Column(name = "trigger_source", length = 16, nullable = false)
    val triggerSource: String = "API",
    @OneToMany(mappedBy = "screeningRun", cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    val matches: MutableList<ScreeningMatchJpaEntity> = mutableListOf(),
)
