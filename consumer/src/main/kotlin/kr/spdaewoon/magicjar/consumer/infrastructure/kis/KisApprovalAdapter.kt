package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
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
 * KIS WebSocket approval_key 발급 어댑터 (ADR 0038 정합 — Redis cache + 분산락 + in-process mutex).
 *
 * KIS-first 출처: open-trading-api examples_user/kis_auth.py
 * - endpoint: POST /oauth2/Approval (access_token /oauth2/tokenP 와 별도)
 * - request body: {"grant_type": "client_credentials", "appkey": "...", "secretkey": "..."}
 * - response: {"approval_key": "..."}
 *
 * Redis:
 * - cache key: `kis:approval:{userId}:{accountType}` TTL 23h
 * - 분산락: `kis:approval:lock:{userId}:{accountType}` SETNX TTL 30s
 *
 * in-process ReentrantLock (per userId:accountType):
 * - cold-start 시 동시 thread가 KIS /oauth2/Approval 중복 호출하는 문제 차단
 * - EGW00133 (1분 1회 제한) 위반 방지 — KisAuthAdapter와 동일 패턴
 *
 * Rate limiter 합류 (ADR 0116 — 2026-06-05 orphan 정식화):
 * - 기존: limiter 미경유 → WS 재연결 루프가 /oauth2/Approval REST 를 우회 발사 → TPS bucket 미집계 → 주문 path 와 KIS 한도 충돌
 * - 변경: 실 KIS 호출(issueApprovalKey) 직전 [KisRateLimiterPort.acquire] (INGESTION priority — 주문보다 후순위)
 * - cache hit / 분산락 대기 path 는 KIS 호출 0 이라 acquire 0 (불필요한 토큰 소비 회피)
 * - INGESTION 은 SHARED·BG 버킷만 소비 → USER_PATH(주문) USER 버킷 굶지 않음 (ADR 0116 §2-B 역방향 fallback)
 */
