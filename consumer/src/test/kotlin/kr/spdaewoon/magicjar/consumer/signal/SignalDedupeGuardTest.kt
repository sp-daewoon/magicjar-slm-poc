package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * SignalDedupeGuard 단위 테스트 (ADR 0066 §2-5 E-1 Redis SETNX 30s 검증).
 *
 * 테스트 매트릭스:
 *  1. SETNX 성공 → tryAcquire true (첫 발행)
 *  2. SETNX 실패 → tryAcquire false (중복 차단)
 *  3. Redis 결함 → fail-open true (DB UQ 최종 보호망 정합)
 */
class SignalDedupeGuardTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val guard = SignalDedupeGuard(redisTemplate)

    private val sampleTs = Instant.fromEpochSeconds(1_700_000_000L)

    @Test
    fun `SETNX 성공 시 첫 발행 true 반환`() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true

        val result = guard.tryAcquire(strategyId = 42L, code = "005930", interval = CandleInterval.FIVE_MIN, ts = sampleTs)

        assertThat(result).isTrue
        verify {
            valueOps.setIfAbsent(
                "signal:dedupe:strategy:42:code:005930:interval:5m:ts:1700000000",
                "1",
                Duration.ofSeconds(30),
            )
        }
    }

    @Test
    fun `SETNX 실패 시 중복 차단 false 반환`() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false

        val result = guard.tryAcquire(strategyId = 1L, code = "000660", interval = CandleInterval.FIFTEEN_MIN, ts = sampleTs)

        assertThat(result).isFalse
    }

    @Test
    fun `Redis 결함 시 fail-open true 반환 — DB UQ 최종 보호망 정합`() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } throws RuntimeException("Redis connection refused")

        val result = guard.tryAcquire(strategyId = 1L, code = "000660", interval = CandleInterval.ONE_HOUR, ts = sampleTs)

        assertThat(result).isTrue
    }
}
