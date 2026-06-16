package kr.spdaewoon.magicjar.api.persistence.reconcile

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileRunRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * domain [ReconcileRunRepository] port JPA 구현 어댑터 — Flyway V25 정합 (ADR 0041·0070 §2-3).
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 * userId 변환: domain ReconcileRun.userId (String) ↔ JPA entity user_id (Long) — toLong/toString 매핑.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class ReconcileRunRepositoryAdapter(
    private val jpaRepository: ReconcileRunJpaRepository,
) : ReconcileRunRepository {
    private val log = LoggerFactory.getLogger(ReconcileRunRepositoryAdapter::class.java)

    @Transactional
    override fun save(run: ReconcileRun): ReconcileRun {
        val entity = toEntity(run)
        val saved = jpaRepository.save(entity)
        log.debug("reconcile_run save: id={} userId={} status={}", saved.id, saved.userId, saved.status)
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findById(id: Long): ReconcileRun? =
        jpaRepository
            .findById(id)
            .map { toDomain(it) }
            .orElse(null)

    @Transactional(readOnly = true)
    override fun findByUserId(
        userId: String,
        limit: Int,
        offset: Int,
    ): List<ReconcileRun> {
        val userIdLong =
            userId.toLongOrNull() ?: run {
                log.warn("ReconcileRunRepositoryAdapter.findByUserId — userId를 Long으로 변환 실패: userId={}", userId)
                return emptyList()
            }
        val page = offset / limit
        val pageable = PageRequest.of(page, limit)
        return jpaRepository
            .findByUserIdOrderByStartedAtDesc(userIdLong, pageable)
            .content
            .map { toDomain(it) }
    }

    // --- private helper ---

    private fun toEntity(run: ReconcileRun): ReconcileRunJpaEntity {
        val userIdLong = run.userId.toLongOrNull() ?: 0L
        return ReconcileRunJpaEntity(
            id = run.id,
            userId = userIdLong,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            driftCount = run.driftCount,
            status = run.status.name,
            lastError = run.lastError,
            triggerSource = run.triggerSource.name,
        )
    }

    private fun toDomain(entity: ReconcileRunJpaEntity): ReconcileRun =
        ReconcileRun(
            id = entity.id,
            userId = entity.userId.toString(),
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            driftCount = entity.driftCount,
            status = safeStatus(entity.status),
            lastError = entity.lastError,
            triggerSource = safeTriggerSource(entity.triggerSource),
        )

    private fun safeStatus(value: String): ReconcileStatus =
        runCatching { ReconcileStatus.valueOf(value) }.getOrDefault(ReconcileStatus.ERROR)

    private fun safeTriggerSource(value: String): ReconcileTriggerSource =
        runCatching { ReconcileTriggerSource.valueOf(value) }.getOrDefault(ReconcileTriggerSource.CRON)
}
