package kr.spdaewoon.magicjar.api.screener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisScreenerCacheAdapter
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration

/**
 * RedisScreenerCacheAdapter 단위 테스트 (ADR 0071 §5 + spec §2-3).
 *
 * 케이스:
 * 1. put — Redis SET 호출 검증 + TTL 30m
 * 2. get HIT — 역직렬화 + cacheHit=true 세팅
 * 3. get MISS — null 반환
 * 4. tryLock SETNX 획득 성공
 * 5. tryLock SETNX 획득 실패 (이미 잠김)
 * 6. releaseLock — Redis DELETE 호출
 */
class RedisScreenerCacheAdapterTest {
    private val redisTemplate: StringRedisTemplate = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var adapter: RedisScreenerCacheAdapter

    @BeforeEach
    fun setUp() {
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        adapter = RedisScreenerCacheAdapter(redisTemplate, objectMapper)
    }

    // ── 테스트 픽스처 ─────────────────────────────────────────────────

    private fun buildResult(cacheHit: Boolean = false): ScreenerRunResult =
        ScreenerRunResult(
            universeSize = 100,
            evaluatedCount = 90,
            matchedCount = 3,
            skippedCount = 10,
            skippedReasons = mapOf("캔들 미수집" to 10),
            matches =
                listOf(
                    ScreenerMatch(
                        instrumentCode = "005930",
                        instrumentName = "삼성전자",
                        rank = 1,
                        score = BigDecimal("0.854321"),
                        breakdown =
                            ScreenerScoreBreakdown(
                                signal = BigDecimal("0.400000"),
                                volume = BigDecimal("0.300000"),
                                atr = BigDecimal("0.180000"),
                                cap = BigDecimal("0.100000"),
                                total = BigDecimal("0.854321"),
                            ),
                        reason = "RSI(14) < 30",
                    ),
                ),
            elapsedMs = 1234L,
            cacheHit = cacheHit,
            cacheKey = "screener:result:dsl:abc:universe:xyz",
        )

    // ── 1. put ────────────────────────────────────────────────────────

    @Test
    fun `put — Redis SET 호출 + TTL 30분 기본값`() {
        val cacheKey = "screener:result:dsl:abc:universe:xyz"
        val result = buildResult()

        adapter.put(cacheKey, result)

        verify(valueOps).set(eq(cacheKey), any<String>(), eq(Duration.ofMinutes(30)))
    }

    @Test
    fun `put — 사용자 지정 TTL 적용`() {
        val cacheKey = "screener:result:dsl:abc:universe:xyz"
        val result = buildResult()
        val customTtl = Duration.ofMinutes(10)

        adapter.put(cacheKey, result, customTtl)

        verify(valueOps).set(eq(cacheKey), any<String>(), eq(customTtl))
    }

    // ── 2. get HIT ────────────────────────────────────────────────────

    @Test
    fun `get HIT — 역직렬화 성공 + cacheHit=true 세팅`() {
        val cacheKey = "screener:result:dsl:abc:universe:xyz"
        val result = buildResult(cacheHit = false)
        val json = objectMapper.writeValueAsString(result)
        whenever(valueOps.get(cacheKey)).thenReturn(json)

        val cached = adapter.get(cacheKey)

        assertThat(cached).isNotNull
        assertThat(cached!!.cacheHit).isTrue()
        assertThat(cached.cacheKey).isEqualTo(cacheKey)
        assertThat(cached.matchedCount).isEqualTo(3)
        assertThat(cached.matches).hasSize(1)
        assertThat(cached.matches[0].instrumentCode).isEqualTo("005930")
    }

    // ── 3. get MISS ───────────────────────────────────────────────────

    @Test
    fun `get MISS — null 반환`() {
        val cacheKey = "screener:result:dsl:unknown:universe:key"
        whenever(valueOps.get(cacheKey)).thenReturn(null)

        val cached = adapter.get(cacheKey)

        assertThat(cached).isNull()
    }

    // ── 4. tryLock SETNX 성공 ─────────────────────────────────────────

    @Test
    fun `tryLock SETNX — 획득 성공 시 true 반환`() {
        val lockKey = "screener:run:user:1:strategy:42:lock"
        whenever(valueOps.setIfAbsent(eq(lockKey), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true)

        val acquired = adapter.tryLock(userId = 1L, strategyId = 42L)

        assertThat(acquired).isTrue()
    }

    // ── 5. tryLock SETNX 실패 ─────────────────────────────────────────

    @Test
    fun `tryLock SETNX — 이미 잠김 시 false 반환`() {
        val lockKey = "screener:run:user:1:strategy:42:lock"
        whenever(valueOps.setIfAbsent(eq(lockKey), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(false)

        val acquired = adapter.tryLock(userId = 1L, strategyId = 42L)

        assertThat(acquired).isFalse()
    }

    // ── 6. releaseLock ────────────────────────────────────────────────

    @Test
    fun `releaseLock — Redis DELETE 호출`() {
        val lockKey = "screener:run:user:1:strategy:42:lock"

        adapter.releaseLock(userId = 1L, strategyId = 42L)

        verify(redisTemplate).delete(lockKey)
    }
}
