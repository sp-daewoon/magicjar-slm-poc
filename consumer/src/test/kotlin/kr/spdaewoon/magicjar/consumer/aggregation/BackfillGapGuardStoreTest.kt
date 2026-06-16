package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.BackfillGuardProperties
import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * BackfillGapGuardStore 단위 테스트 (rate-limit safety #1·#4 — 2026-06-04 dogfooding fix).
 *
 * 검증:
 *   (1) 연속 total-miss 임계치 도달 시 give-up true
 *   (2) 임계치 미만은 give-up false (재시도 허용)
 *   (3) markUnrecoverable → unrecoverable 키 set + miss 카운터 delete
 *   (4) Redis 장애 시 fail-open (isUnrecoverable false, give-up false)
 */
class BackfillGapGuardStoreTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()

    private fun store(threshold: Long = 2L): BackfillGapGuardStore {
        every { redisTemplate.opsForValue() } returns valueOps
        val props =
            ChartCandleProperties(
                backfillGuard = BackfillGuardProperties(consecutiveTotalMissThreshold = threshold),
            )
        return BackfillGapGuardStore(redisTemplate, props)
    }

    @Test
    fun `(safety-1) 연속 total-miss 임계치 도달 시 give-up true`() {
        val store = store(threshold = 2L)
        every { valueOps.increment(any()) } returns 2L
        every { redisTemplate.expire(any(), any<Duration>()) } returns true

        val shouldGiveUp = store.recordTotalMissAndShouldGiveUp("004090", CandleInterval.ONE_MIN)

        assertThat(shouldGiveUp).isTrue
    }

    @Test
    fun `(safety-1) 임계치 미만은 give-up false`() {
        val store = store(threshold = 3L)
        every { valueOps.increment(any()) } returns 1L
        every { redisTemplate.expire(any(), any<Duration>()) } returns true

        val shouldGiveUp = store.recordTotalMissAndShouldGiveUp("004090", CandleInterval.ONE_MIN)

        assertThat(shouldGiveUp).isFalse
    }

    @Test
    fun `(safety-4) markUnrecoverable — unrecoverable set + miss 카운터 delete`() {
        val store = store()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        every { redisTemplate.delete(any<String>()) } returns true

        store.markUnrecoverable("004090", CandleInterval.ONE_MIN, "CONSECUTIVE_TOTAL_MISS")

        verify { valueOps.set(match { it.contains("unrecoverable") && it.contains("004090") }, "CONSECUTIVE_TOTAL_MISS", any<Duration>()) }
        verify { redisTemplate.delete(match<String> { it.contains("miss") && it.contains("004090") }) }
    }

    @Test
    fun `(safety-4) isUnrecoverable — 키 존재 시 true`() {
        val store = store()
        every { redisTemplate.hasKey(match { it.contains("unrecoverable") }) } returns true

        assertThat(store.isUnrecoverable("004090", CandleInterval.ONE_MIN)).isTrue
    }

    @Test
    fun `Redis 장애 시 fail-open — isUnrecoverable false + give-up false`() {
        val store = store()
        every { redisTemplate.hasKey(any()) } throws RuntimeException("redis down")
        every { valueOps.increment(any()) } throws RuntimeException("redis down")

        assertThat(store.isUnrecoverable("004090", CandleInterval.ONE_MIN)).isFalse
        assertThat(store.recordTotalMissAndShouldGiveUp("004090", CandleInterval.ONE_MIN)).isFalse
    }
}
