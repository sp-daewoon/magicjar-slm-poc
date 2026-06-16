package kr.spdaewoon.magicjar.consumer.bootstrap

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RedisCandleTodayResetRunner 단위 테스트.
 *
 * 테스트 1: watchlist 종목 존재 → 각 종목 market:candle:session:code:{code}:interval:1d DEL 호출 검증
 * 테스트 2: watchlist 빈 경우 → DEL 호출 없이 graceful return
 */
class RedisCandleTodayResetRunnerTest {
    private val watchlistJpaRepository = mockk<ConsumerWatchlistJpaRepository>()
    private val redisTemplate = mockk<StringRedisTemplate>()

    private val runner =
        RedisCandleTodayResetRunner(
            watchlistJpaRepository = watchlistJpaRepository,
            redisTemplate = redisTemplate,
        )

    private val args = DefaultApplicationArguments()

    @Test
    fun `watchlist 종목 존재 시 각 종목 candle today 1d 키 DEL 호출`() {
        val entries =
            listOf(
                ConsumerWatchlistJpaEntity(id = 1L, userId = "u1", code = "005930"),
                ConsumerWatchlistJpaEntity(id = 2L, userId = "u1", code = "000660"),
                ConsumerWatchlistJpaEntity(id = 3L, userId = "u1", code = "035720"),
            )
        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns entries
        // 첫 번째 종목: 키 존재 → true, 나머지: 키 없음 → false
        every { redisTemplate.delete("market:candle:session:code:005930:interval:1d") } returns true
        every { redisTemplate.delete("market:candle:session:code:000660:interval:1d") } returns false
        every { redisTemplate.delete("market:candle:session:code:035720:interval:1d") } returns false

        runner.run(args)

        verify(exactly = 1) { redisTemplate.delete("market:candle:session:code:005930:interval:1d") }
        verify(exactly = 1) { redisTemplate.delete("market:candle:session:code:000660:interval:1d") }
        verify(exactly = 1) { redisTemplate.delete("market:candle:session:code:035720:interval:1d") }
    }

    @Test
    fun `watchlist 빈 경우 Redis DEL 호출 없이 graceful return`() {
        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns emptyList()

        runner.run(args)

        // DEL 호출 없음 검증
        verify(exactly = 0) { redisTemplate.delete(any<String>()) }
    }
}
