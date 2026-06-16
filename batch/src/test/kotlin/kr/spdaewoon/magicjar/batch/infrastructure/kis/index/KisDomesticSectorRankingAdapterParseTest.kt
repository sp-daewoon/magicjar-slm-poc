package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Instant

/**
 * KisDomesticSectorRankingAdapter parseSectorList 단위 테스트 — FHPUP02140000 raw output1 정합 검증.
 *
 * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_category_price/inquire_index_category_price.py
 *
 * 검증 포인트:
 *  - output1 sector list 정합 parsing
 *  - sector_code → IndexCode.KOSPI_SECTORS 카탈로그 이름 fallback
 *  - price 0 invalid skip
 */
class KisDomesticSectorRankingAdapterParseTest {
    private val objectMapper = ObjectMapper()
    private val adapter =
        KisDomesticSectorRankingAdapter(
            webClient = mock<WebClient>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            objectMapper = objectMapper,
            gate = KisIndexCredentials.forTest(enabled = false),
            maxPages = 10,
        )

    @Test
    fun `output1 sector 3건 parse — KOSPI 카탈로그 이름 fallback 정합`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output1" to
                    listOf(
                        mapOf(
                            "bstp_cls_code" to "0018",
                            "bstp_nmix_prpr" to "550.20",
                            "bstp_nmix_prdy_ctrt" to "1.25",
                            "acml_tr_pbmn" to "8500000000000",
                        ),
                        mapOf(
                            "bstp_cls_code" to "0010",
                            "bstp_nmix_prpr" to "23500.0",
                            "bstp_nmix_prdy_ctrt" to "-0.5",
                        ),
                        // price=0 skip
                        mapOf(
                            "bstp_cls_code" to "9999",
                            "bstp_nmix_prpr" to "0",
                        ),
                    ),
            )

        val now = Instant.now()
        val sectors = adapter.parseSectorList(response, now)

        assertThat(sectors).hasSize(2)
        val fin = sectors.first { it.sectorCode == "0018" }
        assertThat(fin.sectorName).isEqualTo("금융업") // IndexCode.KOSPI_SECTORS 카탈로그 fallback
        assertThat(fin.price).isEqualByComparingTo(BigDecimal("550.20"))
        assertThat(fin.changeRate).isEqualByComparingTo(BigDecimal("1.25"))
        assertThat(fin.tradeValue).isEqualByComparingTo(BigDecimal("8500000000000"))

        val ee = sectors.first { it.sectorCode == "0010" }
        assertThat(ee.sectorName).isEqualTo("전기전자")
        assertThat(ee.tradeValue).isNull() // acml_tr_pbmn 부재 시 null
    }

    @Test
    fun `output1 부재 시 empty 반환 (graceful)`() {
        val response = mapOf<String, Any>("rt_cd" to "1")
        assertThat(adapter.parseSectorList(response, Instant.now())).isEmpty()
    }
}
