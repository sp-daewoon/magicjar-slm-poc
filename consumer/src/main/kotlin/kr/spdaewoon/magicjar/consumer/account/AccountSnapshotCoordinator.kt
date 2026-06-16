package kr.spdaewoon.magicjar.consumer.account

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.ConsumerKisBalanceQueryAdapter
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Consumer-side account snapshot cache-aside coordinator.
 *
 * The raw KIS adapter remains the single place that knows the HTTP contract; this coordinator
 * only coalesces duplicate account reads and invalidates the shared account cache after order
 * state changes. Cache key intentionally matches API [AccountQueryService] convention:
 * `account:balance:{userId}:{accountType}`.
 */
@Service
class AccountSnapshotCoordinator(
    private val balanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>,
    private val availabilityBalanceQueryAdapterProvider: ObjectProvider<ConsumerKisBalanceQueryAdapter>,
    private val redisTemplateProvider: ObjectProvider<StringRedisTemplate>,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.kis.account-snapshot.cache-ttl-seconds:300}")
    private val cacheTtlSeconds: Long = 300,
    @Value("\${magicjar.kis.account-snapshot.lock-ttl-seconds:12}")
    private val lockTtlSeconds: Long = 12,
    @Value("\${magicjar.kis.account-snapshot.lock-wait-millis:50}")
    private val lockWaitMillis: Long = 50,
    @Value("\${magicjar.kis.account-snapshot.lock-wait-max-millis:10000}")
    private val lockWaitMaxMillis: Long = 10_000,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(AccountSnapshotCoordinator::class.java)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val localCache = ConcurrentHashMap<String, LocalSnapshot>()

    fun getSnapshot(
        userId: String,
        accountType: AccountType,
        reason: String,
        forceRefresh: Boolean = false,
        path: QueryPath = QueryPath.USER_PATH,
    ): KisAccountBalance {
        val cacheKey = cacheKey(userId, accountType)
        if (!forceRefresh) {
            readSnapshotFromCache(cacheKey)?.let {
                log.debug(
                    "account snapshot cache HIT: userId={} accountType={} reason={}",
                    maskUserId(userId),
                    accountType.name,
                    reason,
                )
                return it
            }
        }

        val lock = loadLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            if (!forceRefresh) {
                readSnapshotFromCache(cacheKey)?.let {
                    log.debug(
                        "account snapshot cache HIT after local singleflight: userId={} accountType={} reason={}",
                        maskUserId(userId),
                        accountType.name,
                        reason,
                    )
                    return@synchronized it
                }
            }
            loadWithDistributedSingleFlight(userId, accountType, cacheKey, reason, path)
        }
    }

    fun invalidate(
        userId: String,
        accountType: AccountType,
        reason: String,
        triggerKisOrderNo: String? = null,
    ) {
        val cacheKey = cacheKey(userId, accountType)
        localCache.remove(cacheKey)
        redisTemplateProvider.getIfAvailable()?.let { redis ->
            runCatching { redis.delete(cacheKey) }
                .onFailure { e ->
                    log.warn(
                        "account snapshot Redis invalidate 실패: userId={} accountType={} reason={} err={}",
                        maskUserId(userId),
                        accountType.name,
                        reason,
                        e.message,
                    )
                }
        }
        log.info(
            "account snapshot invalidated: userId={} accountType={} reason={} odno={}",
            maskUserId(userId),
            accountType.name,
            reason,
            triggerKisOrderNo,
        )
    }

    fun invalidate(
        userId: String,
        mode: TradingMode,
        reason: String,
        triggerKisOrderNo: String? = null,
    ) = invalidate(userId, mode.toAccountType(), reason, triggerKisOrderNo)

    private fun loadWithDistributedSingleFlight(
        userId: String,
        accountType: AccountType,
        cacheKey: String,
        reason: String,
        path: QueryPath,
    ): KisAccountBalance {
        val redis = redisTemplateProvider.getIfAvailable()
        if (redis == null) {
            return loadFromKisAndCache(userId, accountType, cacheKey, reason, path)
        }

        val lockKey = lockKey(userId, accountType)
        val lockAcquired =
            runCatching {
                redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(lockTtlSeconds.coerceAtLeast(1))) == true
            }.getOrDefault(false)

        if (lockAcquired) {
            return try {
                loadFromKisAndCache(userId, accountType, cacheKey, reason, path)
            } finally {
                runCatching { redis.delete(lockKey) }
                    .onFailure { e -> log.warn("account snapshot lock release 실패: key={} err={}", lockKey, e.message) }
            }
        }

        val maxRetries = (lockWaitMaxMillis.coerceAtLeast(lockWaitMillis) / lockWaitMillis.coerceAtLeast(1)).toInt()
        repeat(maxRetries.coerceAtLeast(1)) {
            Thread.sleep(lockWaitMillis.coerceAtLeast(1))
            readSnapshotFromCache(cacheKey)?.let {
                log.info(
                    "account snapshot distributed singleflight 공유: userId={} accountType={} reason={}",
                    maskUserId(userId),
                    accountType.name,
                    reason,
                )
                return it
            }
        }

        log.warn(
            "account snapshot distributed singleflight timeout — direct load fallback: userId={} accountType={} reason={}",
            maskUserId(userId),
            accountType.name,
            reason,
        )
        return loadFromKisAndCache(userId, accountType, cacheKey, reason, path)
    }

    private fun loadFromKisAndCache(
        userId: String,
        accountType: AccountType,
        cacheKey: String,
        reason: String,
        path: QueryPath,
    ): KisAccountBalance {
        val snapshot =
            when (path) {
                QueryPath.USER_PATH ->
                    balanceQueryPortProvider
                        .getIfAvailable()
                        ?.fetchFullBalance(userId, accountType)
                        ?: error("KisBalanceQueryPort bean unavailable")
                QueryPath.BACKGROUND_REFRESH ->
                    availabilityBalanceQueryAdapterProvider
                        .getIfAvailable()
                        ?.fetchFullBalanceForAvailabilityRefresh(userId, accountType)
                        ?: balanceQueryPortProvider
                            .getIfAvailable()
                            ?.fetchFullBalance(userId, accountType)
                        ?: error("KIS balance adapter bean unavailable")
            }

        writeSnapshotToCache(cacheKey, snapshot)
        log.info(
            "account snapshot KIS load: userId={} accountType={} reason={} path={} holdings={} netAsset={}",
            maskUserId(userId),
            accountType.name,
            reason,
            path.name,
            snapshot.holdings.size,
            snapshot.balance.netAssetValue,
        )
        return snapshot
    }

    private fun readSnapshotFromCache(cacheKey: String): KisAccountBalance? {
        val redis = redisTemplateProvider.getIfAvailable()
        if (redis != null) {
            val cached =
                runCatching { redis.opsForValue().get(cacheKey) }
                    .onFailure { e -> log.warn("account snapshot Redis read 실패: key={} err={}", cacheKey, e.message) }
                    .getOrNull()
            if (cached != null) {
                return runCatching { objectMapper.readValue(cached, KisAccountBalance::class.java) }
                    .onFailure { e ->
                        log.warn("account snapshot cache decode 실패 — key 삭제: key={} err={}", cacheKey, e.message)
                        runCatching { redis.delete(cacheKey) }
                    }.getOrNull()
            }
            return null
        }

        val local = localCache[cacheKey] ?: return null
        val age = Duration.between(local.cachedAt, Instant.now(clock))
        if (age <= Duration.ofSeconds(cacheTtlSeconds.coerceAtLeast(1))) return local.snapshot
        localCache.remove(cacheKey, local)
        return null
    }

    private fun writeSnapshotToCache(
        cacheKey: String,
        snapshot: KisAccountBalance,
    ) {
        localCache[cacheKey] = LocalSnapshot(snapshot, Instant.now(clock))
        val redis = redisTemplateProvider.getIfAvailable() ?: return
        runCatching {
            redis.opsForValue().set(
                cacheKey,
                objectMapper.writeValueAsString(snapshot),
                Duration.ofSeconds(cacheTtlSeconds.coerceAtLeast(1)),
            )
        }.onFailure { e ->
            log.warn("account snapshot Redis write 실패: key={} err={}", cacheKey, e.message)
        }
    }

    private fun cacheKey(
        userId: String,
        accountType: AccountType,
    ): String = RedisKeyRegistry.Account.balance(userId, accountType)

    private fun lockKey(
        userId: String,
        accountType: AccountType,
    ): String = RedisKeyRegistry.Account.balanceLock(userId, accountType)

    private fun TradingMode.toAccountType(): AccountType = if (this == TradingMode.REAL) AccountType.REAL else AccountType.MOCK

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    enum class QueryPath {
        USER_PATH,
        BACKGROUND_REFRESH,
    }

    private data class LocalSnapshot(
        val snapshot: KisAccountBalance,
        val cachedAt: Instant,
    )
}
