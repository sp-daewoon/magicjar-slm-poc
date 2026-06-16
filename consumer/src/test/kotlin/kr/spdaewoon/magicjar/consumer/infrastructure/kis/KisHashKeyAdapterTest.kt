package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * KisHashKeyAdapter 단위 테스트 (ADR 0084 P0-1).
 *
 * 5 케이스 매트릭스:
 *  (a) 정상 — body·token·appkey·appsecret 입력 → response.HASH 반환
 *  (b) response null → IllegalStateException("HASHKEY_RESPONSE_NULL")
 *  (c) HASH 필드 null/blank → IllegalStateException("HASHKEY_HASH_FIELD_NULL")
 *  (d) 4xx (401 인증만료 등) → IllegalStateException("HASHKEY_HTTP_401") (caller가 token refresh 후 1회 재시도 책임)
 *  (e) 5xx → WebClientResponseException re-throw (caller submitWithRetry 1회 재시도 정합)
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class KisHashKeyAdapterTest {
    private val sampleBody =
        mapOf(
            "CANO" to "50000000",
            "ACNT_PRDT_CD" to "01",
            "PDNO" to "005930",
            "ORD_DVSN" to "01",
            "ORD_QTY" to "10",
            "ORD_UNPR" to "0",
            "EXCG_ID_DVSN_CD" to "KRX",
            "CNDT_PRIC" to "0",
        )

    /**
     * WebClient chain mock 빌더:
     *   webClient.post().uri(...).contentType(...).header(...).bodyValue(...).retrieve()
     *     .bodyToMono(KisHashKeyResponse::class.java).timeout(...).block()
     */
    private fun buildAdapter(blockBehavior: () -> KisHashKeyResponse?): KisHashKeyAdapter {
        val blockMono = mockk<Mono<KisHashKeyResponse>>()
        every { blockMono.block() } answers { blockBehavior() }

        val timeoutMono = mockk<Mono<KisHashKeyResponse>>()
        every { timeoutMono.timeout(any<Duration>()) } returns blockMono

        val retrieveSpec = mockk<WebClient.ResponseSpec>()
        every { retrieveSpec.bodyToMono(KisHashKeyResponse::class.java) } returns timeoutMono

        val requestBodySpec = mockk<WebClient.RequestBodySpec>()
        every { requestBodySpec.retrieve() } returns retrieveSpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec
        every { requestBodySpec.header(any(), *anyVararg()) } returns requestBodySpec

        val requestBodyUriSpec = mockk<WebClient.RequestBodyUriSpec>()
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec

        val webClient = mockk<WebClient>()
        every { webClient.post() } returns requestBodyUriSpec

        return KisHashKeyAdapter(
            webClient = webClient,
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )
    }

    @Test
    fun `(a) 정상 응답 → HASH 필드 반환`() {
        val adapter = buildAdapter { KisHashKeyResponse(hash = "abcd1234efgh5678") }

        val result =
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "test-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )

        assertThat(result).isEqualTo("abcd1234efgh5678")
    }

    @Test
    fun `(b) response null → IllegalStateException HASHKEY_RESPONSE_NULL`() {
        val adapter = buildAdapter { null }

        assertThatThrownBy {
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "test-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HASHKEY_RESPONSE_NULL")
    }

    @Test
    fun `(c) HASH 필드 null → IllegalStateException HASHKEY_HASH_FIELD_NULL`() {
        val adapter = buildAdapter { KisHashKeyResponse(hash = null) }

        assertThatThrownBy {
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "test-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HASHKEY_HASH_FIELD_NULL")
    }

    @Test
    fun `(c2) HASH 필드 blank → IllegalStateException HASHKEY_HASH_FIELD_NULL`() {
        val adapter = buildAdapter { KisHashKeyResponse(hash = "   ") }

        assertThatThrownBy {
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "test-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HASHKEY_HASH_FIELD_NULL")
    }

    @Test
    fun `(d) 4xx 401 → IllegalStateException HASHKEY_HTTP_401`() {
        val exception =
            WebClientResponseException(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                null,
                "{\"error\":\"token_expired\"}".toByteArray(),
                null,
            )
        val adapter = buildAdapter { throw exception }

        assertThatThrownBy {
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "expired-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HASHKEY_HTTP_401")
    }

    @Test
    fun `(e) 5xx → WebClientResponseException re-throw (caller 재시도 책임)`() {
        val exception =
            WebClientResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                null,
                "{\"error\":\"server\"}".toByteArray(),
                null,
            )
        val adapter = buildAdapter { throw exception }

        assertThatThrownBy {
            adapter.issueHashKey(
                body = sampleBody,
                accessToken = "test-token",
                appKey = "test-appkey",
                appSecret = "test-secret",
                accountType = AccountType.MOCK,
            )
        }.isInstanceOf(WebClientResponseException::class.java)
    }

    @Test
    fun `(f) HASHKEY_PATH 상수 — POST uapi hashkey 시그니처 LOCK`() {
        // raw kis_auth.py L?: url = f"{getTREnv().my_url}/uapi/hashkey"
        // 2026-05-18 WebFetch verbatim LOCK
        assertThat(KisHashKeyAdapter.HASHKEY_PATH).isEqualTo("/uapi/hashkey")
    }
}
