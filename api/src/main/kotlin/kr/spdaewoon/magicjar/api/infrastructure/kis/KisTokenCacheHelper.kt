package kr.spdaewoon.magicjar.api.infrastructure.kis

import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
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
 * api 모듈 KIS OAuth 토큰 cache helper (T-1·2·3·4 통합 — 502 root cause fix).
 *
 * ### 책임 (T-4)
 * api 어댑터 다수가 중복 구현하던 토큰 lookup 로직을 단일 helper로 통합한다.
 * - 기존: ApiKisBalanceQueryAdapter·KisRestPriceMetricsAdapter·CandleRefreshService 각자 발급
 * - 결함: KisRestPriceMetricsAdapter·CandleRefreshService는 `access_token:` prefix (consumer/Balance는 `kis:token:`) → cache miss 폭증 → KIS EGW00133/EGW00201 502
 * - 본 helper: 단일 `kis:oauth:token:user:{userId}:account:{accountType}` prefix LOCK (T-1)
 *
 * ### 분산락 정합 (ADR 0038 — consumer KisAuthAdapter 패턴 적용)
 * - 1) Redis cache hit → 즉시 반환 (lock 없이 fast path)
 * - 2) cache miss → in-process ReentrantLock 획득 (per userId:accountType)
 *   - 2a) lock 안에서 Redis double-check
 *   - 2b) Redis SETNX 분산락 `kis:oauth:token:user:{userId}:account:{accountType}:lock` (TTL 30s)
 *     - 획득 성공 → KIS /oauth2/tokenP 발급 → cache write → unlock
 *     - 획득 실패 → spinWait 후 cache 재조회 (다중 JVM 안전)
 *
 * ### TTL 동적 (T-3)
 * KIS `/oauth2/tokenP` 응답 `expires_in` (초) × 0.95 (5% 안전마진) 으로 cache TTL 설정.
 * - hardcode 23h 제거 — KIS 정책 변경 자동 흡수
 * - `expires_in` 누락 시 fallback 23h
 *
 * ### prefix 통합 효과 (T-1·T-2)
 * - consumer `KisAuthAdapter` + consumer `SystemKisKeyChangedListener` + api `ApiKisBalanceQueryAdapter`
 *   모두 동일 `kis:oauth:token:` prefix 사용 → 단일 진실원
 * - api `KisCacheInvalidator` (T-2)는 표준 token/approval 키만 DEL
 *
 * ### 자격증명 우선순위
 * 사용자 요청 시 REAL 우선 → MOCK fallback. 호출자가 명시한 [AccountType] 우선.
 *
 * ### 메모리 zero-fill (ADR 0038 §5-3)
 * APP_KEY·SECRET_KEY ByteArray 사용 직후 fill(0).
 */
