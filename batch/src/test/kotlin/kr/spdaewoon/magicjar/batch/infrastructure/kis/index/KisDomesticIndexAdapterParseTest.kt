package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * KisDomesticIndexAdapter parseSnapshot 단위 테스트 — FHPUP02100000 raw 응답 정합 검증.
 *
 * KIS open-trading-api raw 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/inquire_index_price.py
 *
 * 본 테스트는 mock 합성 0 LOCK 정합 — 실 KIS 호출 없이 fixture 파싱만 검증.
 */
class KisDomesticIndexAdapterParseTest {
    private val objectMapper = ObjectMapper()
    private val adapter =
        KisDomesticIndexAdapter(
            webClient = mock<WebClient>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            objectMapper = objectMapper,
            gate = KisIndexCredentials.forTest(enabled = false),
        )

    @Test
    fun `정상 응답 — output map 1건 parse 성공`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "msg_cd" to "MCA00000",
                "output" to
                    mapOf(
                        "bstp_nmix_prpr" to "2640.50",
                        "bstp_nmix_prdy_ctrt" to "0.45",
                        "acml_vol" to "123456",
                    ),
            )
        val snapshot = adapter.parseSnapshot(IndexCode.KOSPI, response)
        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.price).isEqualByComparingTo(BigDecimal("2640.50"))
        assertThat(snapshot.changeRate).isEqualByComparingTo(BigDecimal("0.45"))
        assertThat(snapshot.volume).isEqualTo(123456L)
        assertThat(snapshot.code).isEqualTo("0001")
        assertThat(snapshot.rawPayload).contains("bstp_nmix_prpr")
    }

    @Test
    fun `output 부재 시 null 반환 — graceful skip`() {
        val response = mapOf("rt_cd" to "1", "msg_cd" to "MCA00001")
        assertThat(adapter.parseSnapshot(IndexCode.KOSPI, response)).isNull()
    }

    @Test
    fun `price 0 또는 음수 시 null 반환 — mock 합성 0 LOCK 정합`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output" to mapOf("bstp_nmix_prpr" to "0"),
            )
        assertThat(adapter.parseSnapshot(IndexCode.KOSPI, response)).isNull()
    }

    @Test
    fun `volume null 일 때 nullable 반환`() {
        val response =
            mapOf(
                "rt_cd" to "0",
                "output" to mapOf("bstp_nmix_prpr" to "100.0"),
            )
        val snapshot = adapter.parseSnapshot(IndexCode.KOSPI, response)
        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.volume).isNull()
        assertThat(snapshot.changeRate).isNull()
    }
}
