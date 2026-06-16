package kr.spdaewoon.magicjar.api.service.order

import kr.spdaewoon.magicjar.api.persistence.order.RealTradingConsentJpaAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 실거래 동의 토글 서비스 (ADR 0068 §15-2 — 의제 A-1 3중 게이트 조건 3).
 *
 * toggle ON  → user_account.real_trading_consent_at = NOW()
 * toggle OFF → user_account.real_trading_consent_at = NULL
 *
 * SettingsModal PUT /api/user-account/real-trading-consent 진입.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Service
class RealTradingConsentService(
    private val consentAdapter: RealTradingConsentJpaAdapter,
) {
    private val log = LoggerFactory.getLogger(RealTradingConsentService::class.java)

    /**
     * 실거래 동의 토글.
     *
     * @param userId KIS credential identity
     * @param consented true → 동의 시각 기록, false → NULL
     * @return 동의 시각 (consented=true 시 NOW(), false 시 null)
     */
    @Transactional
    fun setConsent(
        userId: String,
        consented: Boolean,
    ): Instant? {
        val result = consentAdapter.setConsent(userId, consented)
        log.info("실거래 동의 토글: userId={} consented={} consentAt={}", userId, consented, result)
        return result
    }

    /**
     * 현재 동의 상태 조회.
     */
    @Transactional(readOnly = true)
    fun getConsentAt(userId: String): Instant? = consentAdapter.findConsentAt(userId)
}
