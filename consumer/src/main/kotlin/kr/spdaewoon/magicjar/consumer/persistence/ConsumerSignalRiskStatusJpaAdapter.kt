package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side signal.risk_status updater for the automatic trading pipeline.
 */
@Component
class ConsumerSignalRiskStatusJpaAdapter(
    private val signalJpaRepository: ConsumerSignalJpaRepository,
) : SignalRiskStatusUpdater {
    private val log = LoggerFactory.getLogger(ConsumerSignalRiskStatusJpaAdapter::class.java)

    @Transactional
    override fun markAllowed(signalId: Long) {
        val updated = signalJpaRepository.updateRiskStatus(signalId, "ALLOWED")
        log.debug("consumer signal.risk_status ALLOWED: signalId={} updated={}", signalId, updated)
    }

    @Transactional
    override fun markBlocked(
        signalId: Long,
        rule: RiskRule,
        reason: String,
    ) {
        val updated = signalJpaRepository.updateRiskStatus(signalId, "BLOCKED")
        log.debug(
            "consumer signal.risk_status BLOCKED: signalId={} rule={} reason={} updated={}",
            signalId,
            rule.name,
            reason,
            updated,
        )
    }
}
