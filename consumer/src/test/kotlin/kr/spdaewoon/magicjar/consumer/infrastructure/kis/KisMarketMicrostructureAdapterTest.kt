package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

class KisMarketMicrostructureAdapterTest {
    private val adapter =
        KisMarketMicrostructureAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true),
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )

    @Test
    fun `inquire-investor output을 투자자 수급으로 매핑한다`() {
        val result =
            adapter.parseInvestorRows(
                code = "005930",
                marketDivCode = "J",
                rows =
                    listOf(
                        mapOf(
                            "stck_bsop_date" to "20260612",
                            "stck_clpr" to "72000",
                            "prsn_ntby_qty" to "-1000",
                            "frgn_ntby_qty" to "800",
                            "orgn_ntby_qty" to "200",
                            "frgn_ntby_tr_pbmn" to "57600000",
                        ),
                    ),
            )

        assertThat(result).hasSize(1)
        assertThat(result[0].businessDate).isEqualTo("20260612")
        assertThat(result[0].closePrice).isEqualByComparingTo(BigDecimal("72000"))
        assertThat(result[0].individualNetBuyQty).isEqualTo(-1000L)
        assertThat(result[0].foreignNetBuyQty).isEqualTo(800L)
        assertThat(result[0].institutionNetBuyQty).isEqualTo(200L)
        assertThat(result[0].foreignNetBuyAmount).isEqualByComparingTo(BigDecimal("57600000"))
    }

    @Test
    fun `inquire-ccnl output을 최근 체결로 매핑한다`() {
        val result =
            adapter.parseExecutionRows(
                code = "005930",
                marketDivCode = "UN",
                rows =
                    listOf(
                        mapOf(
                            "stck_cntg_hour" to "145930",
                            "stck_prpr" to "72100",
                            "cntg_vol" to "300",
                            "tday_rltv" to "121.5",
                            "prdy_ctrt" to "1.55",
                        ),
                    ),
            )

        assertThat(result).hasSize(1)
        assertThat(result[0].executionTime).isEqualTo("145930")
        assertThat(result[0].price).isEqualByComparingTo(BigDecimal("72100"))
        assertThat(result[0].volume).isEqualTo(300L)
        assertThat(result[0].relativeStrength).isEqualByComparingTo(BigDecimal("121.5"))
        assertThat(result[0].previousDayRate).isEqualByComparingTo(BigDecimal("1.55"))
    }
}
