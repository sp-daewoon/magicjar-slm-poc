package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * OrderIdempotencyGuardImpl 단위 테스트 (ADR 0068 §14-1 + 의제 H-1 + §14-4 risk 매트릭스 LOCK).
 *
 * 3 케이스 매트릭스 (§14-7):
 *  (a) 첫 acquire → true
 *  (b) 중복 acquire → false (Redis SETNX false)
 *  (c) Redis 장애 → 예외 전파 (라우터가 REJECTED 영속)
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class OrderIdempotencyGuardImplTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `(a) 첫 acquire — Redis SETNX true → 정상 true 반환`() {
        every { valueOps.setIfAbsent("idem:order:42:12345678", "1", Duration.ofSeconds(60)) } returns true
        val guard = OrderIdempotencyGuardImpl(redisTemplate)

        val result = guard.acquire("idem:order:42:12345678", ttlSec = 60)

        assertThat(result).isTrue()
    }

    @Test
    fun `(b) 중복 acquire — Redis SETNX false → false 반환 (duplicate skip)`() {
        every { valueOps.setIfAbsent("idem:order:42:12345678", "1", Duration.ofSeconds(60)) } returns false
        val guard = OrderIdempotencyGuardImpl(redisTemplate)

        val result = guard.acquire("idem:order:42:12345678", ttlSec = 60)

        assertThat(result).isFalse()
    }

    @Test
    fun `(c) Redis 장애 — RedisConnectionFailureException 시 예외 전파 (자본 보호 LOCK)`() {
        every { valueOps.setIfAbsent("idem:order:42:12345678", "1", Duration.ofSeconds(60)) } throws
            RedisConnectionFailureException("redis down")
        val guard = OrderIdempotencyGuardImpl(redisTemplate)

        assertThatThrownBy { guard.acquire("idem:order:42:12345678", ttlSec = 60) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("ORDER_IDEMPOTENCY_REDIS_DOWN")
    }
}
