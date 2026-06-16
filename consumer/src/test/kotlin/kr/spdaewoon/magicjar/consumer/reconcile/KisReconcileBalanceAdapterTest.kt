package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * KisReconcileBalanceAdapter 단위 테스트 (ADR 0070 §8 raw 정합 검증).
 *
 * 2 케이스 (parseHoldings 순수 함수 + 경계):
 *  (a) parseHoldings — KIS output1 → ReconcileHolding 매핑 정합
 *  (b) parseHoldings — 수량 0주 종목 필터링 + 결측 필드 graceful skip
 *
 * 페이징 loop / WebClient 호출 시나리오는 통합 테스트 영역 — 단위 테스트는 parseHoldings(internal) 검증으로 충분.
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class KisReconcileBalanceAdapterTest {
    private fun newAdapter(): KisReconcileBalanceAdapter {
        // parseHoldings는 internal 순수 함수 — webClient·secretCipher 등 의존 무관
        return KisReconcileBalanceAdapter(
            webClient = WebClient.create(),
            secretCipher = SecretCipherStub(),
            kisAuthAdapter = stubKisAuthAdapter(),
            credentialRepository = stubCredRepo(),
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            magicjarMode = "REAL",
        )
    }

    @Test
    fun `(a) parseHoldings — KIS output1 → ReconcileHolding 매핑 정합`() {
        val adapter = newAdapter()
        val output1 =
            listOf<Map<String, Any?>>(
                mapOf(
                    "pdno" to "005930",
                    "hldg_qty" to "100",
                    "pchs_avg_pric" to "70000.5",
                ),
                mapOf(
                    "pdno" to "000660",
                    "hldg_qty" to "50",
                    "pchs_avg_pric" to "130000",
                ),
            )

        val holdings = adapter.parseHoldings(output1)

        assertThat(holdings).hasSize(2)
        assertThat(holdings[0].symbol).isEqualTo("005930")
        assertThat(holdings[0].quantity).isEqualTo(100)
        assertThat(holdings[0].avgPrice).isEqualByComparingTo(BigDecimal("70000.5"))
        assertThat(holdings[1].symbol).isEqualTo("000660")
    }

    @Test
    fun `(b) parseHoldings — 수량 0주 필터링 + 결측 pdno graceful skip`() {
        val adapter = newAdapter()
        val output1 =
            listOf<Map<String, Any?>>(
                mapOf("pdno" to "005930", "hldg_qty" to "100", "pchs_avg_pric" to "70000"),
                mapOf("pdno" to "999999", "hldg_qty" to "0", "pchs_avg_pric" to "0"), // 0주 필터
                mapOf("hldg_qty" to "50", "pchs_avg_pric" to "10000"), // pdno 결측 skip
                mapOf("pdno" to "000660", "hldg_qty" to "50"), // avgPrice 결측 → ZERO default
            )

        val holdings = adapter.parseHoldings(output1)

        assertThat(holdings).hasSize(2) // 0주·pdno 결측 모두 필터
        assertThat(holdings.map { it.symbol }).containsExactly("005930", "000660")
        assertThat(holdings.first { it.symbol == "000660" }.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
    }

    // ---- stub helpers ----
    private fun stubKisAuthAdapter(): kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisAuthAdapter {
        // KisAuthAdapter 생성자 의존 회피 — io.mockk.mockk 활용
        return io.mockk.mockk(relaxed = true)
    }

    private fun stubCredRepo(): kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository =
        io.mockk.mockk(relaxed = true)

    private class SecretCipherStub : kr.spdaewoon.magicjar.domain.secret.port.SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(cipher: ByteArray): ByteArray = cipher
    }
}
