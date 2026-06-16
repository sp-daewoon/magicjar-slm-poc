package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * KisDomesticIndexDailyAdapter parseDaily 단위 테스트 — FHPUP02120000 raw output2 정합 검증.
 *
 * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_daily_price/inquire_index_daily_price.py
 */
class KisDomesticIndexDailyAdapterParseTest {
    private val objectMapper = ObjectMapper()
    private val adapter =
        KisDomesticIndexDailyAdapter(
            webClient = mock<WebClient>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            objectMapper = objectMapper,
            gate = KisIndexCredentials.forTest(enabled = false),
        )

    @Test
    fun `output2 array 3건 parse 성공 — 정합 OHLC 검증`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output2" to
                    listOf(
                        mapOf(
                            "stck_bsop_date" to "20260523",
                            "bstp_nmix_oprc" to "2640.0",
                            "bstp_nmix_hgpr" to "2650.5",
                            "bstp_nmix_lwpr" to "2630.0",
                            "bstp_nmix_prpr" to "2645.0",
                            "acml_vol" to "1000000",
                        ),
                        mapOf(
                            "stck_bsop_date" to "20260522",
                            "bstp_nmix_oprc" to "2620.0",
                            "bstp_nmix_hgpr" to "2645.0",
                            "bstp_nmix_lwpr" to "2615.0",
                            "bstp_nmix_prpr" to "2640.0",
                            "acml_vol" to "950000",
                        ),
                        // close=0 invalid skip
                        mapOf(
                            "stck_bsop_date" to "20260521",
                            "bstp_nmix_prpr" to "0",
                        ),
                    ),
            )

        val daily = adapter.parseDaily(IndexCode.KOSPI, response)
        assertThat(daily).hasSize(2) // 3번째는 close=0 → skip
        assertThat(daily[0].tradeDate).isEqualTo(LocalDate.of(2026, 5, 23))
        assertThat(daily[0].close).isEqualByComparingTo(BigDecimal("2645.0"))
        assertThat(daily[0].open).isEqualByComparingTo(BigDecimal("2640.0"))
        assertThat(daily[0].volume).isEqualTo(1_000_000L)
    }

    @Test
    fun `output2 빈 list 시 empty 반환`() {
        val response = mapOf("rt_cd" to "0", "output2" to emptyList<Any>())
        assertThat(adapter.parseDaily(IndexCode.KOSPI, response)).isEmpty()
    }

    @Test
    fun `output2 null 시 empty 반환 (graceful)`() {
        val response = mapOf<String, Any>("rt_cd" to "0")
        assertThat(adapter.parseDaily(IndexCode.KOSPI, response)).isEmpty()
    }
}