@Component
class KisApprovalAdapter(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisApprovalAdapter::class.java)

    companion object {
        private const val APPROVAL_TTL_HOURS = 23L
        private const val LOCK_TTL_SECONDS = 30L
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60 // 30s / 0.5s
    }

    /**
     * in-process 락 맵 — userId:accountType 쌍별 ReentrantLock.
     * KisAuthAdapter와 동일 패턴 — approval_key 전용 독립 맵.
     */
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * KIS WebSocket approval_key 반환 (in-process mutex + Redis cache + 분산락).
     *
     * 직렬화 흐름 (double-check locking 2단):
     * 1) Redis cache hit → 즉시 반환 (락 없이)
     * 2) cache miss → in-process ReentrantLock 획득 (JVM 내 동시 thread 직렬화)
     *    2a) lock 내부 Redis double-check → hit 시 즉시 반환
     *    2b) Redis SETNX 분산락 획득
     *        - 획득 성공: POST /oauth2/Approval → cache 저장 → unlock
     *        - 획득 실패: spinWait 후 cache 재조회 → 없으면 직접 발급
     *
     * KIS-first: endpoint는 /oauth2/Approval (tokenP가 아님).
     * open-trading-api kis_auth.py auth_ws() 함수 기반.
     */
    fun getApprovalKey(
        userId: String,
        accountType: AccountType,
    ): String {
        val cacheKey = RedisKeyRegistry.Kis.approval(userId, accountType)
        val lockKey = RedisKeyRegistry.Kis.approvalLock(userId, accountType)

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
                    // Triple-check: 다른 JVM이 이미 발급?
                    redisTemplate.opsForValue().get(cacheKey)?.let { return@withLock it }

                    val key = issueApprovalKey(userId, accountType)
                    redisTemplate
                        .opsForValue()
                        .set(cacheKey, key, Duration.ofHours(APPROVAL_TTL_HOURS))
                    log.info(
                        "KIS approval_key 발급 완료: user={} type={}",
                        maskUserId(userId),
                        accountType.name,
                    )
                    key
                } finally {
                    redisTemplate.delete(lockKey)
                }
            } else {
                log.debug(
                    "KIS approval_key 분산락 대기: user={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                for (i in 0 until LOCK_MAX_RETRIES) {
                    Thread.sleep(LOCK_WAIT_MS)
                    redisTemplate.opsForValue().get(cacheKey)?.let { return@withLock it }
                }
                // 30s 대기 후에도 없으면 직접 발급 (단일 JVM fallback — in-process lock 보호 하에 안전)
                log.warn("KIS approval_key 분산락 30s 타임아웃 — 직접 발급")
                issueApprovalKey(userId, accountType).also { key ->
                    redisTemplate
                        .opsForValue()
                        .set(cacheKey, key, Duration.ofHours(APPROVAL_TTL_HOURS))
                }
            }
        }
    }

    /**
     * KIS /oauth2/Approval 직접 호출.
     *
     * KIS-first 출처: open-trading-api kis_auth.py auth_ws():
     *   url = f"{_cfg[svr]}/oauth2/Approval"
     *   p = {"grant_type": "client_credentials", "appkey": ..., "secretkey": ...}
     *   res = requests.post(url, data=json.dumps(p), headers=_getBaseHeader())
     *   approval_key = res.json()["approval_key"]
     */
    private fun issueApprovalKey(
        userId: String,
        accountType: AccountType,
    ): String {
        // user_kis_credential 식별 규약 불일치 (v3.1.0 backlog §23):
        //   REAL 키는 OnboardingService가 user_id=계좌번호로 저장
        //   MOCK 키는 user_id=KIS user_id로 저장
        // single-user 환경에서는 firstOrNull fallback으로 매칭. multi-tenant 진입(W3 T-O) 시 본격 정리.
        val creds = credentialRepository.findAllByAccountType(accountType.name)
        val cred =
            creds.firstOrNull { it.id.userId == userId }
                ?: creds.firstOrNull()
                ?: error("KIS 자격증명 없음 — userId=$userId type=${accountType.name}")

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)

            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

            // Rate limiter acquire (ADR 0116 — INGESTION priority, SHARED·BG 버킷, 주문 USER 버킷 미침범).
            // 실 KIS 호출 직전에만 acquire → cache hit·분산락 대기 path 는 토큰 소비 0.
            kisRateLimiterPort.acquire(KisCallPriority.INGESTION, timeoutMillis = 3000L, trId = "approval")

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/Approval")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKeyStr,
                            "secretkey" to secretKeyStr,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() as? Map<String, Any>
                    ?: run {
                        kisRateLimiterPort.onSuccess() // HTTP 정상이나 body null — token 소비 정상 집계
                        error("KIS /oauth2/Approval 응답 없음")
                    }

            kisRateLimiterPort.onSuccess()
            response["approval_key"]?.toString()
                ?: error("KIS /oauth2/Approval 응답에 approval_key 없음")
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
            }
            log.error(
                "KIS approval_key 발급 실패 (인증 실패 — 재시도 금지): user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            // 인증 실패(4xx)는 즉시 실패 — 재시도 금지 (ADR 0038 §2-1)
            throw e
        } finally {
            // 메모리 zero-fill (ADR 0038 §5-3 의무)
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * approval_key Redis cache 무효화 — doConnect() 진입 시 호출 (OPSP8996 해소).
     *
     * KIS는 동일 appkey의 활성 WS session을 1개만 허용 (OPSP8996 "ALREADY IN USE appkey").
     * consumer 재시작 또는 재연결 시 이전 session의 approval_key가 Redis에 잔류하면
     * 동일 approval_key로 subscribe 시 KIS가 기존 session 활성 상태로 판단 → OPSP8996 반환.
     *
     * 해결: 재연결 직전 cache DEL → getApprovalKey() 호출 시 KIS에서 새 approval_key 발급
     * → 새 session이 KIS 측에서 기존 session을 대체 (appkey별 1 session 정책 자동 처리됨).
     *
     * KIS-first 출처: open-trading-api kis_auth.py auth_ws() — 매 연결 시 approval_key 신규 발급 패턴.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    fun invalidate(
        userId: String,
        accountType: AccountType,
    ) {
        val cacheKey = RedisKeyRegistry.Kis.approval(userId, accountType)
        val deleted = redisTemplate.delete(cacheKey)
        log.info(
            "KIS approval_key cache 무효화 (재연결 전): user={} type={} deleted={}",
            maskUserId(userId),
            accountType.name,
            deleted,
        )
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
