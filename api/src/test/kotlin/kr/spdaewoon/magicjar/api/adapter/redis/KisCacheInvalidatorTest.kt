package kr.spdaewoon.magicjar.api.adapter.redis

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * KisCacheInvalidator 단위 테스트 — RedisKeyRegistry 표준 KIS key 무효화 가드.
 *
 * 핵심 회귀 가드:
 * - deleteAccessToken 호출 시 표준 KIS token key DEL
 * - RedisKeyRegistry가 KIS standard wire key를 단일 생성한다
 */
class KisCacheInvalidatorTest {
    private val redis: StringRedisTemplate = mock()
    private val sut = KisCacheInvalidator(redis)

    @Test
    fun `registry — KIS token key 는 standard wire format`() {
        assertThat(RedisKeyRegistry.Kis.token("u-001", "REAL"))
            .isEqualTo("kis:oauth:token:user:u-001:account:REAL")
    }

    @Test
    fun `registry — KIS approval key 는 standard wire format`() {
        assertThat(RedisKeyRegistry.Kis.approval("u-001", "REAL"))
            .isEqualTo("kis:ws:approval:user:u-001:account:REAL")
    }

    @Test
    fun `deleteAccessToken — standard token key DEL`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteAccessToken(userId = "u-001", accountType = "REAL")

        verify(redis).delete(eq("kis:oauth:token:user:u-001:account:REAL"))
    }

    @Test
    fun `deleteAccessToken — MOCK accountType standard key DEL`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteAccessToken(userId = "u-001", accountType = "MOCK")

        verify(redis).delete(eq("kis:oauth:token:user:u-001:account:MOCK"))
    }

    @Test
    fun `setKeyChangedLock — 5s TTL`() {
        val ops: ValueOperations<String, String> = mock()
        whenever(redis.opsForValue()).thenReturn(ops)

        sut.setKeyChangedLock(userId = "u-001", accountType = "REAL")

        verify(ops).set(
            eq("kis:credential:changed:user:u-001:account:REAL"),
            eq("1"),
            eq(Duration.ofSeconds(5)),
        )
    }

    @Test
    fun `isKeyChangedLockActive — Redis hasKey true 반환`() {
        whenever(redis.hasKey("kis:credential:changed:user:u-001:account:REAL")).thenReturn(true)

        val active = sut.isKeyChangedLockActive(userId = "u-001", accountType = "REAL")

        assertThat(active).isTrue()
    }

    @Test
    fun `isKeyChangedLockActive — Redis hasKey false 반환`() {
        whenever(redis.hasKey("kis:credential:changed:user:u-001:account:REAL")).thenReturn(false)

        val active = sut.isKeyChangedLockActive(userId = "u-001", accountType = "REAL")

        assertThat(active).isFalse()
    }

    @Test
    fun `deleteApprovalKey — standard approval key DEL`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteApprovalKey(userId = "u-001", accountType = "REAL")

        verify(redis).delete(eq("kis:ws:approval:user:u-001:account:REAL"))
    }
}
