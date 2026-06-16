package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KIS OAuth 운영용 토큰 어댑터 (ADR 0038 — Redis cache + 분산락 + in-process mutex).
 * - Redis cache `kis:oauth:token:user:{userId}:account:{accountType}` TTL 23h
 * - in-process ReentrantLock (per userId:accountType) — JVM 내 동시 thread 직렬화
 *   cold-start 시 2+ thread가 동시에 Redis miss 판정 후 KIS tokenP 동시 호출하는 문제 차단
 *   (EGW00133: KIS tokenP 1분 1회 제한 위반 방지)
 * - Redis SETNX 분산락 `kis:oauth:token:user:{userId}:account:{accountType}:lock` TTL 30s — 다중 JVM 확장 대비
 * - 분산락 미획득 시 30s spinWait 후 cache 재조회 (Bootstrap fallback)
 * - PR-6 KisAuthValidator(단발 검증용)와 별개 — 운영용 신규 클래스
 *
 * 복호화 메모리 zero-fill 의무: APP_KEY·SECRET_KEY 사용 후 즉시 fill.
 */
@Component
class KisAuthAdapter(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisAuthAdapter::class.java)

    companion object {
        private const val TOKEN_TTL_HOURS = 23L
        private const val LOCK_TTL_SECONDS = 30L
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60 // 30s / 0.5s
    }

    /**
     * in-process 락 맵 — userId:accountType 쌍별 ReentrantLock.
     * ConcurrentHashMap.computeIfAbsent 로 lazy 생성 + thread-safe.
     * JVM 내 동시 thread 수에 비례하여 최대 N개(userId × accountType 조합) 생성.
     * 단일 JVM 단일 consumer 환경(magicJar baseline)에서 완전 충분.
     * 다중 JVM 확장 시 Redis SETNX 분산락이 2차 방어선.
     */
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * KIS 액세스 토큰 반환 (in-process mutex + ADR 0038 Redis 분산락 + cache).
     *
     * 직렬화 흐름 (double-check locking 2단):
     * 1) Redis cache hit → 즉시 반환 (락 없이)
     * 2) cache miss → in-process ReentrantLock 획득 (JVM 내 동시 thread 직렬화)
     *    2a) lock 내부 Redis double-check → hit 시 즉시 반환
     *    2b) Redis SETNX 분산락 획득
     *        - 획득 성공: KIS /oauth2/tokenP → cache 저장 → unlock
     *        - 획득 실패: spinWait 후 cache 재조회
     *
     * cold-start 16 thread 동시 진입 시:
     *   - 1개 thread만 KIS tokenP 호출 (in-process lock + double-check 보장)
     *   - 나머지 15개는 lock 대기 후 Redis cache hit → 즉시 반환
     *   → EGW00133 (1분 1회 제한) 완전 차단
     */
    fun getAccessToken(
        userId: String,
        accountType: AccountType,
    ): String {
        require(userId.isNotBlank()) {
            "KIS getAccessToken userId 빈 문자열 차단 — caller 결함 " +
                "(cache key '${RedisKeyRegistry.Kis.token("", accountType)}' pollution + cross-user leak risk · P0 hotfix 2026-05-20)"
        }
        val cacheKey = RedisKeyRegistry.Kis.token(userId, accountType)
        val lockKey = RedisKeyRegistry.Kis.tokenLock(userId, accountType)

        // 1. Redis cache hit — 락 없이 fast path
        redisTemplate.opsForValue().get(cacheKey)?.let { return it }

        // 2. in-process ReentrantLock 획득 — JVM 내 동시 thread 직렬화
        val inProcessLock = inProcessLocks.computeIfAbsent("$userId:${accountType.name}") { ReentrantLock() }
        return inProcessLock.withLock {
            // 2a. Double-check: lock 대기 중 다른 thread가 이미 발급+캐시?
            redisTemplate.opsForValue().get(cacheKey)?.let { return@withLock it }

            // 2b. Redis SETNX 분산락 획득 시도 (다중 JVM 확장 대비)
            val lockAcquired =
                redisTemplate
                    .opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TTL_SECONDS)) == true

            if (lockAcquired) {
                try {
                    // Triple-check: 다른 JVM이 이미 발급? (in-process lock 이후이므로 실질적 방어)
                    redisTemplate.opsForValue().get(cacheKey)?.let { return@withLock it }

                    val token = issueToken(userId, accountType)
                    redisTemplate
                        .opsForValue()
                        .set(cacheKey, token, Duration.ofHours(TOKEN_TTL_HOURS))
                    log.info(
                        "KIS 토큰 발급 완료: user={} type={}",
                        maskUserId(userId),
                        accountType.name,
                    )
                    token
                } finally {
                    redisTemplate.delete(lockKey)
                }
            } else {
                // 분산락 미획득 — spinWait 후 cache 재조회 (in-process lock 안에서 실행)
                log.debug("KIS 토큰 발급 분산락 대기: user={} type={}", maskUserId(userId), accountType.name)
                for (i in 0 until LOCK_MAX_RETRIES) {
                    Thread.sleep(LOCK_WAIT_MS)
                    redisTemplate.opsForValue().get(cacheKey)?.let { return@withLock it }
                }
                // 30s 대기 후에도 없으면 직접 발급 (단일 JVM fallback — in-process lock 보호 하에 안전)
                log.warn("KIS 토큰 분산락 30s 타임아웃 — 직접 발급 (단일 JVM fallback)")
                issueToken(userId, accountType).also { token ->
                    redisTemplate
                        .opsForValue()
                        .set(cacheKey, token, Duration.ofHours(TOKEN_TTL_HOURS))
                }
            }
        }
    }

    /**
     * KIS /oauth2/tokenP 호출 (ADR 0038 §2-2).
     * 메모리 zero-fill: APP_KEY·SECRET_KEY 사용 후 Arrays.fill.
     */
    private fun issueToken(
        userId: String,
        accountType: AccountType,
    ): String {
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: error("KIS 자격증명 없음 — userId=$userId type=${accountType.name}")

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)

            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

            val response =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/tokenP")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKeyStr,
                            "appsecret" to secretKeyStr,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() ?: error("KIS tokenP 응답 없음")

            response["access_token"]?.toString()
                ?: error("KIS tokenP 응답에 access_token 없음")
        } catch (e: WebClientResponseException) {
            log.error(
                "KIS tokenP 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            // 메모리 zero-fill (ADR 0038 §5-3 의무)
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
