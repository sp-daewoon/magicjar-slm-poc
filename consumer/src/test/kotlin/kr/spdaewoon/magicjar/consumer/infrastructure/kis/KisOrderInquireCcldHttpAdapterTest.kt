package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * KisOrderInquireCcldHttpAdapter 단위 테스트 — ADR 0084 P0-3.
 *
 * 본 테스트는 HTTP 호출 자체는 mock 없이 (WebClient mock 복잡도 회피) —
 * **순수 매핑·합성·파싱 helper 메서드** 중심 검증.
 *  (a) toExecution — 정상 row → KisDailyCcldExecution 매핑
 *  (b) toExecution — odno 빈 문자열 시 null 반환
 *  (c) toExecution — totCcldQty 0 시 null 반환 (미체결 제외)
 *  (d) toExecution — totCcldQty 음수·null 시 null 반환
 *  (e) parseExecutedAt — 정상 YYYYMMDD + HHMMSS → Instant 합성
 *  (f) parseExecutedAt — 파싱 실패 시 now() fallback (graceful)
 *  (g) raw verbatim — INQUIRE_DAILY_CCLD_PATH 상수 정합 (KIS-first 정합)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8).
 */
class KisOrderInquireCcldHttpAdapterTest {
    private val webClient = mockk<WebClient>(relaxed = true)
    private val secretCipher = mockk<SecretCipher>(relaxed = true)
    private val kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true)
    private val credentialRepo = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)

    private val adapter =
        KisOrderInquireCcldHttpAdapter(
            webClient = webClient,
            secretCipher = secretCipher,
            kisAuthAdapter = kisAuthAdapter,
            credentialRepository = credentialRepo,
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `(a) toExecution — 정상 row → KisDailyCcldExecution 매핑`() {
        val raw =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "ODNO123",
                pdno = "005930",
                sllBuyDvsnCd = "02",
                ordQty = "10",
                totCcldQty = "10",
                avgPrvs = "78000",
            )

        val exec = adapter.toExecution(raw)

        assertThat(exec).isNotNull
        assertThat(exec!!.kisOrderNo).isEqualTo("ODNO123")
        assertThat(exec.code).isEqualTo("005930")
        assertThat(exec.sellBuyCls).isEqualTo("02")
        assertThat(exec.ordQty).isEqualTo(10)
        assertThat(exec.totCcldQty).isEqualTo(10)
        assertThat(exec.avgPrice).isEqualByComparingTo(BigDecimal("78000"))
        assertThat(exec.ordDt).isEqualTo("20260518")
        assertThat(exec.ordTmd).isEqualTo("093015")
    }

    @Test
    fun `(b) toExecution — odno 빈 문자열 시 null 반환`() {
        val raw =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "",
                pdno = "005930",
                totCcldQty = "10",
                avgPrvs = "78000",
            )

        assertThat(adapter.toExecution(raw)).isNull()
    }

    @Test
    fun `(c) toExecution — totCcldQty 0 시 null 반환 (미체결 제외)`() {
        val raw =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "ODNO123",
                pdno = "005930",
                totCcldQty = "0",
                avgPrvs = "78000",
            )

        assertThat(adapter.toExecution(raw)).isNull()
    }

    @Test
    fun `(d) toExecution — totCcldQty null·blank·invalid 시 null 반환`() {
        val rawNull =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "ODNO123",
                pdno = "005930",
                totCcldQty = null,
                avgPrvs = "78000",
            )
        val rawBlank =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "ODNO123",
                pdno = "005930",
                totCcldQty = "  ",
                avgPrvs = "78000",
            )
        val rawNonNum =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "093015",
                odno = "ODNO123",
                pdno = "005930",
                totCcldQty = "ABC",
                avgPrvs = "78000",
            )

        assertThat(adapter.toExecution(rawNull)).isNull()
        assertThat(adapter.toExecution(rawBlank)).isNull()
        assertThat(adapter.toExecution(rawNonNum)).isNull()
    }

    @Test
    fun `(e) parseExecutedAt — 정상 YYYYMMDD + HHMMSS → Instant KST 합성`() {
        val instant = adapter.parseExecutedAt("20260518", "093015")
        val kstZdt = instant.atZone(ZoneId.of("Asia/Seoul"))

        assertThat(kstZdt.year).isEqualTo(2026)
        assertThat(kstZdt.monthValue).isEqualTo(5)
        assertThat(kstZdt.dayOfMonth).isEqualTo(18)
        assertThat(kstZdt.hour).isEqualTo(9)
        assertThat(kstZdt.minute).isEqualTo(30)
        assertThat(kstZdt.second).isEqualTo(15)
    }

    @Test
    fun `(f) parseExecutedAt — 파싱 실패 시 now() fallback (graceful)`() {
        val before = Instant.now()
        val instant = adapter.parseExecutedAt("INVALID", "BADTIME")
        val after = Instant.now()

        // fallback은 호출 시각 근처 — assertion은 1초 여유
        assertThat(instant).isBetween(before.minusSeconds(1), after.plusSeconds(1))
    }

    @Test
    fun `(g) raw verbatim — INQUIRE_DAILY_CCLD_PATH 상수 정합 (KIS-first LOCK)`() {
        // raw URL inquire_daily_ccld.py L18:
        // API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        assertThat(KisOrderInquireCcldHttpAdapter.INQUIRE_DAILY_CCLD_PATH)
            .isEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
    }

    @Test
    fun `(h) toExecution — ordTmd 5자리 시 6자리로 zero-pad`() {
        val raw =
            KisInquireDailyCcldOutput1(
                ordDt = "20260518",
                ordTmd = "93015", // 6자리 미만
                odno = "ODNO123",
                pdno = "005930",
                totCcldQty = "10",
                avgPrvs = "78000",
            )

        val exec = adapter.toExecution(raw)

        assertThat(exec).isNotNull
        assertThat(exec!!.ordTmd).isEqualTo("093015") // padStart 6
    }
}
