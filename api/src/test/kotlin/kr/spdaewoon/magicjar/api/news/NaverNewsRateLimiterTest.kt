package kr.spdaewoon.magicjar.api.news

import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations

/**
 * NaverNewsRateLimiter Redis ZSET 슬라이딩 윈도 단위 테스트 (T-NEWS Phase 2 — ADR 0075 §4-2).
 *
 * 검증 1: 17건 한도 허용 — acquireSlot() true
 * 검증 2: 18번째 false 반환 (분당 17건 hard limit)
 * 검증 3: 한도 내 슬롯 획득 성공
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
class NaverNewsRateLimiterTest {
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var rateLimiter: NaverNewsRateLimiter

    @BeforeEach
    fun setUp() {
        redisTemplate = mock()
        zSetOps = mock()
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        whenever(redisTemplate.expire(any(), any())).thenReturn(true)
        rateLimiter = NaverNewsRateLimiter(redisTemplate)
    }

    /**
     * 검증 1: 16건 이하 → acquireSlot() true
     * ADR 0075 §4-2 — LIMIT_PER_MINUTE = 17
     */
    @Test
    fun `acquireSlotWithLimit - 한도 미만 슬롯 획득 성공`() {
        // given: 현재 카운트 = 0 (한도 17 미만)
        whenever(zSetOps.removeRangeByScore(any(), any(), any())).thenReturn(0L)
        whenever(zSetOps.zCard(NaverNewsRateLimiter.RATE_LIMIT_KEY)).thenReturn(0L)
        whenever(zSetOps.add(any(), any(), any())).thenReturn(true)

        // when
        val result = rateLimiter.acquireSlotWithLimit(17)

        // then
        assertThat(result).isTrue()
    }

    /**
     * 검증 2: 17건 도달 → acquireSlotWithLimit() false (18번째 시도)
     * ADR 0075 §4-2 — 분당 17건 hard limit 초과 시 false
     */
    @Test
    fun `acquireSlotWithLimit - 17건 도달 시 false`() {
        // given: 현재 카운트 = 17 (한도 도달)
        whenever(zSetOps.removeRangeByScore(any(), any(), any())).thenReturn(0L)
        whenever(zSetOps.zCard(NaverNewsRateLimiter.RATE_LIMIT_KEY)).thenReturn(17L)

        // when
        val result = rateLimiter.acquireSlotWithLimit(17)

        // then
        assertThat(result).isFalse()
    }

    /**
     * 검증 3: 한도 1 + 카운트 0 → 슬롯 획득 성공 (경계값 테스트)
     */
    @Test
    fun `acquireSlotWithLimit - 카운트 0 한도 1 성공`() {
        // given
        whenever(zSetOps.removeRangeByScore(any(), any(), any())).thenReturn(0L)
        whenever(zSetOps.zCard(NaverNewsRateLimiter.RATE_LIMIT_KEY)).thenReturn(0L)
        whenever(zSetOps.add(any(), any(), any())).thenReturn(true)

        // when
        val result = rateLimiter.acquireSlotWithLimit(1)

        // then
        assertThat(result).isTrue()
    }

    /**
     * 검증 4: 한도 1 + 카운트 1 → false (경계값 초과)
     */
    @Test
    fun `acquireSlotWithLimit - 한도 1 카운트 1 초과 false`() {
        // given
        whenever(zSetOps.removeRangeByScore(any(), any(), any())).thenReturn(0L)
        whenever(zSetOps.zCard(NaverNewsRateLimiter.RATE_LIMIT_KEY)).thenReturn(1L)

        // when
        val result = rateLimiter.acquireSlotWithLimit(1)

        // then
        assertThat(result).isFalse()
    }
}
