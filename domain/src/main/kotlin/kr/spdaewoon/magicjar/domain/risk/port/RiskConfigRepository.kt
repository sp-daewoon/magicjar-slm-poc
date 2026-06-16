package kr.spdaewoon.magicjar.domain.risk.port

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.risk.RiskConfig

/**
 * RiskConfig 영속 포트 (ADR 0067 §5-5). 헌법 §6 abstract만.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskConfigRepository {
    fun findByUserId(userId: String): RiskConfig?

    fun getOrDefault(
        userId: String,
        now: Instant,
    ): RiskConfig

    fun save(config: RiskConfig): RiskConfig
}
