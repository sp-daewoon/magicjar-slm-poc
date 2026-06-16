package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * KisApprovalAdapter rate limiter 합류 회귀 (ADR 0116 — 2026-06-05 orphan 정식화).
 *
 * 검증: cache miss → 분산락 획득 → KIS /oauth2/Approval 호출 직전 [KisRateLimiterPort.acquire] (INGESTION) 1회.
 *
 * 결함 배경: WS 재연결 루프가 approval REST 를 limiter 우회 발사 → TPS bucket 미집계 → 주문 path 와 KIS 한도 충돌.
 *
 * 자본 영향 0 — mock 단위 (KIS endpoint 호출 0, Redis 호출 0).
 */
class KisApprovalAdapterRateLimitTest {
    private val userId = "user_appr_001"
    private val accountType = AccountType.REAL

    @Test
    fun `cache miss 발급 path — KIS 호출 직전 limiter acquire (INGESTION) 1회`() {
        val rateLimiter = mockk<KisRateLimiterPort>(relaxed = true)

        // Redis: cache miss + 분산락 SETNX 성공 + DEL.
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { valueOps.get(any()) } returns null
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        every { redis.opsForValue() } returns valueOps
        every { redis.delete(any<String>()) } returns true

        // 자격증명 (appKey/secretKey 복호화 stub — appKeyEnc/secretKeyEnc 는 ByteArray).
        val appKeyEnc = "enc-app".toByteArray()
        val secretKeyEnc = "enc-secret".toByteArray()
        val cred =
            ConsumerUserKisCredentialJpaEntity(
                id = ConsumerUserKisCredentialId(userId = userId, accountType = accountType.name),
                accountNumber = "50000000",
                accountPrdtCd = "01",
                appKeyEnc = appKeyEnc,
                secretKeyEnc = secretKeyEnc,
            )
        val credRepo = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { credRepo.findAllByAccountType(accountType.name) } returns listOf(cred)

        val secretCipher = mockk<SecretCipher>()
        every { secretCipher.decrypt(appKeyEnc) } returns "appkey-plain".toByteArray()
        every { secretCipher.decrypt(secretKeyEnc) } returns "secret-plain".toByteArray()

        // WebClient chain — /oauth2/Approval 응답 {"approval_key":"appr-xyz"}.
        val webClient = buildApprovalWebClient(mapOf("approval_key" to "appr-xyz"))

        val adapter =
            KisApprovalAdapter(
                webClient = webClient,
                redisTemplate = redis,
                secretCipher = secretCipher,
                credentialRepository = credRepo,
                kisRateLimiterPort = rateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        adapter.getApprovalKey(userId, accountType)

        // KIS 호출 직전 limiter acquire (INGESTION) 정확히 1회 + 정상 시그널.
        verify(exactly = 1) { rateLimiter.acquire(KisCallPriority.INGESTION, any(), any()) }
        verify(exactly = 1) { rateLimiter.onSuccess() }
    }

    @Test
    fun `cache hit — limiter acquire 0 (KIS 미호출 path 토큰 미소비)`() {
        val rateLimiter = mockk<KisRateLimiterPort>(relaxed = true)
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { valueOps.get(any()) } returns "cached-appr-key"
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        every { redis.opsForValue() } returns valueOps

        val adapter =
            KisApprovalAdapter(
                webClient = mockk(),
                redisTemplate = redis,
                secretCipher = mockk(),
                credentialRepository = mockk(),
                kisRateLimiterPort = rateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        adapter.getApprovalKey(userId, accountType)

        verify(exactly = 0) { rateLimiter.acquire(any(), any(), any()) }
    }

    /**
     * WebClient chain mock:
     *   webClient.post().uri(...).contentType(...).bodyValue(...).retrieve()
     *     .bodyToMono(Map::class.java).timeout(...).block()
     */
    private fun buildApprovalWebClient(response: Map<String, Any>): WebClient {
        val blockMono = mockk<Mono<*>>()
        every { blockMono.block() } returns response

        val timeoutMono = mockk<Mono<*>>()
        every { timeoutMono.timeout(any<Duration>()) } returns blockMono

        val retrieveSpec = mockk<WebClient.ResponseSpec>()
        every { retrieveSpec.bodyToMono(Map::class.java) } returns
            @Suppress("UNCHECKED_CAST")
            (timeoutMono as Mono<Map<*, *>>)

        val requestBodySpec = mockk<WebClient.RequestBodySpec>()
        every { requestBodySpec.retrieve() } returns retrieveSpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec

        val requestBodyUriSpec = mockk<WebClient.RequestBodyUriSpec>()
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec

        val webClient = mockk<WebClient>()
        every { webClient.post() } returns requestBodyUriSpec
        return webClient
    }
}
