package kr.spdaewoon.magicjar.persistence.shared.risk

import org.springframework.data.jpa.repository.JpaRepository

/**
 * risk_config Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [RiskConfigJpaAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskConfigJpaRepository : JpaRepository<RiskConfigJpaEntity, Long> {
    fun findByUserId(userId: String): RiskConfigJpaEntity?

    fun existsByUserId(userId: String): Boolean
}
