package kr.spdaewoon.magicjar.api.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisPerformanceCacheAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * RedisPerformanceCacheAdapterTest — 3건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. put TTL 1h — Redis SET 호출 + Duration 1h
 * 2. get HIT — JSON 역직렬화 정합
 * 3. pattern invalidation — userId 전체 perf:* 삭제
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class RedisPerformanceCacheAdapterTest {
    private val redis: StringRedisTemplate = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val sut = RedisPerformanceCacheAdapter(redis, objectMapper)

    private val userId = "user_test_001"
    private val period = "MONTH"
    private val from = LocalDate.of(2026, 5, 1)
    private val to = LocalDate.of(2026, 5, 14)
    private val kst = ZoneId.of("Asia/Seoul")

    private fun makeDto() =
        PerformanceSummaryDto(
            period = period,
            periodStart = from,
            periodEnd = to,
            totalPnl = BigDecimal("500000"),
            mddPct = BigDecimal("5.0000"),
            winRatePct = BigDecimal("70.00"),
            profitLossRatio = BigDecimal("2.0000"),
            tradeCount = 10,
            computedAt = OffsetDateTime.now(kst),
            cacheHit = false,
        )

    @Test
    fun `put TTL 1h — Redis SET 호출 TTL 1h 검증`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)

        sut.putSummary(userId, period, from, to, makeDto())

        val keyCaptor = argumentCaptor<String>()
        val ttlCaptor = argumentCaptor<Duration>()
        verify(valueOps).set(keyCaptor.capture(), any<String>(), ttlCaptor.capture())

        assertThat(keyCaptor.firstValue).startsWith("performance:summary:user:$userId")
        assertThat(ttlCaptor.firstValue).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `get HIT — JSON 역직렬화 정합`() {
        val dto = makeDto()
        val json = objectMapper.writeValueAsString(dto)
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(any<String>())).thenReturn(json)

        val result = sut.getSummaryJson(userId, period, from, to)

        assertThat(result).isNotNull
        val parsed = objectMapper.readValue(result!!, PerformanceSummaryDto::class.java)
        assertThat(parsed.totalPnl).isEqualByComparingTo(BigDecimal("500000"))
    }

    @Test
    fun `pattern invalidation — userId 전체 perf 캐시 삭제`() {
        val keysCaptor = argumentCaptor<String>()
        whenever(redis.keys(keysCaptor.capture())).thenReturn(emptySet())

        sut.invalidateUser(userId)

        val patterns = keysCaptor.allValues
        assertThat(patterns).anyMatch { it.startsWith("performance:summary:user:$userId") }
        assertThat(patterns).anyMatch { it.startsWith("performance:equity:user:$userId") }
    }
}
