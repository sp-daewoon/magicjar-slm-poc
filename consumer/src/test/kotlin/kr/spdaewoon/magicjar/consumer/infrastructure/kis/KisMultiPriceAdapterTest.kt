package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * KisMultiPriceAdapter — 멀티시세(FHKST11300006) raw 응답 파싱 + chunk + 결손 graceful 단위테스트 (ADR 0118 R1).
 *
 * KIS-first raw spec 정합:
 *   응답 output 필드 (COLUMN_MAPPING verbatim):
 *     inter_shrn_iscd · inter2_oprc · inter2_hgpr · inter2_lwpr · inter2_prpr ·
 *     inter2_prdy_clpr · acml_vol · acml_tr_pbmn · kospi_kosdaq_cls_name · mrkt_trtm_cls_name · hour_cls_code
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/chk_intstock_multprice.py
 *
 * 테스트 매트릭스:
 *   (parse-full) 정상 2종목 output → 6필드 + NXT evidence 필드 매핑 검증
 *   (parse-skip-blank-code) inter_shrn_iscd 공백/비6자리 row → skip (데이터 없음 path, mock 0)
 *   (parse-decimal-graceful) 빈 문자열 OHLC → null graceful (예외 0)
 *   (parse-empty-output) output 키 부재 → emptyList
 *   (chunk-30) 31종목 입력 → 30 + 1 chunk 분할 (MAX_SYMBOLS_PER_CALL=30 raw spec)
 *   (no-credential) REAL credential 0건 → 빈 결과 + KIS 호출 0 (fail-safe)
 */
