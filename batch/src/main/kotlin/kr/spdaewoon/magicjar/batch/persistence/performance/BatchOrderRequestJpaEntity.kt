package kr.spdaewoon.magicjar.batch.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * batch 모듈용 order_request 경량 JPA 엔티티 (읽기 전용 — 활성 사용자 목록 조회용).
 *
 * DailyPerformanceJob이 처리할 사용자 목록을 order_request에서 distinct 조회.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Entity
@Table(name = "order_request")
class BatchOrderRequestJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "code", nullable = false, length = 8)
    val code: String = "",
    @Column(name = "side", nullable = false, length = 4)
    val side: String = "BUY",
)
