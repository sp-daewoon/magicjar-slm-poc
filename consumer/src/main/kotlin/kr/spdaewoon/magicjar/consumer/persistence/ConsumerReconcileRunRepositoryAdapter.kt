package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileRunRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * ReconcileRunRepository adapter in the consumer JVM.
 *
 * Without this bean BalanceReconcileService falls back to in-memory id=null runs.
 */
@Component
class ConsumerReconcileRunRepositoryAdapter(
    private val jpa: ConsumerReconcileRunJpaRepository,
) : ReconcileRunRepository {
    private val log = LoggerFactory.getLogger(ConsumerReconcileRunRepositoryAdapter::class.java)

    @Transactional
    override fun save(run: ReconcileRun): ReconcileRun {
        val saved = jpa.save(toEntity(run))
        log.debug("consumer reconcile_run save: id={} userId={} status={}", saved.id, saved.userId, saved.status)
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findById(id: Long): ReconcileRun? = jpa.findById(id).map { toDomain(it) }.orElse(null)

    @Transactional(readOnly = true)
    override fun findByUserId(
        userId: String,
        limit: Int,
        offset: Int,
    ): List<ReconcileRun> {
        val userIdLong = userId.toLongOrNull() ?: return emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        return jpa
            .findByUserIdOrderByStartedAtDesc(userIdLong, PageRequest.of(offset / safeLimit, safeLimit))
            .content
            .map { toDomain(it) }
    }

    private fun toEntity(run: ReconcileRun): ConsumerReconcileRunJpaEntity =
        ConsumerReconcileRunJpaEntity(
            id = run.id,
            userId = run.userId.toLongOrNull() ?: 0L,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            driftCount = run.driftCount,
            status = run.status.name,
            lastError = run.lastError,
            triggerSource = run.triggerSource.name,
        )

    private fun toDomain(entity: ConsumerReconcileRunJpaEntity): ReconcileRun =
        ReconcileRun(
            id = entity.id,
            userId = entity.userId.toString(),
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            driftCount = entity.driftCount,
            status = runCatching { ReconcileStatus.valueOf(entity.status) }.getOrDefault(ReconcileStatus.ERROR),
            lastError = entity.lastError,
            triggerSource =
                runCatching { ReconcileTriggerSource.valueOf(entity.triggerSource) }
                    .getOrDefault(ReconcileTriggerSource.CRON),
        )
}
