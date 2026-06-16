package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

class KisRealizedProfitAdapterTest {
    private val adapter =
        KisRealizedProfitAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true),
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )

    @Test
    fun `inquire-balance-rlz-pl output1을 실현손익 item으로 매핑한다`() {
        val result =
            adapter.parseItems(
                listOf(
                    mapOf(
                        "pdno" to "005930",
                        "prdt_name" to "삼성전자",
                        "hldg_qty" to "10",
                        "ord_psbl_qty" to "7",
                        "pchs_amt" to "700000",
                        "evlu_amt" to "721000",
                        "evlu_pfls_amt" to "21000",
                        "rlzt_pfls" to "15000",
                        "rlzt_erng_rt" to "2.14",
                        "real_evlu_pfls" to "20500",
                    ),
                ),
            )

        assertThat(result).hasSize(1)
        assertThat(result[0].code).isEqualTo("005930")
        assertThat(result[0].name).isEqualTo("삼성전자")
        assertThat(result[0].holdingQty).isEqualTo(10)
        assertThat(result[0].orderableQty).isEqualTo(7)
        assertThat(result[0].purchaseAmount).isEqualByComparingTo(BigDecimal("700000"))
        assertThat(result[0].evaluationProfitLoss).isEqualByComparingTo(BigDecimal("21000"))
        assertThat(result[0].realizedProfitLoss).isEqualByComparingTo(BigDecimal("15000"))
        assertThat(result[0].realizedReturnRate).isEqualByComparingTo(BigDecimal("2.14"))
        assertThat(result[0].realEvaluationProfitLoss).isEqualByComparingTo(BigDecimal("20500"))
    }

    @Test
    fun `inquire-balance-rlz-pl output2를 실현손익 summary로 매핑한다`() {
        val result =
            adapter.parseSummary(
                listOf(
                    mapOf(
                        "dnca_tot_amt" to "1000000",
                        "nxdy_excc_amt" to "900000",
                        "prvs_rcdl_excc_amt" to "800000",
                        "tot_evlu_amt" to "1721000",
                        "nass_amt" to "1800000",
                        "evlu_pfls_smtl_amt" to "21000",
                        "rlzt_pfls" to "15000",
                        "rlzt_erng_rt" to "2.14",
                        "real_evlu_pfls" to "20500",
                        "real_evlu_pfls_erng_rt" to "2.07",
                    ),
                ),
            )

        assertThat(result).isNotNull
        assertThat(result!!.cashBalance).isEqualByComparingTo(BigDecimal("1000000"))
        assertThat(result.cashD2).isEqualByComparingTo(BigDecimal("800000"))
        assertThat(result.totalEvaluation).isEqualByComparingTo(BigDecimal("1721000"))
        assertThat(result.netAssetValue).isEqualByComparingTo(BigDecimal("1800000"))
        assertThat(result.totalEvaluationProfitLoss).isEqualByComparingTo(BigDecimal("21000"))
        assertThat(result.realizedProfitLoss).isEqualByComparingTo(BigDecimal("15000"))
        assertThat(result.realizedReturnRate).isEqualByComparingTo(BigDecimal("2.14"))
        assertThat(result.realEvaluationReturnRate).isEqualByComparingTo(BigDecimal("2.07"))
    }
}
