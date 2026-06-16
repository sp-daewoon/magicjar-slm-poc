package kr.spdaewoon.magicjar.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.service.account.AccountQueryService
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AccountQueryService 단위 테스트 — T-ACCT Phase 2 (ADR 0081 + spec §13).
 *

 * 시나리오:
 * 1. cache miss → KIS 호출 + cache write TTL 5분 (주문 이벤트 dirty + singleflight 공유)
 * 2. cache hit → KIS 호출 0
 * 3. summary 응답 수익률 계산 (totalProfitLoss / totalPurchaseAmount × 100)
 * 4. positions 응답 매핑 (output1 향상판)
 * 5. cache 역직렬화 실패 → cache 삭제 + KIS 재조회 (graceful)
 *
 * mock 합성 0 LOCK (feedback_no_mock_data): 본 테스트는 fixture (테스트 fixture는 면제).
 * AI 호출 0 LOCK: LLM import 0.
 */
class AccountQueryServiceTest {
    private val kisPort: KisBalanceQueryPort = mock()
    private val redis: StringRedisTemplate = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private val sut = AccountQueryService(kisPort, redis, objectMapper)

    private val userId = "user_test_001"
    private val accountType = AccountType.REAL

    // hotfix 2026-05-20 — cache key 에 accountType 포함 (REAL/MOCK 분리)
    private val cacheKey = "account:balance:user:$userId:account:REAL"
    private val lockKey = "account:balance:user:$userId:account:REAL:lock"
    private val now: Instant = Instant.parse("2026-05-18T05:30:00Z")

    /**
     * 분산락 SETNX 기본 stub — 단일 thread 테스트는 락을 즉시 획득(true)해 backoff 없이 KIS 직행.
     * 동시성 테스트는 backing map 기반 atomic SETNX 로 별도 override.
     */
    private fun stubLockAcquired() {
        whenever(valueOps.setIfAbsent(any<String>(), any<String>(), any<Duration>())).thenReturn(true)
    }

    /**
     * 실제 Redis SETNX 분산락을 모사 — backing map 으로 atomic putIfAbsent / DEL 구현.
     * 동시성 테스트에서 단 1 thread/instance 만 락을 획득하고 KIS 를 호출하는지 검증한다.
     */
    private fun stubAtomicLock(lockMap: java.util.concurrent.ConcurrentHashMap<String, String>) {
        whenever(valueOps.setIfAbsent(any<String>(), any<String>(), any<Duration>())).thenAnswer { inv ->
            val key = inv.arguments[0] as String
            lockMap.putIfAbsent(key, inv.arguments[1] as String) == null
        }
        doAnswer { inv ->
            lockMap.remove(inv.arguments[0] as String)
            true
        }.whenever(redis).delete(any<String>())
    }

