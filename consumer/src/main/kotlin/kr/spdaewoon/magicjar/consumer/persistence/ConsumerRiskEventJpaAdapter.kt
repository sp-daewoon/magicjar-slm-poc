package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.RiskVerdict
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant as JInstant

/**
 * Consumer-side RiskEventRepository implementation.
 */
@Component
class ConsumerRiskEventJpaAdapter(
    private val riskEventJpaRepository: ConsumerRiskEventJpaRepository,
) : RiskEventRepository {
    @Transactional
    override fun save(event: RiskEvent): RiskEvent {
        val saved = riskEventJpaRepository.save(toEntity(event))
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun countRecentBlockedByStrategy(
        strategyId: Long,
        windowMinutes: Long,
        excludedRules: Set<String>,
    ): Int {
        val since = JInstant.now().minusSeconds(windowMinutes * 60)
        // 빈 set 이면 기존 전수 카운트 query — 빈 collection IN 절 hazard 회피(F9).
        return if (excludedRules.isEmpty()) {
            riskEventJpaRepository.countRecentBlockedByStrategy(strategyId, since)
        } else {
            riskEventJpaRepository.countRecentBlockedByStrategyExcludingRules(strategyId, since, excludedRules)
        }
    }

    private fun toEntity(event: RiskEvent): ConsumerRiskEventJpaEntity =
        ConsumerRiskEventJpaEntity(
            id = event.id,
            userId = event.userId,
            signalId = event.signalId,
            rule = event.rule.name,
            verdict = event.verdict.name,
            reason = event.reason.takeIf { it.isNotBlank() },
            ts = event.createdAt.toJavaInstant(),
            createdAt = event.createdAt.toJavaInstant(),
        )

    private fun toDomain(entity: ConsumerRiskEventJpaEntity): RiskEvent =
        RiskEvent(
            id = entity.id,
            userId = entity.userId,
            signalId = entity.signalId,
            rule = safeRule(entity.rule),
            verdict = safeVerdict(entity.verdict),
            reason = entity.reason ?: "",
            createdAt = entity.createdAt.toKotlinInstant(),
        )

    private fun safeRule(value: String): RiskRule = runCatching { RiskRule.valueOf(value) }.getOrDefault(RiskRule.USER_DISABLED)

    private fun safeVerdict(value: String): RiskVerdict = runCatching { RiskVerdict.valueOf(value) }.getOrDefault(RiskVerdict.BLOCKED)
}
