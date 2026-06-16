package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * reconcile_run Spring Data repository for the consumer module.
 */
interface ConsumerReconcileRunJpaRepository : JpaRepository<ConsumerReconcileRunJpaEntity, Long> {
    fun findByUserIdOrderByStartedAtDesc(
        userId: Long,
        pageable: Pageable,
    ): Page<ConsumerReconcileRunJpaEntity>
}
