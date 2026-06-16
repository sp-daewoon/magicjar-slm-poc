package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * ConsumerRealTradingConsentJpaAdapter 단위 테스트 (REAL canary hotfix — 자본 게이트 조건 3).
 *
 * `real_trading_consent_at` non-null ↔ consented 매핑 검증:
 *   - non-null → 동의 시각 Instant 반환 (조건 3 통과)
 *   - null → null 반환 (CONSENT_NOT_GIVEN denied)
 *   - 사용자 부재 → null 반환 (fail-closed)
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class ConsumerRealTradingConsentJpaAdapterTest {
    private val repository = mockk<ConsumerUserAccountJpaRepository>()
    private val adapter = ConsumerRealTradingConsentJpaAdapter(repository)

    @Test
    fun `real_trading_consent_at 가 non-null 이면 동의 시각 Instant 를 반환한다`() {
        val consentAt = Instant.parse("2026-06-11T01:49:00Z")
        every { repository.findById("74683223") } returns
            Optional.of(ConsumerUserAccountJpaEntity(userId = "74683223", realTradingConsentAt = consentAt))

        val result = adapter.findConsentAt("74683223")

        assertThat(result).isEqualTo(consentAt)
    }

    @Test
    fun `real_trading_consent_at 가 NULL 이면 null 을 반환한다 (CONSENT_NOT_GIVEN)`() {
        every { repository.findById("74683223") } returns
            Optional.of(ConsumerUserAccountJpaEntity(userId = "74683223", realTradingConsentAt = null))

        val result = adapter.findConsentAt("74683223")

        assertThat(result).isNull()
    }

    @Test
    fun `사용자가 존재하지 않으면 null 을 반환한다 (fail-closed)`() {
        every { repository.findById("00000000") } returns Optional.empty()

        val result = adapter.findConsentAt("00000000")

        assertThat(result).isNull()
    }
}
