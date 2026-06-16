package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate
import java.util.Optional

/**
 * KisRestCandleAdapter.fetchDailyCandlesInRange — ADR 0118 R-BF BACKFILL range fetch 계약 단위테스트.
 *
 * 검증:
 *   - from > to → 빈 list (가드).
 *   - REAL credential 부재 → [KisDailyRangeUnavailableException] (fail-closed — 빈 결과로 숨기지 않음).
 *
 * BG(BACKFILL) 버킷 acquire 는 코드 LOCK (fetchDailyRangeSinglePageBackfill 내부
 * `kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, ...)`). 전체 acquire-priority 통합 검증은
 * WebClient 통합(WireMock dogfooding) 후속이며, 본 단위테스트는 fail-closed 계약을 가드한다.
 */
class KisDailyCandleRangeBackfillTest {
    private fun buildAdapter(
        credentialRepository: ConsumerUserKisCredentialJpaRepository = mockk(relaxed = true),
        kisRateLimiterPort: KisRateLimiterPort = mockk(relaxed = true),
    ): KisRestCandleAdapter {
        val instrumentRepo = mockk<ConsumerInstrumentJpaRepository>(relaxed = true)
        // instrument 없음 → resolveMrktDivCodeCandidates = listOf("J") (NXT 후보 아님).
        every { instrumentRepo.findById(any()) } returns Optional.empty()
        return KisRestCandleAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            credentialRepository = credentialRepository,
            instrumentRepository = instrumentRepo,
            kisRateLimiterPort = kisRateLimiterPort,
            chartCandleProperties = ChartCandleProperties(),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )
    }

    @Test
    fun `from after to - 빈 list (가드)`() {
        val rateLimiter = mockk<KisRateLimiterPort>(relaxed = true)
        val adapter = buildAdapter(kisRateLimiterPort = rateLimiter)

        val result =
            adapter.fetchDailyCandlesInRange(
                "005930",
                from = LocalDate.of(2025, 1, 10),
                to = LocalDate.of(2025, 1, 1),
            )

        assertThat(result).isEmpty()
        // 가드 단계 — KIS acquire 0 (콜 0).
        verify(exactly = 0) { rateLimiter.acquire(any(), any(), any()) }
    }

    @Test
    fun `REAL credential 부재 - fail-closed 예외 전파 (빈 결과로 숨기지 않음)`() {
        val credentialRepo = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { credentialRepo.findAllByAccountType(any()) } returns emptyList()
        val adapter = buildAdapter(credentialRepository = credentialRepo)

        assertThatThrownBy {
            adapter.fetchDailyCandlesInRange(
                "005930",
                from = LocalDate.now().minusYears(10),
                to = LocalDate.now().minusDays(1),
            )
        }.isInstanceOf(KisDailyRangeUnavailableException::class.java)
    }
}
