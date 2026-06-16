package kr.spdaewoon.magicjar.api.persistence.trendline

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface TrendlineJpaRepository : JpaRepository<TrendlineJpaEntity, Long> {
    fun findByUserIdAndCodeOrderByCreatedAtDesc(
        userId: String,
        code: String,
    ): List<TrendlineJpaEntity>

    fun findByIdAndUserIdAndCode(
        id: Long,
        userId: String,
        code: String,
    ): TrendlineJpaEntity?

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    fun deleteByIdAndUserIdAndCode(
        id: Long,
        userId: String,
        code: String,
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    fun deleteByUserIdAndCodeAndSourceAndSourceInterval(
        userId: String,
        code: String,
        source: String,
        sourceInterval: String,
    ): Int
}
