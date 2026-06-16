package kr.spdaewoon.magicjar.api.persistence.signal

import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * SignalRiskStatusUpdater port JPA 어댑터 — Flyway V19 정합 (ADR 0067 §5-5 F-1).
 *
 * signal.risk_status 컬럼 PENDING → ALLOWED/BLOCKED 갱신.
 * 헌법 §6 (ADR 0051) 준수: body 가진 helper는 이 클래스 private fun 위치.
 *
 * ### 갱신 방식
 * JPQL @Modifying UPDATE — entity full reload 없이 단일 컬럼 갱신 (ADR 0067 §5-5 정합).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class SignalRiskStatusJpaAdapter(
    private val signalJpaRepository: SignalJpaRepository,
) : SignalRiskStatusUpdater {
    private val log = LoggerFactory.getLogger(SignalRiskStatusJpaAdapter::class.java)

    @Transactional
    override fun markAllowed(signalId: Long) {
        val updated = signalJpaRepository.updateRiskStatus(signalId, "ALLOWED")
        log.debug("signal.risk_status ALLOWED: signalId={} updated={}", signalId, updated)
    }

    @Transactional
    override fun markBlocked(
        signalId: Long,
        rule: RiskRule,
        reason: String,
    ) {
        val updated = signalJpaRepository.updateRiskStatus(signalId, "BLOCKED")
        log.debug(
            "signal.risk_status BLOCKED: signalId={} rule={} reason={} updated={}",
            signalId,
            rule.name,
            reason,
            updated,
        )
    }
}