class KisMultiPriceAdapterTest {
    private fun buildAdapter(
        credentialRepository: ConsumerUserKisCredentialJpaRepository = mockk(relaxed = true),
        rateLimiter: KisRateLimiterPort = mockk(relaxed = true),
    ): KisMultiPriceAdapter =
        KisMultiPriceAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            credentialRepository = credentialRepository,
            kisRateLimiterPort = rateLimiter,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )

    @Test
    fun `parse-full - raw output 2종목 6필드 + NXT evidence 필드 매핑`() {
        val adapter = buildAdapter()
        val body =
            mapOf(
                "rt_cd" to "0",
                "output" to
                    listOf(
                        mapOf(
                            "inter_shrn_iscd" to "005930",
                            "inter2_oprc" to "71000",
                            "inter2_hgpr" to "72500",
                            "inter2_lwpr" to "70800",
                            "inter2_prpr" to "72000",
                            "inter2_prdy_clpr" to "70900",
                            "acml_vol" to "12345678",
                            "acml_tr_pbmn" to "888888888888",
                            "kospi_kosdaq_cls_name" to "KOSPI",
                            "mrkt_trtm_cls_name" to "",
                            "hour_cls_code" to "0",
                        ),
                        mapOf(
                            "inter_shrn_iscd" to "000660",
                            "inter2_oprc" to "180000",
                            "inter2_hgpr" to "182000",
                            "inter2_lwpr" to "179000",
                            "inter2_prpr" to "181500",
                            "inter2_prdy_clpr" to "179500",
                            "acml_vol" to "2222222",
                            "acml_tr_pbmn" to "400000000000",
                            "kospi_kosdaq_cls_name" to "KOSPI",
                            "mrkt_trtm_cls_name" to "관리",
                            "hour_cls_code" to "1",
                        ),
                    ),
            )

        val quotes = adapter.parseMultiPriceResponse(body)

        assertThat(quotes).hasSize(2)
        val samsung = quotes.first { it.code == "005930" }
        assertThat(samsung.open).isEqualByComparingTo(BigDecimal("71000"))
        assertThat(samsung.high).isEqualByComparingTo(BigDecimal("72500"))
        assertThat(samsung.low).isEqualByComparingTo(BigDecimal("70800"))
        assertThat(samsung.close).isEqualByComparingTo(BigDecimal("72000"))
        assertThat(samsung.previousClose).isEqualByComparingTo(BigDecimal("70900"))
        assertThat(samsung.accumulatedVolume).isEqualTo(12345678L)
        assertThat(samsung.accumulatedTradeAmount).isEqualByComparingTo(BigDecimal("888888888888"))
        assertThat(samsung.kospiKosdaqClsName).isEqualTo("KOSPI")
        // mrkt_trtm_cls_name 빈 문자열 → null (NXT evidence 필드 graceful)
        assertThat(samsung.marketTreatmentClsName).isNull()
        assertThat(samsung.hourClsCode).isEqualTo("0")

        val sk = quotes.first { it.code == "000660" }
        assertThat(sk.marketTreatmentClsName).isEqualTo("관리")
        assertThat(sk.hourClsCode).isEqualTo("1")
    }

    @Test
    fun `parse-skip-blank-code - 공백 또는 비6자리 종목코드 row는 skip`() {
        val adapter = buildAdapter()
        val body =
            mapOf(
                "rt_cd" to "0",
                "output" to
                    listOf(
                        mapOf("inter_shrn_iscd" to "", "inter2_prpr" to "1000"),
                        mapOf("inter_shrn_iscd" to "12", "inter2_prpr" to "1000"),
                        mapOf("inter2_prpr" to "1000"), // inter_shrn_iscd 부재
                        mapOf("inter_shrn_iscd" to "005930", "inter2_prpr" to "72000"),
                    ),
            )

        val quotes = adapter.parseMultiPriceResponse(body)

        assertThat(quotes).hasSize(1)
        assertThat(quotes.first().code).isEqualTo("005930")
    }

    @Test
    fun `parse-decimal-graceful - 빈 문자열 OHLC는 null graceful (예외 0)`() {
        val adapter = buildAdapter()
        val body =
            mapOf(
                "rt_cd" to "0",
                "output" to
                    listOf(
                        mapOf(
                            "inter_shrn_iscd" to "005930",
                            "inter2_oprc" to "",
                            "inter2_hgpr" to " ",
                            "inter2_lwpr" to "abc",
                            "inter2_prpr" to "72000",
                            "acml_vol" to "",
                        ),
                    ),
            )

        val quotes = adapter.parseMultiPriceResponse(body)

        assertThat(quotes).hasSize(1)
        val q = quotes.first()
        assertThat(q.open).isNull()
        assertThat(q.high).isNull()
        assertThat(q.low).isNull()
        assertThat(q.close).isEqualByComparingTo(BigDecimal("72000"))
        assertThat(q.accumulatedVolume).isNull()
    }

    @Test
    fun `parse-empty-output - output 키 부재 시 emptyList`() {
        val adapter = buildAdapter()
        assertThat(adapter.parseMultiPriceResponse(mapOf("rt_cd" to "0"))).isEmpty()
        assertThat(adapter.parseMultiPriceResponse(mapOf("rt_cd" to "0", "output" to emptyList<Any>()))).isEmpty()
    }

    @Test
    fun `chunk-30 - 31종목 입력은 30 + 1 chunk로 분할 (raw MAX_SYMBOLS_PER_CALL=30)`() {
        assertThat(KisMultiPriceAdapter.MAX_SYMBOLS_PER_CALL).isEqualTo(30)
        // chunk 분할 정합 검증 (List.chunked 동작 — 31 → [30, 1])
        val codes = (1..31).map { "%06d".format(it) }
        val chunks = codes.chunked(KisMultiPriceAdapter.MAX_SYMBOLS_PER_CALL)
        assertThat(chunks).hasSize(2)
        assertThat(chunks[0]).hasSize(30)
        assertThat(chunks[1]).hasSize(1)
    }

    @Test
    fun `no-credential - REAL credential 0건이면 빈 결과 + KIS 호출 0`() {
        val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { credentialRepository.findAllByAccountType(any()) } returns emptyList()
        val rateLimiter = mockk<KisRateLimiterPort>(relaxed = true)
        val adapter = buildAdapter(credentialRepository = credentialRepository, rateLimiter = rateLimiter)

        val result = adapter.fetchMultiPrice(listOf("005930", "000660"), "J")

        assertThat(result).isEmpty()
        // credential 없으면 rate-limiter acquire 자체가 호출되지 않아야 함 (fail-safe — 콜 0)
        verify(exactly = 0) { rateLimiter.acquire(any(), any(), any()) }
    }

    @Test
    fun `empty-codes - 유효 종목코드 0건이면 credential 조회 전 빈 결과`() {
        val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)
        val adapter = buildAdapter(credentialRepository = credentialRepository)

        val result = adapter.fetchMultiPrice(listOf("", "12", "abcdef"), "J")

        assertThat(result).isEmpty()
        verify(exactly = 0) { credentialRepository.findAllByAccountType(any()) }
    }
}
