package kr.spdaewoon.magicjar.api.scheduler

import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsAdapter
import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsRateLimiter
import kr.spdaewoon.magicjar.api.infrastructure.naver.NewsKafkaProducer
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaEntity
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

/**
 * WatchlistNewsScheduler staggering 검증 — backlog §57 (안정성 Cycle 1).
 *
 * 검증 목표:
 *   1. INTER_CALL_DELAY_MS 상수 = 3,500ms (60,000/17 ≈ 3,529 → 3,500 채택, Naver 분당 17건 hard limit 정합)
 *   2. 빈 watchlist 시 즉시 return — fetchAndSave 미호출
 *   3. 다중 종목 시 RateLimiter slot 획득 후 fetchAndSave 호출
 *
 * 비범위: Thread.sleep 정확도 (System clock 의존 — 통합 환경 docker logs evidence로 검증).
 */
class WatchlistNewsSchedulerTest {
    /**
     * 검증 1: INTER_CALL_DELAY_MS 상수 정합 (backlog §57 산식).
     * 60,000ms / 17건 = 3,529ms → 3,500ms 채택 (분당 17건 균등 분포로 Naver burst 회피).
     */
    @Test
    fun `INTER_CALL_DELAY_MS - 3500ms 산식 정합`() {
        // given/then
        assertThat(WatchlistNewsScheduler.INTER_CALL_DELAY_MS).isEqualTo(3_500L)

        // RateLimiter LIMIT_PER_MINUTE = 17 — 60,000/17 ≈ 3,529ms 한도 미만 보장
        val maxAllowedDelay = 60_000L / NaverNewsRateLimiter.LIMIT_PER_MINUTE
        assertThat(WatchlistNewsScheduler.INTER_CALL_DELAY_MS)
            .isLessThanOrEqualTo(maxAllowedDelay)
    }

    /**
     * 검증 2: 빈 watchlist 시 즉시 return — fetchAndSave 호출 0건 + RateLimiter 호출 0건.
     */
    @Test
    fun `pollWatchlistNews - 빈 watchlist 즉시 return`() {
        // given
        val watchlistRepo: WatchlistJpaRepository = mock()
        val instrumentRepo: InstrumentJpaRepository = mock()
        val adapter: NaverNewsAdapter = mock()
        val rateLimiter: NaverNewsRateLimiter = mock()
        val kafka: NewsKafkaProducer = mock()
        whenever(watchlistRepo.findAll()).thenReturn(emptyList())

        val scheduler = WatchlistNewsScheduler(watchlistRepo, instrumentRepo, adapter, rateLimiter, kafka)

        // when
        scheduler.pollWatchlistNews()

        // then
        verify(rateLimiter, never()).acquireSlot()
        verify(adapter, never()).fetchAndSave(any(), any())
        verify(kafka, never()).publish(any())
    }

    /**
     * 검증 3: 단일 종목 시 staggering 미발생 (index=0 sleep skip) + fetchAndSave 1회 호출.
     * — Thread.sleep 미호출 검증 = 실제 실행 시간이 3500ms보다 훨씬 짧음 (test wall-clock 보호).
     */
    @Test
    fun `pollWatchlistNews - 단일 종목 staggering 미발생`() {
        // given
        val watchlistRepo: WatchlistJpaRepository = mock()
        val instrumentRepo: InstrumentJpaRepository = mock()
        val adapter: NaverNewsAdapter = mock()
        val rateLimiter: NaverNewsRateLimiter = mock()
        val kafka: NewsKafkaProducer = mock()

        whenever(watchlistRepo.findAll()).thenReturn(
            listOf(WatchlistJpaEntity(userId = "u1", code = "005930")),
        )
        whenever(rateLimiter.acquireSlot()).thenReturn(true)
        whenever(instrumentRepo.findById("005930")).thenReturn(
            Optional.of(
                InstrumentJpaEntity(
                    code = "005930",
                    name = "삼성전자",
                    market = "KOSPI",
                    type = "STOCK",
                ),
            ),
        )
        whenever(adapter.fetchAndSave("005930", "삼성전자")).thenReturn(emptyList())

        val scheduler = WatchlistNewsScheduler(watchlistRepo, instrumentRepo, adapter, rateLimiter, kafka)

        // when — Thread.sleep 발생 시 3500ms 소요. wall-clock 1초 미만이면 sleep skip 증명.
        val start = System.currentTimeMillis()
        scheduler.pollWatchlistNews()
        val elapsed = System.currentTimeMillis() - start

        // then
        assertThat(elapsed).isLessThan(1_000L)
        verify(adapter, times(1)).fetchAndSave("005930", "삼성전자")
    }
}
