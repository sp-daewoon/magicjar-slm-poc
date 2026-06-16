package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * ReconcileLockAdapter 단위 테스트 (ADR 0070 §5 Redis 분산락 + idempotency 캐시).
 *
 * 3 케이스:
 *  (a) acquireLock 신규 → true (Redis SETNX 통과)
 *  (b) acquireLock 중복 → false (다른 인스턴스 처리 중)
 *  (c) acquireLock Redis 장애 → fail-open true (가용성 우선 — KIS Rate Limit + idempotency cache로 회피)
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class ReconcileLockAdapterTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `(a) acquireLock 신규 — SETNX 통과 시 true`() {
        every { valueOps.setIfAbsent("reconcile:user:U1:lock", "1", Duration.ofMinutes(5)) } returns true
        val adapter = ReconcileLockAdapter(redisTemplate)

        val result = adapter.acquireLock("U1")

        assertThat(result).isTrue()
        verify { valueOps.setIfAbsent("reconcile:user:U1:lock", "1", Duration.ofMinutes(5)) }
    }

    @Test
    fun `(b) acquireLock 중복 — SETNX false 시 false (다른 인스턴스 처리 중)`() {
        every { valueOps.setIfAbsent("reconcile:user:U1:lock", "1", Duration.ofMinutes(5)) } returns false
        val adapter = ReconcileLockAdapter(redisTemplate)

        val result = adapter.acquireLock("U1")

        assertThat(result).isFalse()
    }

    @Test
    fun `(c) acquireLock Redis 장애 — fail-open true 반환 (Rate Limit + cache로 회피)`() {
        every { valueOps.setIfAbsent("reconcile:user:U1:lock", "1", Duration.ofMinutes(5)) } throws
            RedisConnectionFailureException("redis down")
        val adapter = ReconcileLockAdapter(redisTemplate)

        val result = adapter.acquireLock("U1")

        // T-RECON은 자본 보호 path이긴 하나 cron 자체 재진입은 KIS RateLimiter로도 safe → fail-open
        assertThat(result).isTrue()
    }
}
