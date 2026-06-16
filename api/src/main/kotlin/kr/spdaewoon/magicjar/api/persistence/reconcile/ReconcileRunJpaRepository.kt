package kr.spdaewoon.magicjar.api.persistence.reconcile

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * reconcile_run Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [ReconcileRunRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
interface ReconcileRunJpaRepository : JpaRepository<ReconcileRunJpaEntity, Long> {
    /** 사용자별 reconcile_run 목록 — started_at DESC 페이징 */
    fun findByUserIdOrderByStartedAtDesc(
        userId: Long,
        pageable: Pageable,
    ): Page<ReconcileRunJpaEntity>
}