    private fun fixtureSnapshot(): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = accountType,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000.00"),
                    cashD1 = BigDecimal("10000000.00"),
                    cashD2 = BigDecimal("10000000.00"),
                    totalEvaluation = BigDecimal("12000000.00"),
                    netAssetValue = BigDecimal("12000000.00"),
                    totalPurchaseAmount = BigDecimal("1900000.00"),
                    totalHoldingEvaluation = BigDecimal("2000000.00"),
                    totalProfitLoss = BigDecimal("100000.00"),
                    assetIncreaseDecrease = BigDecimal("50000.00"),
                    assetIncreaseDecreaseRate = BigDecimal("0.42"),
                    fetchedAt = now,
                ),
            holdings =
                listOf(
                    AccountHolding(
                        code = "005930",
                        name = "삼성전자",
                        quantity = 10,
                        avgPrice = BigDecimal("70000.00"),
                        currentPrice = BigDecimal("71000.00"),
                        evaluationAmount = BigDecimal("710000.00"),
                        profitLoss = BigDecimal("10000.00"),
                        profitLossRate = BigDecimal("1.43"),
                    ),
                    AccountHolding(
                        code = "000660",
                        name = "SK하이닉스",
                        quantity = 5,
                        avgPrice = BigDecimal("240000.00"),
                        currentPrice = BigDecimal("258000.00"),
                        evaluationAmount = BigDecimal("1290000.00"),
                        profitLoss = BigDecimal("90000.00"),
                        profitLossRate = BigDecimal("7.50"),
                    ),
                ),
            fetchedAt = now,
        )

    @Test
    fun `cache miss — KIS 호출 1회 + cache SETEX TTL 5분 (singleflight cache-aside 정합)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        assertThat(response.netAssetValue).isEqualByComparingTo("12000000.00")
        assertThat(response.totalProfitLoss).isEqualByComparingTo("100000.00")
        assertThat(response.fetchedAt).isEqualTo("2026-05-18T05:30:00Z")

        // cache write TTL 5분 검증 — 주문/체결/취소/거부 이벤트 dirty 로 신선도 보존.
        val keyCap = argumentCaptor<String>()
        val ttlCap = argumentCaptor<Duration>()
        verify(valueOps).set(keyCap.capture(), any<String>(), ttlCap.capture())
        assertThat(keyCap.firstValue).isEqualTo(cacheKey)
        assertThat(ttlCap.firstValue).isEqualTo(Duration.ofMinutes(5))

        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `KIS account snapshot — CACHE_TTL companion 상수 5분 LOCK`() {
        assertThat(AccountQueryService.CACHE_TTL).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `cache key — REAL accountType 별도 key (account-balance-userId-REAL)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq("account:balance:user:$userId:account:REAL"))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, AccountType.REAL)).thenReturn(fixtureSnapshot())

        sut.getBalance(userId, AccountType.REAL)

        // REAL key 만 read·write 검증 (MOCK key 무관)
        verify(valueOps, atLeastOnce()).get(eq("account:balance:user:$userId:account:REAL"))
        val keyCap = argumentCaptor<String>()
        verify(valueOps).set(keyCap.capture(), any<String>(), any<Duration>())
        assertThat(keyCap.firstValue).isEqualTo("account:balance:user:$userId:account:REAL")
    }

    @Test
    fun `cache key — MOCK accountType 별도 key (account-balance-userId-MOCK) — REAL cache 무관`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq("account:balance:user:$userId:account:MOCK"))).thenReturn(null)
        // MOCK fixture
        val mockSnapshot = fixtureSnapshot().copy(accountType = AccountType.MOCK)
        whenever(kisPort.fetchFullBalance(userId, AccountType.MOCK)).thenReturn(mockSnapshot)

        sut.getBalance(userId, AccountType.MOCK)

        verify(valueOps, atLeastOnce()).get(eq("account:balance:user:$userId:account:MOCK"))
        // REAL key 미접근 검증 (cross-contamination 차단)
        verify(valueOps, never()).get(eq("account:balance:user:$userId:account:REAL"))
    }

    @Test
    fun `cache hit — KIS 호출 0 + cache JSON 역직렬화`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        val json = objectMapper.writeValueAsString(fixtureSnapshot())
        whenever(valueOps.get(eq(cacheKey))).thenReturn(json)

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        verify(kisPort, never()).fetchFullBalance(any(), any())
        verify(valueOps, never()).set(any<String>(), any<String>(), any<Duration>())
    }

    @Test
    fun `refresh true — Redis cache hit 무시하고 KIS 재조회 후 cache overwrite`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        val staleJson = objectMapper.writeValueAsString(fixtureSnapshot())
        whenever(valueOps.get(eq(cacheKey))).thenReturn(staleJson)
        val freshSnapshot =
            fixtureSnapshot().copy(
                holdings = listOf(fixtureSnapshot().holdings[0].copy(quantity = 12)),
            )
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(freshSnapshot)

        val positions = sut.getPositions(userId, accountType, refresh = true)

        assertThat(positions).hasSize(1)
        assertThat(positions[0].quantity).isEqualTo(12)
        verify(valueOps, never()).get(eq(cacheKey))
        verify(kisPort).fetchFullBalance(userId, accountType)
        verify(valueOps).set(eq(cacheKey), any<String>(), eq(Duration.ofMinutes(5)))
    }

    /**
     * ★ 강제 새로고침 debounce single-flight 회귀 (ADR 0116, 14:59 evidence 직격).
     *
     * 주문 직후 frontend 강제 새로고침 3연발 → 첫 refresh 만 KIS 호출, 이후 debounce(3s) 이내 refresh 는
     * 직전 결과(cache) 공유 → KIS 잔고조회 1회로 수렴 (EGW00201 burst 차단).
     */
    @Test
    fun `refresh 3연발 — debounce 이내면 KIS 1회로 수렴 (single-flight)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        val freshSnapshot = fixtureSnapshot()
        // 첫 KIS 호출 후 cache 에 기록된 JSON 을 이후 get 이 반환하도록 stub (cache write → read 모사).
        val cachedJson = objectMapper.writeValueAsString(freshSnapshot)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(freshSnapshot)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(cachedJson)

        // 3연발 강제 새로고침 (간격 0 — burst 가정)
        sut.getSummary(userId, accountType, refresh = true)
        sut.getSummary(userId, accountType, refresh = true)
        sut.getSummary(userId, accountType, refresh = true)

        // KIS 잔고조회는 정확히 1회 (2·3번째는 debounce 공유)
        verify(kisPort, org.mockito.kotlin.times(1)).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `summary — 수익률 = totalProfitLoss div totalPurchaseAmount times 100, 보유 종목 수 정합`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val summary = sut.getSummary(userId, accountType)

        assertThat(summary.totalEvaluation).isEqualByComparingTo("12000000.00")
        assertThat(summary.cashD2).isEqualByComparingTo("10000000.00")
        assertThat(summary.totalProfitLoss).isEqualByComparingTo("100000.00")
        // 100000 / 1900000 × 100 = 5.26
        assertThat(summary.profitLossRate).isEqualByComparingTo("5.26")
        assertThat(summary.holdingsCount).isEqualTo(2)
    }

    @Test
    fun `positions — output1 향상판 매핑 (8 컬럼 정합)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val positions = sut.getPositions(userId, accountType)

        assertThat(positions).hasSize(2)
        val samsung = positions[0]
        assertThat(samsung.code).isEqualTo("005930")
        assertThat(samsung.name).isEqualTo("삼성전자")
        assertThat(samsung.quantity).isEqualTo(10)
        assertThat(samsung.avgPrice).isEqualByComparingTo("70000.00")
        assertThat(samsung.currentPrice).isEqualByComparingTo("71000.00")
        assertThat(samsung.evaluationAmount).isEqualByComparingTo("710000.00")
        assertThat(samsung.profitLoss).isEqualByComparingTo("10000.00")
        assertThat(samsung.profitLossRate).isEqualByComparingTo("1.43")
    }

    @Test
    fun `cache 역직렬화 실패 — cache 삭제 후 KIS 재조회 (graceful)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq(cacheKey))).thenReturn("not-a-valid-json{", null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        verify(redis).delete(eq(cacheKey))
        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `concurrent cache miss — same account coalesces into one KIS call`() {
        val backingCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        val backingLocks = java.util.concurrent.ConcurrentHashMap<String, String>()
        val fetchCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)

        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubAtomicLock(backingLocks)
        whenever(valueOps.get(any())).thenAnswer { invocation ->
            backingCache[invocation.arguments[0] as String]
        }
        doAnswer { invocation ->
            backingCache[invocation.arguments[0] as String] = invocation.arguments[1] as String
            null
        }.whenever(valueOps).set(any<String>(), any<String>(), any<Duration>())
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenAnswer {
            fetchCount.incrementAndGet()
            TimeUnit.MILLISECONDS.sleep(50)
            fixtureSnapshot()
        }

        repeat(3) {
            pool.submit {
                try {
                    start.await()
                    sut.getSummary(userId, accountType)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(fetchCount.get()).isEqualTo(1)
        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `concurrent rate-limit failure — same account shares short failure without serial KIS retry`() {
        val backingLocks = java.util.concurrent.ConcurrentHashMap<String, String>()
        val fetchCount = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)

        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubAtomicLock(backingLocks)
        whenever(valueOps.get(any())).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenAnswer {
            fetchCount.incrementAndGet()
            TimeUnit.MILLISECONDS.sleep(50)
            throw KisRateLimitTimeoutException(KisCallPriority.USER_PATH, 3000L)
        }

        repeat(3) {
            pool.submit {
                try {
                    start.await()
                    sut.getSummary(userId, accountType)
                } catch (ex: Throwable) {
                    errors.add(ex)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(errors).hasSize(3)
        assertThat(errors).allSatisfy { ex ->
            assertThat(ex).isInstanceOf(KisRateLimitTimeoutException::class.java)
        }
        assertThat(fetchCount.get()).isEqualTo(1)
        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    /**
     * ★ 분산 single-flight 핵심 — 락 미획득 instance 는 KIS 미호출, backoff 중 cache 재read 로 결과 공유.
     *
     * 다른 instance(또는 thread)가 이미 SETNX 락을 잡은 상황 모사: 본 호출은 setIfAbsent=false →
     * backoff polling → 락 보유자가 cache write 완료 시점에 cache hit → KIS 0 호출 (cluster 전역 coalesce).
     */
    @Test
    fun `distributed lock 미획득 — backoff 중 cache 채워지면 KIS 미호출 (cluster coalesce)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        // 다른 instance 가 락 선점 — 본 호출은 분산락 획득 실패.
        whenever(valueOps.setIfAbsent(any<String>(), any<String>(), any<Duration>())).thenReturn(false)
        // cache: 첫 lookup·coalesce 재확인 시 miss → backoff 2회째에 락 보유자가 write 한 것처럼 hit.
        val cachedJson = objectMapper.writeValueAsString(fixtureSnapshot())
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null, null, null, cachedJson)

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        // 락 보유자(타 instance)가 KIS 호출 — 본 instance 는 0 호출.
        verify(kisPort, never()).fetchFullBalance(any(), any())
        // 락 미보유이므로 lock DEL 도 하지 않음.
        verify(redis, never()).delete(eq(lockKey))
    }

    /**
     * 분산락 보유자는 finally 에서 락을 반드시 해제 — KIS 실패(rate limit) 시에도 deadlock 방지.
     */
    @Test
    fun `distributed lock 보유 — KIS 실패해도 finally 에서 락 해제`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        stubLockAcquired()
        whenever(kisPort.fetchFullBalance(userId, accountType))
            .thenThrow(KisRateLimitTimeoutException(KisCallPriority.USER_PATH, 3000L))

        try {
            sut.getBalance(userId, accountType)
        } catch (_: KisRateLimitTimeoutException) {
            // expected
        }

        // 락 보유자이므로 finally 에서 lock DEL 1회 — TTL 만료 전에도 즉시 해제.
        verify(redis).delete(eq(lockKey))
    }

    /**
     * cache hit fast path 는 분산락을 건드리지 않음 — KIS·SETNX 모두 0 (불필요 락 회피).
     */
    @Test
    fun `cache hit — 분산락 SETNX 미사용 (fast path)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        val json = objectMapper.writeValueAsString(fixtureSnapshot())
        whenever(valueOps.get(eq(cacheKey))).thenReturn(json)

        sut.getBalance(userId, accountType)

        verify(kisPort, never()).fetchFullBalance(any(), any())
        verify(valueOps, never()).setIfAbsent(any<String>(), any<String>(), any<Duration>())
    }

    @Test
    fun `summary — totalPurchaseAmount 0 시 수익률 0 fallback (divide by zero 회피)`() {
        val snapshot =
            fixtureSnapshot().copy(
                balance =
                    fixtureSnapshot().balance.copy(
                        totalPurchaseAmount = BigDecimal.ZERO,
                        totalProfitLoss = BigDecimal.ZERO,
                    ),
                holdings = emptyList(),
            )
        whenever(redis.opsForValue()).thenReturn(valueOps)
        stubLockAcquired()
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(snapshot)

        val summary = sut.getSummary(userId, accountType)

        assertThat(summary.profitLossRate).isEqualByComparingTo("0")
        assertThat(summary.holdingsCount).isEqualTo(0)
    }

    @Test
    fun `cache hit JSON round-trip — 11 필드 직렬화 정합`() {
        // JSON serialize → deserialize 정합 검증 (cache 호환성)
        val original = fixtureSnapshot()
        val json = objectMapper.writeValueAsString(original)
        val restored: KisAccountBalance = objectMapper.readValue(json)

        assertThat(restored.balance.cashBalance).isEqualByComparingTo(original.balance.cashBalance)
        assertThat(restored.balance.cashD1).isEqualByComparingTo(original.balance.cashD1)
        assertThat(restored.balance.cashD2).isEqualByComparingTo(original.balance.cashD2)
        assertThat(restored.balance.totalEvaluation).isEqualByComparingTo(original.balance.totalEvaluation)
        assertThat(restored.balance.netAssetValue).isEqualByComparingTo(original.balance.netAssetValue)
        assertThat(restored.balance.totalPurchaseAmount).isEqualByComparingTo(original.balance.totalPurchaseAmount)
        assertThat(restored.balance.totalHoldingEvaluation).isEqualByComparingTo(original.balance.totalHoldingEvaluation)
        assertThat(restored.balance.totalProfitLoss).isEqualByComparingTo(original.balance.totalProfitLoss)
        assertThat(restored.balance.assetIncreaseDecrease)
            .isEqualByComparingTo(original.balance.assetIncreaseDecrease)
        assertThat(restored.balance.assetIncreaseDecreaseRate)
            .isEqualByComparingTo(original.balance.assetIncreaseDecreaseRate)
        assertThat(restored.holdings).hasSize(2)
    }
}
