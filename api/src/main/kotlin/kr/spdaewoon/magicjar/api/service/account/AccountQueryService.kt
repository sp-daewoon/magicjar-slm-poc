package kr.spdaewoon.magicjar.api.service.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.controller.account.AccountBalanceResponse
import kr.spdaewoon.magicjar.api.controller.account.AccountHoldingResponse
import kr.spdaewoon.magicjar.api.controller.account.AccountSummaryResponse
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitException
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 계좌 잔고 쿼리 서비스 — T-ACCT Phase 2 (ADR 0081 §2-1 + spec §4-1 LOCK).
 *
 * ### 책임
 * - Redis `account:balance:{userId}:{accountType}` cache lookup (TTL 5분 — KIS 호출 압박 완화, 주문 이벤트 dirty)
 * - cache miss → [KisBalanceQueryPort] 1회 호출 (output1 + output2 동시 흡수)
 * - 응답 cache write + 3 endpoint별 응답 매핑
 *
 * ### cache stampede 완화 — 2단 single-flight (TPS 완화 hotfix 2026-06-08)
 * - 1단 in-JVM `synchronized(loadLocks[cacheKey])` — 동일 인스턴스 내 동시 요청 코얼레싱
 *   - evidence: 같은 ms(10:30:24.920~936) http-nio exec 3~4개가 /balance·/positions·/summary 동시 폴링 → 셋 다 cache miss → KIS burst
 * - 2단 Redis SETNX 분산락 `account:balance:lock:{userId}:{accountType}` (TTL 8s) — 다중 인스턴스 전역 코얼레싱
 *   - 락 보유자만 KIS 호출, 나머지는 50ms×20 backoff 동안 cache 재read (KisTokenCacheHelper ADR 0038 패턴 정합)
 *   - 분산락 미해제 방지: TTL 만료 + finally DEL 이중 안전
 *
 * ### KIS 호출 빈도 (ADR 0037 정합)
 * - cache hit = KIS 0 호출
 * - cache miss = KIS 1 호출 (`inquire-balance` 1건만 — 추가 endpoint 0)
 * - frontend auto-refresh fallback은 cache hit가 기본이며, 주문 이벤트가 Redis key를 dirty 처리하면 다음 요청이 fresh load
 * - 자본 시각화 즉시성은 STOMP invalidate + backend Redis dirty 조합으로 보존한다.
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * - KIS upstream 실패 → [KisBalanceQueryPort]가 IllegalStateException 또는 WebClientResponseException 발행
 * - 본 service는 예외를 그대로 propagate (controller에서 ResponseStatusException 502 변환)
 * - cache write 0 (실패 시 cache 오염 방지)
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4)
 * - LLM provider import 0
 * - 잔고 분석 코멘트는 W4 T-AI 후보 (본 트랙 비범위)
 *
 * ### CapitalSnapshotProvider 결합 (ADR 0067 §7-1 + ADR 0081 §2-5)
 * - 본 service의 cache write 결과를 consumer `KisCapitalSnapshotProviderAdapter`가 Redis 읽기로 재사용
 * - api endpoint 호출 시 cache 갱신 → consumer 룰 평가 시 cache hit → KIS 미호출
 */
