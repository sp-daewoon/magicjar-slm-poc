package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * [KisRestAskingPriceAdapter] 단위 테스트 — ADR 0114 §10-3 R2-C1 FHKST01010200 응답 파싱.
 *
 * HTTP 호출 자체는 mock 없이(WebClient mock 복잡도 회피, 기존 KisOrderInquireCcldHttpAdapterTest 선례) —
 * **KIS 응답 output1 fixture → EntryQuote 매핑** helper(parseOutput1) 중심 검증.
 *
 *  (a) 정상 output1(askp1·bidp1·askp_rsqn1·aspr_acpt_hour) → EntryQuote
 *  (b) askp1 blank → null (호가 미가용)
 *  (c) askp1 0 → null
 *  (d) askp_rsqn1 누락 → askQty1 null 허용
 *  (e) aspr_acpt_hour HHMMSS → KST 당일 시각 합성 (초 단위)
 *
 * KIS-first: output1 필드명(askp1·bidp1·askp_rsqn1·aspr_acpt_hour)은 FHKST01010200 공식 규약.
 * AI 호출 0.
 */
class KisRestAskingPriceAdapterTest {
    private val secretCipher = mockk<SecretCipher>(relaxed = true)

    private val adapter =
        KisRestAskingPriceAdapter(
            webClient = mockk(relaxed = true),
            kisAuthAdapter = mockk(relaxed = true),
            kisRateLimiterPort = mockk(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            defaultUserId = "tester",
            credentialRepositoryProvider = mockk(relaxed = true),
            secretCipher = secretCipher,
        )

    @Test
    fun `정상 output1이면 EntryQuote로 매핑한다`() {
        val output1 =
            mapOf<String, Any>(
                "askp1" to "71000",
                "bidp1" to "70900",
                "askp_rsqn1" to "250",
                "aspr_acpt_hour" to "093015",
            )

        val q = adapter.parseOutput1("005930", output1)

        assertThat(q).isNotNull
        assertThat(q!!.ask1).isEqualByComparingTo("71000")
        assertThat(q.bid1).isEqualByComparingTo("70900")
        assertThat(q.askQty1).isEqualTo(250L)
    }

    @Test
    fun `askp1이 blank면 null을 반환한다`() {
        val output1 = mapOf<String, Any>("askp1" to "", "bidp1" to "70900")
        assertThat(adapter.parseOutput1("005930", output1)).isNull()
    }

    @Test
    fun `askp1이 0이면 null을 반환한다`() {
        val output1 = mapOf<String, Any>("askp1" to "0", "bidp1" to "70900")
        assertThat(adapter.parseOutput1("005930", output1)).isNull()
    }

    @Test
    fun `askp_rsqn1이 없어도 매핑된다 (잔량 optional)`() {
        val output1 = mapOf<String, Any>("askp1" to "71000", "bidp1" to "70900")

        val q = adapter.parseOutput1("005930", output1)

        assertThat(q).isNotNull
        assertThat(q!!.askQty1).isNull()
    }

    // ── F11: REST fallback REAL 자격증명 resolve (hotfix c95b5391 패턴) ──────────

    @Test
    fun `REAL 자격증명 보유 user가 있으면 그 userId로 resolve한다 (ws_user-id 무시)`() {
        val credId = ConsumerUserKisCredentialId(userId = "74683223", accountType = "REAL")
        val entity = mockk<ConsumerUserKisCredentialJpaEntity> { every { id } returns credId }
        val repo =
            mockk<ConsumerUserKisCredentialJpaRepository> {
                every { findAllByAccountType("REAL") } returns listOf(entity)
            }
        val provider = mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>> { every { ifAvailable } returns repo }
        val adapterWithRepo =
            KisRestAskingPriceAdapter(
                webClient = mockk(relaxed = true),
                kisAuthAdapter = mockk(relaxed = true),
                kisRateLimiterPort = mockk(relaxed = true),
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                defaultUserId = "thdeodns", // WS approval 식별자 — REST 자격증명 없음(c95b5391 incident 재현)
                credentialRepositoryProvider = provider,
                secretCipher = secretCipher,
            )

        assertThat(invokeResolveRestUserId(adapterWithRepo)).isEqualTo("74683223")
    }

    @Test
    fun `REAL 자격증명이 없으면 ws_user-id로 fallback한다`() {
        val repo =
            mockk<ConsumerUserKisCredentialJpaRepository> {
                every { findAllByAccountType("REAL") } returns emptyList()
            }
        val provider = mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>> { every { ifAvailable } returns repo }
        val adapterWithRepo =
            KisRestAskingPriceAdapter(
                webClient = mockk(relaxed = true),
                kisAuthAdapter = mockk(relaxed = true),
                kisRateLimiterPort = mockk(relaxed = true),
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                defaultUserId = "thdeodns",
                credentialRepositoryProvider = provider,
                secretCipher = secretCipher,
            )

        assertThat(invokeResolveRestUserId(adapterWithRepo)).isEqualTo("thdeodns")
    }

    @Test
    fun `repository 부재(fixture) 시 ws_user-id로 fallback한다`() {
        val provider = mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>> { every { ifAvailable } returns null }
        val adapterNoRepo =
            KisRestAskingPriceAdapter(
                webClient = mockk(relaxed = true),
                kisAuthAdapter = mockk(relaxed = true),
                kisRateLimiterPort = mockk(relaxed = true),
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                defaultUserId = "wsuser",
                credentialRepositoryProvider = provider,
                secretCipher = secretCipher,
            )

        assertThat(invokeResolveRestUserId(adapterNoRepo)).isEqualTo("wsuser")
    }

    private fun invokeResolveRestUserId(target: KisRestAskingPriceAdapter): String {
        val m = KisRestAskingPriceAdapter::class.java.getDeclaredMethod("resolveRestUserId")
        m.isAccessible = true
        return m.invoke(target) as String
    }

    @Test
    fun `fetchTopQuote는 KIS 필수 appkey appsecret 헤더를 전송한다`() {
        val capturedHeaders = mutableMapOf<String, List<String>>()
        val webClient =
            WebClient
                .builder()
                .exchangeFunction { request ->
                    capturedHeaders.putAll(request.headers().mapValues { it.value })
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(
                                """
                                {
                                  "rt_cd": "0",
                                  "output1": {
                                    "askp1": "20050",
                                    "bidp1": "20000",
                                    "askp_rsqn1": "10",
                                    "aspr_acpt_hour": "111435"
                                  }
                                }
                                """.trimIndent(),
                            ).build(),
                    )
                }.build()
        val appKeyEnc = "enc-app".toByteArray()
        val secretKeyEnc = "enc-secret".toByteArray()
        val cred =
            ConsumerUserKisCredentialJpaEntity(
                id = ConsumerUserKisCredentialId(userId = "74683223", accountType = "REAL"),
                accountNumber = "12345678",
                appKeyEnc = appKeyEnc,
                secretKeyEnc = secretKeyEnc,
            )
        val repo =
            mockk<ConsumerUserKisCredentialJpaRepository> {
                every { findAllByAccountType("REAL") } returns listOf(cred)
            }
        val provider = mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>> { every { ifAvailable } returns repo }
        val kisAuth =
            mockk<KisAuthAdapter> {
                every { getAccessToken("74683223", AccountType.REAL) } returns "token-plain"
            }
        val cipher =
            mockk<SecretCipher> {
                every { decrypt(appKeyEnc) } returns "appkey-plain".toByteArray()
                every { decrypt(secretKeyEnc) } returns "secret-plain".toByteArray()
            }
        val adapterWithHttp =
            KisRestAskingPriceAdapter(
                webClient = webClient,
                kisAuthAdapter = kisAuth,
                kisRateLimiterPort = mockk(relaxed = true),
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                defaultUserId = "thdeodns",
                credentialRepositoryProvider = provider,
                secretCipher = cipher,
            )

        val quote = adapterWithHttp.fetchTopQuote("093370")

        assertThat(quote).isNotNull
        assertThat(capturedHeaders["authorization"]).containsExactly("Bearer token-plain")
        assertThat(capturedHeaders["appkey"]).containsExactly("appkey-plain")
        assertThat(capturedHeaders["appsecret"]).containsExactly("secret-plain")
        assertThat(capturedHeaders["tr_id"]).containsExactly("FHKST01010200")
    }

    @Test
    fun `aspr_acpt_hour HHMMSS가 KST 당일 시각으로 합성된다`() {
        val output1 =
            mapOf<String, Any>(
                "askp1" to "71000",
                "bidp1" to "70900",
                "aspr_acpt_hour" to "093015",
            )

        val q = adapter.parseOutput1("005930", output1)

        // KST 09:30:15 → UTC 00:30:15. 초 단위까지 합성되었는지(now fallback 아님) 검증.
        assertThat(q).isNotNull
        assertThat(q!!.asOf.toString()).endsWith(":30:15Z")
    }
}
