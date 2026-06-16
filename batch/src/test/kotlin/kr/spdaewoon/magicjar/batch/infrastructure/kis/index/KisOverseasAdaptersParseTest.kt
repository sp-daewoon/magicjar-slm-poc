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
 * KisOverseasIndexAdapter + KisOverseasIndexDailyAdapter parsing 단위 테스트.
 *
 * raw 출처:
 *  - HHDFS00000300: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/price/price.py
 *  - HHDFS76240000: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/dailyprice/dailyprice.py
 *
 * 본 테스트는 .IXIC(NASDAQ Composite) symbol query 정합 + output 파싱 검증.
 */
class KisOverseasAdaptersParseTest {
    private val objectMapper = ObjectMapper()
    private val priceAdapter =
        KisOverseasIndexAdapter(
            webClient = mock<WebClient>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            objectMapper = objectMapper,
            gate = KisIndexCredentials.forTest(enabled = false),
        )
    private val dailyAdapter =
        KisOverseasIndexDailyAdapter(
            webClient = mock<WebClient>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            objectMapper = objectMapper,
            gate = KisIndexCredentials.forTest(enabled = false),
            maxPages = 5,
        )

    @Test
    fun `해외 price output last 정합 — NASDAQ IXIC composite symbol`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output" to
                    mapOf(
                        "last" to "16812.4525",
                        "rate" to "0.85",
                        "tvol" to "4234567890",
                    ),
            )
        val snapshot = priceAdapter.parseSnapshot(IndexCode.NASDAQ, response)
        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.code).isEqualTo(".IXIC")
        assertThat(snapshot.price).isEqualByComparingTo(BigDecimal("16812.4525"))
        assertThat(snapshot.changeRate).isEqualByComparingTo(BigDecimal("0.85"))
        assertThat(snapshot.volume).isEqualTo(4_234_567_890L)
    }

    @Test
    fun `해외 price last 0 시 null (mock 합성 0 LOCK)`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output" to mapOf("last" to "0"),
            )
        assertThat(priceAdapter.parseSnapshot(IndexCode.NASDAQ, response)).isNull()
    }

    @Test
    fun `해외 dailyprice output2 array — xymd open clos tvol 정합`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output1" to mapOf("rsym" to "DNASIXIC"),
                "output2" to
                    listOf(
                        mapOf(
                            "xymd" to "20260523",
                            "open" to "16800.0",
                            "high" to "16820.0",
                            "low" to "16780.0",
                            "clos" to "16812.45",
                            "tvol" to "4200000000",
                        ),
                        mapOf(
                            "xymd" to "20260522",
                            "open" to "16780.0",
                            "high" to "16810.0",
                            "low" to "16770.0",
                            "clos" to "16795.5",
                            "tvol" to "4100000000",
                        ),
                    ),
            )

        val daily = dailyAdapter.parseDaily(IndexCode.NASDAQ, response)
        assertThat(daily).hasSize(2)
        assertThat(daily[0].tradeDate).isEqualTo(LocalDate.of(2026, 5, 23))
        assertThat(daily[0].close).isEqualByComparingTo(BigDecimal("16812.45"))
        assertThat(daily[0].volume).isEqualTo(4_200_000_000L)
    }
}
