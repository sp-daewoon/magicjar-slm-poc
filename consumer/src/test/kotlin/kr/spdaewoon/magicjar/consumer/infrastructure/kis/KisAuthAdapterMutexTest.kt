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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * KisAuthAdapter in-process mutex 단위 테스트.
 *
 * EGW00133 (KIS tokenP 1분 1회 한도) 방어:
 *   cold-start 시 동시 thread N개가 Redis cache miss 후 KIS tokenP 중복 호출하는 문제를
 *   in-process ReentrantLock + double-check locking 으로 차단.
 *
 * 3 케이스:
 *   (a) 동시 thread 16개 진입 시 KIS tokenP 호출 1회만 검증
 *   (b) Redis cache hit 시 tokenP 호출 0건 검증
 *   (c) Redis cache miss 후 단일 thread: tokenP 호출 1건 + Redis cache 저장 검증
 *
 * AI 호출 0 — hotfix 정책 정합.
 */
class KisAuthAdapterMutexTest {
    // ---- stub helpers ----

    private fun stubCred(
        userId: String,
        accountType: AccountType,
    ): ConsumerUserKisCredentialJpaEntity =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = userId, accountType = accountType.name),
            appKeyEnc = "appkey".toByteArray(),
            secretKeyEnc = "secret".toByteArray(),
            accountNumber = "1234567890",
        )

    /**
     * KisAuthAdapter 생성 — WebClient를 가로채서 tokenP 호출 카운트·응답을 제어.
     *
     * @param redisTemplate 동작 제어할 mock
     * @param tokenPCallCount KIS tokenP 호출 횟수를 누적하는 카운터
     * @param tokenResponse 반환할 access_token 값
     */
    private fun buildAdapter(
        redisTemplate: StringRedisTemplate,
        tokenPCallCount: AtomicInteger,
        tokenResponse: String = "test-token",
    ): KisAuthAdapter {
        val secretCipher =
            mockk<SecretCipher>().also { sc ->
                every { sc.decrypt(any()) } answers { firstArg<ByteArray>() }
            }

        val credRepo =
            mockk<ConsumerUserKisCredentialJpaRepository>().also { repo ->
                every { repo.findAllByAccountType(AccountType.REAL.name) } returns
                    listOf(stubCred("user1", AccountType.REAL))
            }

        // WebClient chain mock: post().uri(...).contentType(...).bodyValue(...).retrieve().bodyToMono(...).timeout(...).block()
        val blockMono = mockk<Mono<Map<*, *>>>()
        every { blockMono.block() } answers {
            tokenPCallCount.incrementAndGet()
            mapOf("access_token" to tokenResponse)
        }

        val timeoutMono = mockk<reactor.core.publisher.Mono<Map<*, *>>>()
        every { timeoutMono.timeout(any<Duration>()) } returns blockMono

        val retrieveSpec = mockk<WebClient.ResponseSpec>()
        every { retrieveSpec.bodyToMono(Map::class.java) } returns timeoutMono

        val requestBodySpec = mockk<WebClient.RequestBodySpec>()
        every { requestBodySpec.retrieve() } returns retrieveSpec

        val requestBodyUriSpec = mockk<WebClient.RequestBodyUriSpec>()
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec

        val webClient = mockk<WebClient>()
        every { webClient.post() } returns requestBodyUriSpec

        return KisAuthAdapter(
            webClient = webClient,
            redisTemplate = redisTemplate,
            secretCipher = secretCipher,
            credentialRepository = credRepo,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )
    }

    /**
     * 공통 Redis mock 빌더 — cache miss 후 set 시 다음 get에서 hit 반환 (단순 Map 시뮬레이션).
     */
    private fun buildRedisTemplate(initialCacheHit: String? = null): StringRedisTemplate {
        val valueOps = mockk<ValueOperations<String, String>>()
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.opsForValue() } returns valueOps

        // SETNX (분산락) 항상 성공 반환
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true
        // lock delete
        every { redisTemplate.delete(any<String>()) } returns true

        if (initialCacheHit != null) {
            // 캐시 hit — 항상 토큰 반환
            every { valueOps.get(any()) } returns initialCacheHit
        } else {
            // 캐시 miss 후 set 시뮬레이션:
            //   첫 번째 get(cacheKey) → null (fast path miss)
            //   두 번째 get(cacheKey) — lock 내 double-check → null (발급 진행)
            //   세 번째 이후 get(cacheKey) — triple-check, 이미 발급 완료 → "test-token"
            //   set() 호출 시 내부 map에 저장
            val cacheStore = mutableMapOf<String, String>()
            val callCounts = mutableMapOf<String, Int>()

            every { valueOps.get(any()) } answers {
                val key = firstArg<String>()
                val count = (callCounts[key] ?: 0) + 1
                callCounts[key] = count
                // 1·2번째 호출: null (miss), 이후: store에서 반환
                if (count <= 2) null else cacheStore[key]
            }
            every { valueOps.set(any(), any(), any<Duration>()) } answers {
                val key = firstArg<String>()
                val value = secondArg<String>()
                cacheStore[key] = value
            }
        }

        return redisTemplate
    }

    /**
     * (a) 동시 thread 16개 진입 시 KIS tokenP 호출 1회만.
     *
     * 검증:
     * - 16개 thread 모두 동일 userId:accountType으로 동시에 getAccessToken 호출
     * - in-process ReentrantLock이 JVM 내 직렬화 보장
     * - 첫 번째 thread만 KIS tokenP 호출 (tokenPCallCount == 1)
     * - 나머지 15개는 Redis double-check hit으로 반환
     * - 모든 thread 반환값 == "test-token"
     */
    @Test
    fun `(a) 동시 thread 16개 진입 시 KIS tokenP 호출 1회만`() {
        val tokenPCallCount = AtomicInteger(0)

        // Redis: 첫 발급 후 cache에 저장, lock 내 double-check부터 hit 반환
        val cacheStore = mutableMapOf<String, String>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true
        every { redisTemplate.delete(any<String>()) } returns true

        // get: cache 비어있으면 null, set 후에는 값 반환
        every { valueOps.get(any()) } answers {
            val key = firstArg<String>()
            synchronized(cacheStore) { cacheStore[key] }
        }
        every { valueOps.set(any(), any(), any<Duration>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            synchronized(cacheStore) { cacheStore[key] = value }
        }

        val adapter = buildAdapter(redisTemplate, tokenPCallCount, "test-token")

        val threadCount = 16
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = mutableListOf<String>()
        val resultLock = Any()

        val executor = Executors.newFixedThreadPool(threadCount)
        repeat(threadCount) {
            executor.submit {
                latch.await() // 모든 thread 동시 출발
                val token = adapter.getAccessToken("user1", AccountType.REAL)
                synchronized(resultLock) { results.add(token) }
                doneLatch.countDown()
            }
        }

        latch.countDown() // 동시 출발
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(finished).isTrue()
        assertThat(results).hasSize(threadCount)
        assertThat(results).allSatisfy { assertThat(it).isEqualTo("test-token") }
        // 핵심 검증: tokenP 호출 1회만
        assertThat(tokenPCallCount.get())
            .`as`("KIS tokenP 호출은 1회만 허용 (EGW00133 방어)")
            .isEqualTo(1)
    }

    /**
     * (b) Redis cache hit 시 tokenP 호출 0건.
     *
     * 검증:
     * - Redis cache에 이미 토큰이 존재하면 fast path에서 즉시 반환
     * - KIS tokenP 호출 0건
     */
    @Test
    fun `(b) Redis cache hit 시 tokenP 호출 0건`() {
        val tokenPCallCount = AtomicInteger(0)
        val redisTemplate = buildRedisTemplate(initialCacheHit = "cached-token")

        val adapter = buildAdapter(redisTemplate, tokenPCallCount, "new-token")

        val result = adapter.getAccessToken("user1", AccountType.REAL)

        assertThat(result).isEqualTo("cached-token")
        assertThat(tokenPCallCount.get())
            .`as`("Redis cache hit 시 KIS tokenP 호출 0건")
            .isEqualTo(0)
    }

    /**
     * (c) Redis cache miss 후 단일 thread: tokenP 호출 1건 + Redis cache 저장.
     *
     * 검증:
     * - Redis cache miss 시 KIS tokenP 정확히 1회 호출
     * - 결과 토큰 Redis에 TTL 23h로 저장 (ValueOperations.set 호출 검증)
     */
    @Test
    fun `(c) Redis cache miss 시 tokenP 호출 1건 + Redis cache 저장`() {
        val tokenPCallCount = AtomicInteger(0)

        val cacheStore = mutableMapOf<String, String>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true
        every { redisTemplate.delete(any<String>()) } returns true

        val setCalls = mutableListOf<Pair<String, String>>()
        every { valueOps.get(any()) } returns null // 항상 miss
        every { valueOps.set(any(), any(), any<Duration>()) } answers {
            setCalls.add(firstArg<String>() to secondArg<String>())
        }

        val adapter = buildAdapter(redisTemplate, tokenPCallCount, "issued-token")

        val result = adapter.getAccessToken("user1", AccountType.REAL)

        assertThat(result).isEqualTo("issued-token")
        assertThat(tokenPCallCount.get())
            .`as`("단일 thread cache miss 시 tokenP 1회 호출")
            .isEqualTo(1)
        // Redis set 호출 검증 — cacheKey에 토큰 저장
        val cacheKey = "kis:oauth:token:user:user1:account:${AccountType.REAL.name}"
        assertThat(setCalls.any { (k, v) -> k == cacheKey && v == "issued-token" })
            .`as`("발급 토큰이 Redis cache key=$cacheKey 에 저장되어야 함")
            .isTrue()
    }
}