@Component
class KisTokenCacheHelper(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val credentialRepository: UserKisCredentialJpaRepository,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisTokenCacheHelper::class.java)

    companion object {
        /** TTL fallback — KIS expires_in 누락 시 23h (24h - 1h safety margin). */
        private val FALLBACK_TTL: Duration = Duration.ofHours(23)

        /** expires_in 안전마진 — 95%로 설정 (KIS 토큰 만료 직전 미스 회피). */
        private const val TTL_SAFETY_RATIO = 0.95

        /** 분산락 TTL — 30s. KIS tokenP 평균 응답 + 안전여유. */
        private val LOCK_TTL = Duration.ofSeconds(30)

        /** 분산락 미획득 시 spinWait 단위 — 0.5s × 60회 = 30s 대기. */
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60

        private val TOKEN_ISSUE_TIMEOUT = Duration.ofSeconds(10)

        fun tokenKey(
            userId: String,
            accountType: AccountType,
        ): String = RedisKeyRegistry.Kis.token(userId, accountType)

        fun lockKey(
            userId: String,
            accountType: AccountType,
        ): String = RedisKeyRegistry.Kis.tokenLock(userId, accountType)
    }

    /** in-process 락 맵 — userId:accountType 별 ReentrantLock. */
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 단일 토큰만 필요한 호출용 (KisRestPriceMetricsAdapter·CandleRefreshService).
     *
     * 자격증명은 [accountType] 지정 우선, 미지정 시 REAL → MOCK fallback.
     * appKey/secretKey 도 필요하면 [resolveTokenAndKeys] 사용.
     *
     * 빈 userId 차단 (P0 hotfix 2026-05-20 — backend-core):
     *   evidence — 빈 userId token cache pollution + cross-user leak risk.
     *   caller 결함 (예: CandleRefreshService.resolveAccessTokenAndKeys 의 `userId = ""` 명시 path)
     *   입구에서 fail-fast — 호출자가 명시적 userId 전달하도록 강제.
     */
    fun getAccessToken(
        userId: String,
        accountType: AccountType,
    ): String {
        require(userId.isNotBlank()) {
            "KIS getAccessToken userId 빈 문자열 차단 — caller 결함 " +
                "(cache key '${tokenKey("", accountType)}' pollution + cross-user leak risk)"
        }
        return resolveTokenAndKeys(userId, accountType).first
    }

    /**
     * Redis 토큰 캐시 강제 무효화 — 호출자가 KIS EGW00123 등 stale 토큰 검출 시 사용.
     *
     * 2026-05-21 hotfix (backend-core · EGW00123 self-heal):
     *   evidence — REAL dashboard 진입 시 GET /api/account/positions 502 무한 폭주.
     *   Redis TTL 살아있어 helper fast path(line 187 readCache)가 stale 토큰 무한 재사용 →
     *   `body={"msg_cd":"EGW00123","msg1":"기간이 만료된 token 입니다."}` 20+ 회 반복.
     *   수동 token cache DEL 후에야 신규 발급 + 정상 응답 복귀.
     *
     * 본 API 호출 → 다음 getAccessToken 호출에서 cache miss → 분산락 보호 하에 신규 발급.
     * 분산락(lock key)은 건드리지 않음 — 진행 중 발급이 있다면 그쪽이 완료 후 신규 토큰을 cache에 write.
     * Redis DEL은 idempotent이므로 다중 호출 안전.
     *
     * 호출 정책 (caller 의무):
     * - **재시도는 1회로 LIMIT** — 무한 루프 차단 (정상 발급된 토큰이 즉시 EGW00123 떨어지는 비정상 케이스).
     * - WebClient EGW00123 검출 후에만 호출 (네트워크 오류·다른 KIS 에러에서는 호출 금지).
     */
    fun evictToken(
        userId: String,
        accountType: AccountType,
    ) {
        require(userId.isNotBlank()) {
            "KIS evictToken userId 빈 문자열 차단 — caller 결함"
        }
        val cacheKey = tokenKey(userId, accountType)
        runCatching { redisTemplate.delete(cacheKey) }
            .onFailure { ex -> log.warn("Redis token evict 실패 — graceful: key={} err={}", cacheKey, ex.message) }
        log.info("KIS 토큰 evict (api helper): user={} type={}", maskUserId(userId), accountType.name)
    }

    /**
     * 토큰 + appKey + secretKey 묶음 반환 — KIS quotation endpoint 헤더 fix용.
     *
     * 토큰은 Redis cache + 분산락 보호. appKey/secretKey 는 매 호출 DB 복호화.
     * caller 가 사용 후 별도 zero-fill 의무는 없음 (helper 내부에서 String 변환 후 ByteArray fill).
     *
     * 빈 userId 차단 (P0 hotfix 2026-05-20) — getAccessToken 동일 사유.
     */
    fun resolveTokenAndKeys(
        userId: String,
        accountType: AccountType,
    ): Triple<String, String, String> {
        require(userId.isNotBlank()) {
            "KIS resolveTokenAndKeys userId 빈 문자열 차단 — caller 결함 " +
                "(cache key '${tokenKey("", accountType)}' pollution + cross-user leak risk)"
        }
        val cred = loadCredential(userId, accountType)
        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)
            val token = getOrIssueToken(userId, accountType, cred, appKeyStr, secretKeyStr)
            Triple(token, appKeyStr, secretKeyStr)
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * userId × accountType 으로 자격증명 정확 매칭.
     *
     * P0 hotfix 2026-05-20 (backend-core): "DB 첫 번째 row" fallback 폐기.
     *   - 기존: userId 매칭 실패 시 첫 row 반환 → cross-user 토큰 leak root cause
     *     (빈 userId 흘려도 첫 row credential 로 KIS tokenP 발급 + 오염 cache key 저장)
     *   - 신규: 정확 매칭 실패 시 IllegalStateException — 사용자별 자격증명 엄격 분리
     */
    private fun loadCredential(
        userId: String,
        accountType: AccountType,
    ): UserKisCredentialJpaEntity =
        credentialRepository
            .findAllByAccountType(accountType.name)
            .firstOrNull { it.id.userId == userId }
            ?: throw IllegalStateException(
                "KIS 자격증명 매칭 실패 — userId=$userId accountType=${accountType.name} 등록 필요 " +
                    "(fallback 폐기 — cross-user leak 차단)",
            )

    /**
     * Redis cache + 분산락 + KIS tokenP 발급 통합 (ADR 0038 패턴).
     */
    private fun getOrIssueToken(
        userId: String,
        accountType: AccountType,
        @Suppress("UNUSED_PARAMETER") cred: UserKisCredentialJpaEntity,
        appKey: String,
        secretKey: String,
    ): String {
        val cacheKey = tokenKey(userId, accountType)
        val distLockKey = lockKey(userId, accountType)

        // 1. fast path — Redis cache hit
        readCache(cacheKey)?.let { return it }

        // 2. in-process ReentrantLock 획득
        val inProcessLock =
            inProcessLocks.computeIfAbsent("$userId:${accountType.name}") { ReentrantLock() }
        return inProcessLock.withLock {
            // 2a. lock 안에서 double-check
            readCache(cacheKey)?.let { return@withLock it }

            // 2b. Redis SETNX 분산락 시도
            val lockAcquired =
                runCatching {
                    redisTemplate.opsForValue().setIfAbsent(distLockKey, "1", LOCK_TTL) == true
                }.getOrDefault(false)

            if (lockAcquired) {
                try {
                    // triple-check (다른 JVM 발급 가능)
                    readCache(cacheKey)?.let { return@withLock it }
                    val (token, ttl) = issueToken(accountType, appKey, secretKey)
                    writeCache(cacheKey, token, ttl)
                    log.info(
                        "KIS 토큰 발급 (api helper): user={} type={} ttl={}s",
                        maskUserId(userId),
                        accountType.name,
                        ttl.seconds,
                    )
                    token
                } finally {
                    runCatching { redisTemplate.delete(distLockKey) }
                }
            } else {
                // 분산락 미획득 — spinWait
                log.debug("KIS 토큰 발급 분산락 대기 (api): user={} type={}", maskUserId(userId), accountType.name)
                for (i in 0 until LOCK_MAX_RETRIES) {
                    Thread.sleep(LOCK_WAIT_MS)
                    readCache(cacheKey)?.let { return@withLock it }
                }
                // 30s 대기 후에도 없으면 직접 발급 (단일 JVM fallback)
                log.warn("KIS 토큰 분산락 30s 타임아웃 (api) — 직접 발급")
                val (token, ttl) = issueToken(accountType, appKey, secretKey)
                writeCache(cacheKey, token, ttl)
                token
            }
        }
    }

    /**
     * KIS `/oauth2/tokenP` 호출.
     *
     * 응답 `expires_in` (초) 을 95%로 줄여 TTL 계산. 누락 시 [FALLBACK_TTL] 사용.
     */
    private fun issueToken(
        accountType: AccountType,
        appKey: String,
        secretKey: String,
    ): Pair<String, Duration> {
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps
        return try {
            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/tokenP")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKey,
                            "appsecret" to secretKey,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(TOKEN_ISSUE_TIMEOUT)
                    .block() as? Map<String, Any>
                    ?: throw IllegalStateException("KIS tokenP 응답 없음")

            val token =
                response["access_token"]?.toString()
                    ?: throw IllegalStateException("KIS tokenP 응답에 access_token 없음")

            val expiresIn = parseExpiresInSeconds(response["expires_in"])
            val ttl = computeTtl(expiresIn)
            token to ttl
        } catch (ex: WebClientResponseException) {
            log.error(
                "KIS tokenP 실패 (api helper): status={} body={}",
                ex.statusCode,
                ex.responseBodyAsString.take(200),
            )
            throw ex
        }
    }

    /**
     * `expires_in` 파싱 — KIS는 보통 숫자 또는 문자열 반환. 누락 시 -1 반환.
     *
     * `internal` — KisTokenCacheHelperTest 에서 unit 검증 목적.
     */
    internal fun parseExpiresInSeconds(raw: Any?): Long =
        when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: -1L
            else -> -1L
        }

    /**
     * TTL 계산 — expires_in 양수면 × 0.95, 아니면 [FALLBACK_TTL].
     *
     * `internal` — 단위 테스트 검증.
     */
    internal fun computeTtl(expiresInSec: Long): Duration =
        if (expiresInSec > 0) {
            Duration.ofSeconds((expiresInSec * TTL_SAFETY_RATIO).toLong().coerceAtLeast(60L))
        } else {
            FALLBACK_TTL
        }

    private fun readCache(key: String): String? =
        runCatching { redisTemplate.opsForValue().get(key) }
            .onFailure { ex -> log.debug("Redis cache read 실패 — graceful: key={} err={}", key, ex.message) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun writeCache(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        runCatching { redisTemplate.opsForValue().set(key, value, ttl) }
            .onFailure { ex -> log.warn("Redis cache write 실패 — graceful: key={} err={}", key, ex.message) }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
