package kr.spdaewoon.magicjar.api.service.reconcile

import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaRepository
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Reconcile 조회 서비스 — T-RECON Phase 2 (ADR 0041·0070 §2-3 + spec §2-3).
 *
 * ### 제공 기능
 * - listRuns: userId 격리 + 시계열 페이징 (started_at DESC)
 * - findRunDetail: 단건 + userId 격리 검증 (mismatch 시 404 LOCK — ADR 0010)
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Service
class ReconcileQueryService(
    private val jpaRepository: ReconcileRunJpaRepository,
) {
    private val log = LoggerFactory.getLogger(ReconcileQueryService::class.java)

    /**
     * userId별 reconcile_run 목록 페이징 조회 (started_at DESC).
     *
     * @param userId 사용자 ID (multi-tenant 격리)
     * @param page 0-based 페이지 번호
     * @param size 페이지당 row 수
     */
    @Transactional(readOnly = true)
    fun listRuns(
        userId: Long,
        page: Int,
        size: Int,
    ): Page<ReconcileRun> {
        val pageable = PageRequest.of(page, size)
        val entityPage = jpaRepository.findByUserIdOrderByStartedAtDesc(userId, pageable)
        val runs = entityPage.content.map { toDomain(it) }
        log.debug(
            "ReconcileQueryService.listRuns: userId={}**** page={} size={} total={}",
            userId.toString().take(4),
            page,
            size,
            entityPage.totalElements,
        )
        return PageImpl(runs, pageable, entityPage.totalElements)
    }

    /**
     * 단건 상세 조회 — userId 격리 검증 (ADR 0010 multi-tenant).
     *
     * @throws ResponseStatusException 404 — 존재하지 않거나 userId 불일치
     */
    @Transactional(readOnly = true)
    fun findRunDetail(
        id: Long,
        userId: Long,
    ): ReconcileRun {
        val entity =
            jpaRepository.findById(id).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "reconcile run 없음: id=$id")
            }
        if (entity.userId != userId) {
            log.warn(
                "ReconcileQueryService.findRunDetail — userId 불일치: 요청={} 실제={}",
                userId,
                entity.userId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "reconcile run 없음: id=$id")
        }
        return toDomain(entity)
    }

    // --- private helper ---

    private fun toDomain(entity: kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaEntity): ReconcileRun =
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
