package kr.spdaewoon.magicjar.api.order

import kr.spdaewoon.magicjar.api.persistence.order.RealTradingConsentJpaAdapter
import kr.spdaewoon.magicjar.api.service.order.RealTradingConsentService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

/**
 * RealTradingConsentServiceTest — ADR 0068 §15-6 LOCK 2 케이스.
 *
 * (a) toggle ON → real_trading_consent_at=NOW() · (b) toggle OFF → NULL
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class RealTradingConsentServiceTest {
    private val consentAdapter: RealTradingConsentJpaAdapter =
        Mockito.mock(RealTradingConsentJpaAdapter::class.java)
    private val sut = RealTradingConsentService(consentAdapter)

    @Test
    fun `(a) toggle ON — real_trading_consent_at=NOW()`() {
        val userId = "user1"
        val now = Instant.now()

        Mockito.doReturn(now).`when`(consentAdapter).setConsent(userId, true)

        val result = sut.setConsent(userId, true)

        assertNotNull(result)
    }

    @Test
    fun `(b) toggle OFF — real_trading_consent_at=NULL`() {
        val userId = "user1"

        Mockito.doReturn(null).`when`(consentAdapter).setConsent(userId, false)

        val result = sut.setConsent(userId, false)

        assertNull(result)
    }
}
