package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SubscriptionRouter — watchlist polling 전용 (ADR 0124 결정 2).
 *
 * watchlist는 KIS WS 구독 0이므로 add/remove/recompute 모두 polling pool + sourceTracker("polling")만 다룬다.
 * KIS WS subscribe/unsubscribe는 절대 호출되지 않는다(owner demand는 RealtimeSubscriptionRouter 독립 관리).
 */
class SubscriptionRouterTest {
    private val kisWsPort = mockk<KisWsPort>(relaxed = true)
    private val pollingWorker = mockk<WatchlistPollingWorker>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>()
    private val sourceTracker = mockk<RedisSourceTrackerAdapter>(relaxed = true)

    private lateinit var router: SubscriptionRouter

    @BeforeEach
    fun setUp() {
        router =
            SubscriptionRouter(
                pollingWorker = pollingWorker,
                watchlistRepository = watchlistRepository,
                sourceTracker = sourceTracker,
            )
    }

    @Test
    fun `add - polling pool 등록 + WS 구독 미발사`() {
        every { pollingWorker.poolSize() } returns 1

        router.add("user001", "005930")

        verify { pollingWorker.add("005930") }
        verify { sourceTracker.set("user001", "005930", "polling") }
        verify(exactly = 0) { kisWsPort.subscribe(any()) }
    }

    @Test
    fun `remove - polling pool 제거 + WS unsubscribe 미발사`() {
        every { pollingWorker.poolSize() } returns 0

        router.remove("user001", "005930")

        verify { pollingWorker.remove("005930") }
        verify { sourceTracker.remove("user001", "005930") }
        verify(exactly = 0) { kisWsPort.unsubscribe(any()) }
    }

    @Test
    fun `recompute - watchlist 전 종목 polling 등록 + WS 구독 0`() {
        val now = Clock.System.now()
        val entries =
            listOf("005930", "000660", "035720").mapIndexed { idx, code ->
                WatchlistEntry(id = idx.toLong() + 1, userId = "user001", code = code, priority = 100, createdAt = now)
            }
        every { watchlistRepository.findByUserIdOrderByPriority("user001") } returns entries

        router.recompute("user001")

        entries.forEach { entry ->
            verify { pollingWorker.add(entry.code) }
            verify { sourceTracker.set("user001", entry.code, "polling") }
        }
        verify(exactly = 0) { kisWsPort.subscribe(any()) }
    }
}
