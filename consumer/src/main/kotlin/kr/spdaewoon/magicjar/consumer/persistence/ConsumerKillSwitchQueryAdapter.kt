package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side kill-switch query adapter for the order router and risk gateway.
 *
 * 자본 경로 권위 조회이므로 row 없음·조회 실패는 ADR 0098 정합상 fail-closed ON으로 반환한다.
 */
@Component
class ConsumerKillSwitchQueryAdapter(
    private val killSwitchJpaRepository: KillSwitchJpaRepository,
) : KillSwitchQueryPort {
    private val log = LoggerFactory.getLogger(ConsumerKillSwitchQueryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun isOn(userId: String): Boolean =
        try {
            killSwitchJpaRepository
                .findById(userId)
                .map { it.enabled }
                .orElseGet {
                    log.error("consumer KillSwitch row 없음 — fail-closed ON: userId={}****", userId.take(4))
                    true
                }
        } catch (e: Exception) {
            log.error("consumer KillSwitch query 실패 — fail-closed ON: userId={}**** err={}", userId.take(4), e.message)
            true
        }
}