@Service
class AccountQueryService(
    private val kisBalanceQueryPort: KisBalanceQueryPort,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AccountQueryService::class.java)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val rateLimitFailures = ConcurrentHashMap<String, LoadFailure>()

    /**
     * 직전 강제 새로고침(refresh=true) 완료 시각 — refresh debounce single-flight (ADR 0116).
     *
     * 결함 evidence (2026-06-05 14:59): 주문 직후 frontend 가 account 강제 새로고침 3연발(36/40/44s)
     *   → 각 refresh=true 가 single-flight 코얼레싱을 우회 → KIS 잔고조회 3회 burst → EGW00201 직격.
     * fix: refresh=true 도 직전 refresh 가 [REFRESH_DEBOUNCE] 이내면 그 결과(cache)를 공유 → KIS 1회로 수렴.
     */
    private val lastRefreshAt = ConcurrentHashMap<String, Instant>()

    companion object {
        /**
         * Account 잔고 cache TTL — KIS 조회 cache-aside singleflight (2026-06-15, 10s → 5m).
         *
         * 변경 이력:
         *   - 1차: 30s default
         *   - 2차 (PR #594 C-2): 30s → 5s "돈 관련 실시간" dogfooding
         *   - 3차: 5s → 10s — EGW00201 "초당 거래건수 초과" burst 흡수
         *     * 사용자 evidence 2026-05-20: REAL+MOCK 동시 polling 5s + schedule worker + Bootstrap
         *       → KIS 호출 burst → EGW00201 → 500/502 반복
         *   - 4차: 10s → 5m — 자동매매 consumer와 같은 account:balance key 공유.
         *     * 주문/체결/취소/거부 이벤트가 consumer AccountSnapshotCoordinator에서 즉시 key delete.
         *     * frontend는 STOMP invalidateQueries, backend는 Redis dirty로 2중 무효화.
         */
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
        private val RATE_LIMIT_FAILURE_TTL: Duration = Duration.ofSeconds(2)

        /**
         * 강제 새로고침 debounce 윈도 — 직전 refresh 후 이 시간 내 추가 refresh 는 KIS 재호출 0, 직전 결과 공유 (ADR 0116).
         *
         * 3s 사유: 14:59 evidence 의 36/40/44s 3연발(간격 ~4s) 흡수 + 주문 직후 UI 갱신은 order event 기반이 1차.
         * 사용자가 의도적으로 3s 이상 간격으로 새로고침하면 그때는 KIS 재호출 허용 (신선도 보존).
         */
        private val REFRESH_DEBOUNCE: Duration = Duration.ofSeconds(3)

        /**
         * 분산 single-flight 락 TTL — KIS `inquire-balance` 평균 응답 + 안전여유 (TPS 완화 hotfix 2026-06-08).
         *
         * 락 보유자가 죽어도 TTL 만료로 자동 해제(deadlock 방지). 정상 경로는 finally 즉시 DEL.
         */
        private val LOAD_LOCK_TTL: Duration = Duration.ofSeconds(8)

        /**
         * 분산락 미획득 시 backoff 재시도 — 50ms × 20회 = 최대 1s 대기.
         *
         * 락 보유 thread/instance 가 KIS 조회·cache write 완료할 때까지 짧게 polling 후 cache 재read.
         * 1s 내 채워지지 않으면 단일 JVM fallback(직접 조회) — 무한 대기 회피.
         */
        private const val LOAD_LOCK_WAIT_MS = 50L
        private const val LOAD_LOCK_MAX_RETRIES = 20

        fun loadLockKey(
            userId: String,
            accountType: AccountType,
        ): String = RedisKeyRegistry.Account.balanceLock(userId, accountType)
    }

    private data class LoadFailure(
        val at: Instant,
        val exception: RuntimeException,
    )

    /**
     * 잔고 응답 — output2 11 필드.
     */
    fun getBalance(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): AccountBalanceResponse {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        return AccountBalanceResponse.from(snapshot.balance, toIso(snapshot.fetchedAt))
    }

    /**
     * 보유 종목 응답 — output1 향상판 리스트.
     */
    fun getPositions(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): List<AccountHoldingResponse> {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        return snapshot.holdings.map { AccountHoldingResponse.from(it) }
    }

    /**
     * 계좌 요약 응답 — Dashboard 헤더용 (5 필드 + fetchedAt).
     *
     * 수익률 = totalProfitLoss / totalPurchaseAmount × 100 (매입금액 0 시 0 반환).
     */
    fun getSummary(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): AccountSummaryResponse {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        val balance = snapshot.balance
        val rate =
            if (balance.totalPurchaseAmount.signum() == 0) {
                BigDecimal.ZERO
            } else {
                balance.totalProfitLoss
                    .divide(balance.totalPurchaseAmount, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP)
            }
        return AccountSummaryResponse(
            totalEvaluation = balance.totalEvaluation,
            cashD2 = balance.cashD2,
            totalProfitLoss = balance.totalProfitLoss,
            profitLossRate = rate,
            holdingsCount = snapshot.holdings.size,
            fetchedAt = toIso(snapshot.fetchedAt),
        )
    }

    // ── private helper ─────────────────────────────────────────────────────────

    /**
     * Redis cache → KIS 호출 흐름 (1 endpoint 호출로 3 응답 모두 매핑).
     *
     * 헌법 §6 함정 회피: Repository 외부 service의 helper이므로 query inference 무관.
     */
    private fun fetchOrLoad(
        userId: String,
        accountType: AccountType,
        refresh: Boolean,
    ): KisAccountBalance {
        // hotfix 2026-05-20 — cache key 에 accountType 포함 (REAL/MOCK 분리 의무).
        // 사용자 dogfooding evidence: 기존 "$prefix:$userId" 단일 key → REAL/MOCK fetch 시 cache 충돌
        //   → cache miss 폭주 → KIS 호출 burst → EGW00201 "초당 거래건수 초과" 지속 발생.
        // fix: "$prefix:$userId:${accountType}" 분리 → REAL/MOCK 독립 cache → 충돌 해소.
        val cacheKey = RedisKeyRegistry.Account.balance(userId, accountType)

        // 1. Redis cache hit
        val cached = if (refresh) null else redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            runCatching { objectMapper.readValue<KisAccountBalance>(cached) }
                .onSuccess { snapshot ->
                    log.debug("account cache HIT: userId={}", maskUserId(userId))
                    return snapshot
                }.onFailure { ex ->
                    log.warn(
                        "account cache 역직렬화 실패 — KIS 재조회: userId={} err={}",
                        maskUserId(userId),
                        ex.message,
                    )
                    // graceful: cache 오염 시 즉시 삭제 + KIS 재조회
                    redisTemplate.delete(cacheKey)
                }
        }

        if (refresh) {
            log.info("account 강제 새로고침 — Redis cache bypass: userId={} type={}", maskUserId(userId), accountType.name)
        }

        // 2. cache miss 또는 refresh=true → KIS REST 직접 호출
        val lock = loadLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            // 일반(refresh=false) single-flight 코얼레싱 — 락 대기 중 다른 thread 가 이미 채웠나?
            // refresh=true single-flight debounce — 직전 refresh 가 debounce 이내면 그 결과(cache) 공유 (ADR 0116).
            //   주문 직후 강제 새로고침 3연발(14:59 evidence)이 KIS 잔고조회 burst 로 번지는 것을 1회로 수렴.
            val coalesceCache = !refresh || withinRefreshDebounce(cacheKey)
            if (coalesceCache) {
                val coalesced = redisTemplate.opsForValue().get(cacheKey)
                if (coalesced != null) {
                    runCatching { objectMapper.readValue<KisAccountBalance>(coalesced) }
                        .onSuccess { snapshot ->
                            if (refresh) {
                                log.info(
                                    "account 강제 새로고침 debounce 공유 — KIS 미호출(직전 refresh {} 이내): userId={} type={}",
                                    REFRESH_DEBOUNCE,
                                    maskUserId(userId),
                                    accountType.name,
                                )
                            } else {
                                log.debug("account cache HIT after single-flight wait: userId={}", maskUserId(userId))
                            }
                            return@synchronized snapshot
                        }.onFailure { ex ->
                            log.warn(
                                "account cache 역직렬화 실패(single-flight 재확인) — KIS 재조회: userId={} err={}",
                                maskUserId(userId),
                                ex.message,
                            )
                            redisTemplate.delete(cacheKey)
                        }
                }
            }

            recentRateLimitFailure(cacheKey)?.let { failure ->
                log.warn(
                    "account KIS rate-limit single-flight 실패 공유: userId={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                throw failure
            }

            try {
                // 분산 single-flight — in-JVM 코얼레싱을 통과한 thread 만 여기 도달.
                // 다중 인스턴스 환경에서 클러스터 전역 1 KIS 호출로 수렴 (cache stampede 완화).
                loadWithDistributedSingleFlight(userId, accountType, cacheKey)
                    .also {
                        rateLimitFailures.remove(cacheKey)
                        lastRefreshAt[cacheKey] = Instant.now() // debounce 마커 — 직후 refresh 연발 흡수
                    }
            } catch (ex: RuntimeException) {
                if (isRateLimitFailure(ex)) {
                    rateLimitFailures[cacheKey] = LoadFailure(Instant.now(), ex)
                } else {
                    rateLimitFailures.remove(cacheKey)
                }
                throw ex
            }
        }
    }

    /**
     * Redis SETNX 분산 single-flight — 잔고 KIS 조회 cache stampede 완화 (TPS 완화 hotfix 2026-06-08).
     *
     * in-JVM `synchronized(lock)` 통과 thread(인스턴스당 1) 가 호출. KisTokenCacheHelper(ADR 0038) 패턴 정합.
     * 1. SETNX `account:balance:lock:{userId}:{accountType}` (TTL [LOAD_LOCK_TTL]) 시도
     *    - 획득 → KIS 조회·cache write 후 finally 에서 락 DEL (락 보유자만 KIS 호출)
     *    - 미획득 → 다른 인스턴스가 조회 중 → 50ms×20 backoff 동안 cache 재read (coalesce)
     *      - cache 채워지면 그 결과 공유 (KIS 미호출)
     *      - 1s 내 미충족 → 단일 JVM fallback 직접 조회 (무한 대기 회피)
     *
     * Redis 장애(SETNX 실패) 시 graceful: 락 없이 직접 조회 (가용성 우선 — in-JVM 락은 여전히 보호).
     */
    private fun loadWithDistributedSingleFlight(
        userId: String,
        accountType: AccountType,
        cacheKey: String,
    ): KisAccountBalance {
        val lockKey = loadLockKey(userId, accountType)
        val lockAcquired =
            runCatching { redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOAD_LOCK_TTL) == true }
                .getOrDefault(false)

        if (lockAcquired) {
            return try {
                loadFromKisAndCache(userId, accountType, cacheKey)
            } finally {
                runCatching { redisTemplate.delete(lockKey) }
                    .onFailure { ex ->
                        log.warn("account 분산락 해제 실패 — TTL 만료 의존: key={} err={}", lockKey, ex.message)
                    }
            }
        }

        // 분산락 미획득 — 다른 인스턴스가 조회 중. backoff 동안 cache 재read 로 coalesce.
        log.debug("account 분산 single-flight 대기: userId={} type={}", maskUserId(userId), accountType.name)
        repeat(LOAD_LOCK_MAX_RETRIES) {
            Thread.sleep(LOAD_LOCK_WAIT_MS)
            readSnapshotFromCache(cacheKey)?.let { return it }
        }

        // 1s 대기 후에도 cache 미충족 → 단일 JVM fallback 직접 조회 (락 보유자 지연·사망 대비).
        log.warn(
            "account 분산 single-flight 타임아웃 — 직접 조회 fallback: userId={} type={}",
            maskUserId(userId),
            accountType.name,
        )
        return loadFromKisAndCache(userId, accountType, cacheKey)
    }

    /**
     * Redis cache 에서 스냅샷 역직렬화 — 분산락 대기 중 coalesce read 용. 실패/부재 시 null (graceful).
     */
    private fun readSnapshotFromCache(cacheKey: String): KisAccountBalance? {
        val cached =
            runCatching { redisTemplate.opsForValue().get(cacheKey) }
                .getOrNull() ?: return null
        return runCatching { objectMapper.readValue<KisAccountBalance>(cached) }.getOrNull()
    }

    /**
     * 직전 강제 새로고침이 [REFRESH_DEBOUNCE] 이내인지 — true 면 KIS 재호출 없이 cache 공유 (ADR 0116).
     */
    private fun withinRefreshDebounce(cacheKey: String): Boolean {
        val prev = lastRefreshAt[cacheKey] ?: return false
        return Duration.between(prev, Instant.now()) < REFRESH_DEBOUNCE
    }

    private fun recentRateLimitFailure(cacheKey: String): RuntimeException? {
        val failure = rateLimitFailures[cacheKey] ?: return null
        if (Duration.between(failure.at, Instant.now()) <= RATE_LIMIT_FAILURE_TTL) {
            return failure.exception
        }
        rateLimitFailures.remove(cacheKey, failure)
        return null
    }

    private fun isRateLimitFailure(ex: RuntimeException): Boolean =
        ex is KisRateLimitException ||
            (
                ex is WebClientResponseException &&
                    classifyKisError(ex.statusCode.value(), ex.responseBodyAsString) is KisErrorClass.RateLimit
            )

    private fun loadFromKisAndCache(
        userId: String,
        accountType: AccountType,
        cacheKey: String,
    ): KisAccountBalance {
        val snapshot = kisBalanceQueryPort.fetchFullBalance(userId, accountType)
        log.info(
            "account KIS 잔고조회 성공: userId={} type={} holdings={}건 cash={} netAsset={}",
            maskUserId(userId),
            accountType.name,
            snapshot.holdings.size,
            snapshot.balance.cashBalance,
            snapshot.balance.netAssetValue,
        )

        // 3. cache write (TTL 5m, 주문 이벤트 dirty)
        runCatching {
            val json = objectMapper.writeValueAsString(snapshot)
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL)
        }.onFailure { ex ->
            // cache write 실패는 graceful — 응답은 정상 반환
            log.warn("account cache write 실패: userId={} err={}", maskUserId(userId), ex.message)
        }
        return snapshot
    }

    private fun toIso(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
