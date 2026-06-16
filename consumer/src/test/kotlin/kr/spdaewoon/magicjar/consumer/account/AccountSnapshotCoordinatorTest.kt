package kr.spdaewoon.magicjar.consumer.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.ConsumerKisBalanceQueryAdapter
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccountSnapshotCoordinatorTest {
    private val fixedClock =
        Clock.fixed(
            Instant.parse("2026-06-15T00:30:00Z"),
            ZoneId.of("Asia/Seoul"),
        )

    @Test
    fun `동일 계좌 20개 동시 조회는 KIS 1회 호출 결과를 공유한다`() {
        val calls = AtomicInteger(0)
        val port =
            object : KisBalanceQueryPort {
                override fun fetchFullBalance(
                    userId: String,
                    accountType: AccountType,
                ): KisAccountBalance {
                    calls.incrementAndGet()
                    Thread.sleep(100)
                    return snapshot(userId, accountType, BigDecimal("1000000"))
                }
            }
        val sut = coordinator(port)
        val executor = Executors.newFixedThreadPool(20)
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)

        val futures =
            (1..20).map {
                executor.submit<KisAccountBalance> {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    sut.getSnapshot("USER123", AccountType.REAL, reason = "CONCURRENT_TEST")
                }
            }

        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue()
        start.countDown()
        val results = futures.map { it.get(2, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertThat(calls.get()).isEqualTo(1)
        assertThat(results).hasSize(20)
        assertThat(results.map { it.balance.netAssetValue }.toSet())
            .containsExactly(BigDecimal("1000000"))
    }

    @Test
    fun `invalidate 후 다음 조회는 캐시를 쓰지 않고 KIS를 다시 호출한다`() {
        val calls = AtomicInteger(0)
        val port =
            object : KisBalanceQueryPort {
                override fun fetchFullBalance(
                    userId: String,
                    accountType: AccountType,
                ): KisAccountBalance {
                    val version = calls.incrementAndGet()
                    return snapshot(userId, accountType, BigDecimal("1000000").plus(BigDecimal(version)))
                }
            }
        val sut = coordinator(port)

        val first = sut.getSnapshot("USER123", AccountType.REAL, reason = "FIRST")
        val cached = sut.getSnapshot("USER123", AccountType.REAL, reason = "SECOND")
        sut.invalidate("USER123", AccountType.REAL, reason = "ORDER_ACKED", triggerKisOrderNo = "ODNO1")
        val refreshed = sut.getSnapshot("USER123", AccountType.REAL, reason = "AFTER_INVALIDATE")

        assertThat(first.balance.netAssetValue).isEqualByComparingTo("1000001")
        assertThat(cached.balance.netAssetValue).isEqualByComparingTo("1000001")
        assertThat(refreshed.balance.netAssetValue).isEqualByComparingTo("1000002")
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun coordinator(port: KisBalanceQueryPort): AccountSnapshotCoordinator =
        AccountSnapshotCoordinator(
            balanceQueryPortProvider = provider(port),
            availabilityBalanceQueryAdapterProvider = provider<ConsumerKisBalanceQueryAdapter>(null),
            redisTemplateProvider = provider<StringRedisTemplate>(null),
            objectMapper = jacksonObjectMapper(),
            cacheTtlSeconds = 300,
            lockTtlSeconds = 1,
            lockWaitMillis = 1,
            lockWaitMaxMillis = 100,
            clock = fixedClock,
        )

    private fun snapshot(
        userId: String,
        accountType: AccountType,
        netAssetValue: BigDecimal,
    ): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = accountType,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("500000"),
                    cashD1 = BigDecimal("500000"),
                    cashD2 = BigDecimal("500000"),
                    totalEvaluation = netAssetValue,
                    netAssetValue = netAssetValue,
                    totalPurchaseAmount = BigDecimal.ZERO,
                    totalHoldingEvaluation = BigDecimal.ZERO,
                    totalProfitLoss = BigDecimal.ZERO,
                    fetchedAt = fixedClock.instant(),
                ),
            holdings = emptyList(),
            fetchedAt = fixedClock.instant(),
        )

    private fun <T : Any> provider(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}
