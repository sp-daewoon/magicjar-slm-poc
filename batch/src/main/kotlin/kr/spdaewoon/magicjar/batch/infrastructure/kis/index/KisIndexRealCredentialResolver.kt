package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import kr.spdaewoon.magicjar.batch.persistence.BatchUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/**
 * 시장지수 배치용 REAL KIS credential resolver.
 *
 * 기존 시장지수 배치는 `magicjar.kis.index.access-token` env 가 없으면 조용히 skip 했다.
 * 운영 정책은 "시세·캔들은 REAL KIS" 이므로, 별도 시스템 토큰 env 대신 DB에 등록된 REAL credential 을
 * 읽고 API/consumer 와 같은 Redis token prefix(`kis:token:{userId}:REAL`)를 공유한다.
 */
@Component
class KisIndexRealCredentialResolver(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val credentialRepository: BatchUserKisCredentialJpaRepository,
    @Value("\${magicjar.secret.master-key:}")
    private val masterKeyBase64: String,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) {
    private val log = LoggerFactory.getLogger(KisIndexRealCredentialResolver::class.java)
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun resolve(): ResolvedKisIndexCredentials? {
        val credential =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.debug("[KisIndexCredential] REAL credential 없음 — market index fetch skip")
                    return null
                }

        var appKeyBytes: ByteArray? = null
        var secretKeyBytes: ByteArray? = null
        return try {
            appKeyBytes = decrypt(credential.appKeyEnc)
            secretKeyBytes = decrypt(credential.secretKeyEnc)
            val appKey = String(appKeyBytes)
            val appSecret = String(secretKeyBytes)
            val accessToken = getOrIssueToken(credential.id.userId, appKey, appSecret)
            ResolvedKisIndexCredentials(
                appKey = appKey,
                appSecret = appSecret,
                accessToken = accessToken,
                baseUrl = kisBaseProd,
                source = "db-real",
            )
        } catch (e: Exception) {
            log.warn("[KisIndexCredential] REAL credential resolve 실패 — market index fetch skip err={}", e.message)
            null
        } finally {
            appKeyBytes?.fill(0)
            secretKeyBytes?.fill(0)
        }
    }

    private fun getOrIssueToken(
        userId: String,
        appKey: String,
        appSecret: String,
    ): String {
        val cacheKey = tokenKey(userId)
        val lockKey = tokenLockKey(userId)

        readCache(cacheKey)?.let { return it }

        val lock = inProcessLocks.computeIfAbsent(userId) { ReentrantLock() }
        return lock.withLock {
            readCache(cacheKey)?.let { return@withLock it }

            val acquired =
                redisTemplate
                    .opsForValue()
                    .setIfAbsent(lockKey, "1", LOCK_TTL) == true

            if (acquired) {
                try {
                    readCache(cacheKey)?.let { return@withLock it }
                    val issued = issueToken(appKey, appSecret)
                    redisTemplate.opsForValue().set(cacheKey, issued.token, issued.ttl)
                    log.info("[KisIndexCredential] KIS REAL 토큰 발급 완료 user={}", maskUserId(userId))
                    issued.token
                } finally {
                    redisTemplate.delete(lockKey)
                }
            } else {
                waitForToken(cacheKey) ?: run {
                    log.warn("[KisIndexCredential] 토큰 분산락 대기 timeout — 단일 JVM fallback 발급")
                    val issued = issueToken(appKey, appSecret)
                    redisTemplate.opsForValue().set(cacheKey, issued.token, issued.ttl)
                    issued.token
                }
            }
        }
    }

    private fun issueToken(
        appKey: String,
        appSecret: String,
    ): IssuedToken {
        try {
            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .post()
                    .uri("$kisBaseProd/oauth2/tokenP")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKey,
                            "appsecret" to appSecret,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(TOKEN_ISSUE_TIMEOUT)
                    .block() as? Map<String, Any>
                    ?: error("KIS tokenP 응답 없음")

            val token =
                response["access_token"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: error("KIS tokenP 응답에 access_token 없음")
            val ttl = response["expires_in"].toTokenTtl()
            return IssuedToken(token, ttl)
        } catch (e: WebClientResponseException) {
            log.warn(
                "[KisIndexCredential] KIS tokenP 실패 status={} body={}",
                e.statusCode.value(),
                e.responseBodyAsString.take(200),
            )
            throw e
        }
    }

    private fun waitForToken(cacheKey: String): String? {
        repeat(LOCK_MAX_RETRIES) {
            Thread.sleep(LOCK_WAIT_MS)
            readCache(cacheKey)?.let { return it }
        }
        return null
    }

    private fun readCache(cacheKey: String): String? =
        redisTemplate
            .opsForValue()
            .get(cacheKey)
            ?.takeIf { it.isNotBlank() }

    private fun decrypt(cipherText: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(masterKey(), "AES")
        val iv = cipherText.copyOfRange(0, IV_SIZE_BYTES)
        val payload = cipherText.copyOfRange(IV_SIZE_BYTES, cipherText.size)
        return Cipher
            .getInstance(AES_GCM)
            .apply { init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, iv)) }
            .doFinal(payload)
    }

    private fun masterKey(): ByteArray {
        if (masterKeyBase64.isBlank()) {
            error("MAGICJAR_SECRET_MASTER_KEY 부재 — DB REAL KIS credential 복호화 불가")
        }
        val decoded =
            runCatching { Base64.getDecoder().decode(masterKeyBase64) }
                .getOrElse { error("MAGICJAR_SECRET_MASTER_KEY base64 디코딩 실패") }
        require(decoded.size == REQUIRED_KEY_SIZE_BYTES) {
            "MAGICJAR_SECRET_MASTER_KEY 는 32 bytes 필요 — 현재 ${decoded.size} bytes"
        }
        return decoded
    }

    private fun Any?.toTokenTtl(): Duration =
        this
            ?.toString()
            ?.toLongOrNull()
            ?.let { seconds -> (seconds * TTL_SAFETY_RATIO).toLong().coerceAtLeast(MIN_TOKEN_TTL_SECONDS) }
            ?.let(Duration::ofSeconds)
            ?: FALLBACK_TTL

    private fun tokenKey(userId: String): String = RedisKeyRegistry.Kis.token(userId, AccountType.REAL)

    private fun tokenLockKey(userId: String): String = RedisKeyRegistry.Kis.tokenLock(userId, AccountType.REAL)

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private data class IssuedToken(
        val token: String,
        val ttl: Duration,
    )

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val REQUIRED_KEY_SIZE_BYTES = 32
        private const val MIN_TOKEN_TTL_SECONDS = 60L
        private const val TTL_SAFETY_RATIO = 0.95
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60
        private val TOKEN_ISSUE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val LOCK_TTL: Duration = Duration.ofSeconds(30)
        private val FALLBACK_TTL: Duration = Duration.ofHours(23)
    }
}
