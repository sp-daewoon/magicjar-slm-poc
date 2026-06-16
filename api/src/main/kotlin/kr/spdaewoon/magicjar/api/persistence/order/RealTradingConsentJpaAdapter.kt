package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * RealTradingConsentPort JPA 어댑터 — V23 user_account.real_trading_consent_at 정합 (ADR 0068 §15-2 LOCK).
 *
 * 3중 게이트 조건 3: NULL → CONSENT_NOT_GIVEN denied → REAL 주문 BLOCKED.
 *
 * [setConsent] — RealTradingConsentService에서 직접 호출 (port 외 추가 메서드).
 *
 * 헌법 §6 (ADR 0051): body 0건 [UserAccountJpaRepository] 위임.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class RealTradingConsentJpaAdapter(
    private val userAccountJpaRepository: UserAccountJpaRepository,
) : RealTradingConsentPort {
    @Transactional(readOnly = true)
    override fun findConsentAt(userId: String): Instant? =
        userAccountJpaRepository.findById(userId).map { it.realTradingConsentAt }.orElse(null)

    /**
     * 실거래 동의 토글 — ON: NOW() / OFF: null.
     * RealTradingConsentService에서 호출.
     */
    @Transactional
    fun setConsent(
        userId: String,
        consented: Boolean,
    ): Instant? {
        val entity =
            userAccountJpaRepository.findById(userId).orElseThrow {
                IllegalArgumentException("USER_NOT_FOUND: $userId")
            }
        entity.realTradingConsentAt = if (consented) Instant.now() else null
        userAccountJpaRepository.save(entity)
        return entity.realTradingConsentAt
    }
}
