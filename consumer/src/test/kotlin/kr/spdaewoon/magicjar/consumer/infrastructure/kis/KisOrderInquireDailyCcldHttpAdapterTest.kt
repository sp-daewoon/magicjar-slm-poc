package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * KisOrderInquireDailyCcldHttpAdapter 단위 테스트 (ADR 0084 §2-2 P0-2 — 외부 코드 리뷰 결함 #3 verify).
 *
 * ### 5+ 케이스 매트릭스
 *  (a) tr_id REAL inner → TTTC0081R (raw L107·L110 verbatim)
 *  (b) tr_id MOCK inner → VTTC0081R (raw L114·L117 verbatim)
 *  (c) tr_id REAL before → CTSC9215R (raw L108 verbatim — 본 baseline 미사용이나 constant 검증)
 *  (d) tr_id MOCK before → VTSC9215R (raw L115 verbatim — 본 baseline 미사용이나 constant 검증)
 *  (e) ENDPOINT constant — raw L21 verbatim
 *  (f) 자격증명 미발견 → OrderInquireResult.Failed("CREDENTIAL_NOT_FOUND")
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-2).
 */
class KisOrderInquireDailyCcldHttpAdapterTest {
    private val webClient = mockk<WebClient>(relaxed = true)
    private val secretCipher = mockk<SecretCipher>(relaxed = true)
    private val kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true)
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)

    private val adapter =
        KisOrderInquireDailyCcldHttpAdapter(
            webClient = webClient,
            secretCipher = secretCipher,
            kisAuthAdapter = kisAuthAdapter,
            credentialRepository = credentialRepository,
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `(a) tr_id REAL inner constant = TTTC0081R (raw L107·L110)`() {
        // raw inquire_daily_ccld.py L107·L110 verbatim: env=real + pd=inner → TTTC0081R
        assertThat(KisOrderInquireDailyCcldHttpAdapter.TR_ID_REAL_INNER).isEqualTo("TTTC0081R")
    }

    @Test
    fun `(b) tr_id MOCK inner constant = VTTC0081R (raw L114·L117)`() {
        // raw inquire_daily_ccld.py L114·L117 verbatim: env=demo + pd=inner → VTTC0081R
        assertThat(KisOrderInquireDailyCcldHttpAdapter.TR_ID_MOCK_INNER).isEqualTo("VTTC0081R")
    }

    @Test
    fun `(c) tr_id REAL before constant = CTSC9215R (raw L108)`() {
        // raw inquire_daily_ccld.py L108: env=real + pd=before → CTSC9215R (3개월 이전 backfill용)
        assertThat(KisOrderInquireDailyCcldHttpAdapter.TR_ID_REAL_BEFORE).isEqualTo("CTSC9215R")
    }

    @Test
    fun `(d) tr_id MOCK before constant = VTSC9215R (raw L115)`() {
        // raw inquire_daily_ccld.py L115: env=demo + pd=before → VTSC9215R
        assertThat(KisOrderInquireDailyCcldHttpAdapter.TR_ID_MOCK_BEFORE).isEqualTo("VTSC9215R")
    }

    @Test
    fun `(e) ENDPOINT constant = inquire-daily-ccld path (raw L21)`() {
        // raw inquire_daily_ccld.py L21 verbatim: API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        assertThat(KisOrderInquireDailyCcldHttpAdapter.ENDPOINT)
            .isEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
    }

    @Test
    fun `(f) 자격증명 미발견 → Failed CREDENTIAL_NOT_FOUND`() {
        every { credentialRepository.findAllByAccountType("REAL") } returns emptyList()
        every { credentialRepository.findAllByAccountType("MOCK") } returns emptyList()

        val result =
            adapter.inquireByOrderNo(
                userId = "12345678",
                mode = TradingMode.REAL,
                kisOrderNo = "0000123456",
                inquireStartDate = LocalDate.of(2026, 5, 18),
                inquireEndDate = LocalDate.of(2026, 5, 18),
            )

        assertThat(result).isInstanceOf(OrderInquireResult.Failed::class.java)
        assertThat((result as OrderInquireResult.Failed).cause).isEqualTo("CREDENTIAL_NOT_FOUND")
    }

    private fun stubCredential(accountType: String = "REAL") =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = "12345678", accountType = accountType),
            accountNumber = "50000000",
            accountPrdtCd = "01",
            appKeyEnc = ByteArray(0),
            secretKeyEnc = ByteArray(0),
        )
}
