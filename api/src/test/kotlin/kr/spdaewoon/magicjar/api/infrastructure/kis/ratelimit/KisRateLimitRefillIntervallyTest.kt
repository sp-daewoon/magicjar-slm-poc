package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR 0116 §2-C (R2) — `refillIntervally` 토큰 회복·burst 억제 행동 검증.
 *
 * RedisKisRateLimiterAdapter 가 `BucketConfiguration.builder().addLimit { capacity(N).refillIntervally(N, 1s) }`
 * 로 발행하는 버킷의 핵심 행동을 동일 설정의 **LocalBucket + 주입 시계(TimeMeter)** 로 재현한다.
 *
 * 검증:
 *   1. `refillIntervally` 는 윈도 **중간**에 부분 회복 0 — idle 후 burst 억제 (refillGreedy 와 대비되는 핵심)
 *   2. 1초 경계 도달 시점에만 capacity 만큼 일괄 회복
 *   3. capacity = refill 이므로 idle 축적 burst 0 (KIS sub-second 한도 추정 보수 정합)
 *
 * 자본 영향 0 — 순수 인메모리 토큰 산술 (KIS·Redis 호출 0).
 */
class KisRateLimitRefillIntervallyTest {
    /** 주입 가능한 가상 시계 — 테스트가 nanos 를 직접 전진시켜 윈도 경계 제어. */
    private class FakeClock(
        private val nanos: AtomicLong = AtomicLong(0L),
    ) : TimeMeter {
        override fun currentTimeNanos(): Long = nanos.get()

        override fun isWallClockBased(): Boolean = false

        fun advance(duration: Duration) = nanos.addAndGet(duration.toNanos())
    }

    private fun newBucket(
        clock: FakeClock,
        capacity: Long = 3L,
    ): Bucket =
        Bucket
            .builder()
            .addLimit { limit ->
                // RedisKisRateLimiterAdapter.bucketConfigFor 와 동일 — capacity == refill, refillIntervally(N, 1s)
                limit.capacity(capacity).refillIntervally(capacity, Duration.ofSeconds(1))
            }.withCustomTimePrecision(clock)
            .build()

    @Test
    fun `refillIntervally - capacity 소진 후 윈도 중간 부분 회복 0 (idle burst 억제)`() {
        val clock = FakeClock()
        val bucket = newBucket(clock, capacity = 3L)

        // 초기 capacity 3 전부 소비
        repeat(3) { assertThat(bucket.tryConsume(1L)).isTrue() }
        assertThat(bucket.tryConsume(1L)).isFalse()

        // 윈도 중간(500ms·900ms)에는 회복 0 — refillGreedy 였다면 100ms당 1토큰씩 부분 회복했을 지점
        clock.advance(Duration.ofMillis(500))
        assertThat(bucket.tryConsume(1L)).isFalse()
        clock.advance(Duration.ofMillis(400)) // 누적 900ms
        assertThat(bucket.tryConsume(1L)).isFalse()
    }

    @Test
    fun `refillIntervally - 1초 경계 도달 시 capacity 일괄 회복`() {
        val clock = FakeClock()
        val bucket = newBucket(clock, capacity = 3L)

        repeat(3) { bucket.tryConsume(1L) }
        assertThat(bucket.tryConsume(1L)).isFalse()

        // 1초 경계 도달 → capacity(3) 일괄 회복
        clock.advance(Duration.ofSeconds(1))
        assertThat(bucket.tryConsume(1L)).isTrue()
        assertThat(bucket.tryConsume(1L)).isTrue()
        assertThat(bucket.tryConsume(1L)).isTrue()
        assertThat(bucket.tryConsume(1L)).isFalse() // 다시 소진
    }

    @Test
    fun `refillIntervally - capacity 와 refill 동일 → idle 축적 burst 0`() {
        val clock = FakeClock()
        val bucket = newBucket(clock, capacity = 3L)

        // 장시간 idle (10초) 경과해도 capacity(3) 상한 — refill 누적 burst 0
        clock.advance(Duration.ofSeconds(10))

        assertThat(bucket.tryConsume(3L)).isTrue() // 정확히 capacity 만큼
        assertThat(bucket.tryConsume(1L)).isFalse() // 초과분 0 (10초 idle 이어도 burst 미발생)
    }
}
