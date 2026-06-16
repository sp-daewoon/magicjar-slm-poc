package kr.spdaewoon.magicjar.persistence.shared.system

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * system_config Spring Data JPA Repository.
 * singleton (id=1) — findSingleton()으로 항상 1건 조회.
 */
interface SystemConfigJpaRepository : JpaRepository<SystemConfigJpaEntity, Short> {
    @Query("SELECT s FROM SystemConfigJpaEntity s WHERE s.id = 1")
    fun findSingleton(): SystemConfigJpaEntity?

    @Query("SELECT COUNT(s) > 0 FROM SystemConfigJpaEntity s WHERE s.id = 1 AND s.dartApiKey IS NOT NULL")
    fun hasDartApiKey(): Boolean

    @Query(
        "SELECT COUNT(s) > 0 FROM SystemConfigJpaEntity s WHERE s.id = 1 AND s.notificationSlackWebhook IS NOT NULL",
    )
    fun hasSlackWebhook(): Boolean
}
