package kr.spdaewoon.magicjar.api.persistence.risk

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.RiskVerdict
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant as JInstant

/**
 * RiskEventRepository port JPA 어댑터 — Flyway V17 정합 (ADR 0067 §5-2 G-1).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * ### countRecentBlockedByStrategy 정합
 * strategy가 최근 windowMinutes 내 BLOCKED 이벤트 수 — AutoPauseGuard F-1 임계 검사.
 * signal.strategy_id → risk_event.signal_id JPQL subquery 방식 (signal FK 경유).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class RiskEventJpaAdapter(
    private val riskEventJpaRepository: RiskEventJpaRepository,
) : RiskEventRepository {
    @Transactional
    override fun save(event: RiskEvent): RiskEvent {
        val entity = toEntity(event)
        return toDomain(riskEventJpaRepository.save(entity))
    }

    @Transactional(readOnly = true)
    override fun countRecentBlockedByStrategy(
        strategyId: Long,
        windowMinutes: Long,
        excludedRules: Set<String>,
    ): Int {
        val since = JInstant.now().minusSeconds(windowMinutes * 60)
        // 빈 set 이면 기존 전수 카운트 query — 빈 collection IN 절 hazard 회피(F9, consumer adapter 와 동일 분기).
        return if (excludedRules.isEmpty()) {
            riskEventJpaRepository.countRecentBlockedByStrategy(strategyId, since)
        } else {
            riskEventJpaRepository.countRecentBlockedByStrategyExcludingRules(strategyId, since, excludedRules)
        }
    }

    /**
     * id로 단건 조회.
     * RiskEventQueryService에서 직접 호출.
     */
    @Transactional(readOnly = true)
    fun findById(id: Long): RiskEvent? = riskEventJpaRepository.findById(id).map { toDomain(it) }.orElse(null)

    /**
     * 사용자 시계열 risk_event 페이징 조회.
     * RiskEventQueryService에서 직접 호출 — port 외 추가 메서드.
     */
    @Transactional(readOnly = true)
    fun findByUserAndRange(
        userId: String,
        from: JInstant,
        to: JInstant,
        page: Int,
        size: Int,
    ): Page<RiskEvent> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "ts"))
        return riskEventJpaRepository
            .findByUserIdAndTsBetween(userId, from, to, pageable)
            .map { toDomain(it) }
    }

    // --- private helper ---

    private fun toEntity(event: RiskEvent): RiskEventJpaEntity =
        RiskEventJpaEntity(
            id = event.id,
            userId = event.userId,
            signalId = event.signalId,
            rule = event.rule.name,
            verdict = event.verdict.name,
            reason = event.reason.takeIf { it.isNotBlank() },
            ts = event.createdAt.toJavaInstant(),
            createdAt = event.createdAt.toJavaInstant(),
        )

    private fun toDomain(entity: RiskEventJpaEntity): RiskEvent =
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
