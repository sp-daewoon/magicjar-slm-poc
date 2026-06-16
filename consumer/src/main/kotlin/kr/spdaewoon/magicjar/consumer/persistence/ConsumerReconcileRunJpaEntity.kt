package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * reconcile_run JPA entity copy for the consumer JVM.
 *
 * api 모듈의 Flyway가 만든 동일 테이블을 consumer에서도 기록하기 위한 모듈 경계용 사본.
 */
@Entity
@Table(name = "reconcile_run")
class ConsumerReconcileRunJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "finished_at")
    val finishedAt: Instant? = null,
    @Column(name = "drift_count", nullable = false)
    val driftCount: Int = 0,
    @Column(name = "status", nullable = false, length = 16)
    val status: String,
    @Column(name = "last_error", columnDefinition = "TEXT")
    val lastError: String? = null,
    @Column(name = "trigger_source", nullable = false, length = 16)
    val triggerSource: String,
) {
    protected constructor() : this(
        userId = 0L,
        startedAt = Instant.EPOCH,
        status = "RUNNING",
        triggerSource = "CRON",
    )
}
