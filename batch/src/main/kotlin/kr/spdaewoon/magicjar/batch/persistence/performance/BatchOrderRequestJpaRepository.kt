package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * batch 모듈 order_request 읽기 전용 Repository — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob이 처리할 사용자 목록 조회용.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface BatchOrderRequestJpaRepository : JpaRepository<BatchOrderRequestJpaEntity, Long> {
    @Query("SELECT DISTINCT r.userId FROM BatchOrderRequestJpaEntity r")
    fun findDistinctUserIds(): List<String>
}
