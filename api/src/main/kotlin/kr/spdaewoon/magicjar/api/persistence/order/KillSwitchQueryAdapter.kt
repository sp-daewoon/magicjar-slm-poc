package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * KillSwitchQueryPort JPA 어댑터 (api 모듈 — ADR 0068 §14-2 step 4 LOCK).
 *
 * kill_switch_state.enabled = true 이면 ORDER_ROUTER step 4에서 즉시 REJECTED.
 * row 없음·조회 실패는 ADR 0098 정합상 fail-closed ON으로 반환한다.
 * api 모듈에서 read-only 조회만 수행.
 *
 * 헌법 §6 (ADR 0051): body 0건 [KillSwitchQueryJpaRepository] 위임.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KillSwitchQueryAdapter(
    private val repo: KillSwitchQueryJpaRepository,
) : KillSwitchQueryPort {
    private val log = LoggerFactory.getLogger(KillSwitchQueryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun isOn(userId: String): Boolean =
        try {
            repo
                .findById(userId)
                .map { it.enabled }
                .orElseGet {
                    log.error("KillSwitch row 없음 — fail-closed ON: userId={}****", userId.take(4))
                    true
                }
        } catch (e: Exception) {
            log.error("KillSwitch query 실패 — fail-closed ON: userId={}**** err={}", userId.take(4), e.message)
            true
        }
}
