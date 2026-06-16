package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

/**
 * CandleQueryService — `?limit=` 사용자 입력 cap 500 → 5000 확장 회귀 가드 (ADR 0093 §3-1 LOCK).
 *
 * Phase 3 (2026-05-20 backend-core):
 *   - maxLimit property 외부화 (ChartCandleProperties.maxResponseLimit default 5000)
 *   - limit.coerceIn(1, maxLimit) clamp 검증
 *   - default limit 600 (controller layer default도 600 정합)
 *
 * 진보 페르소나 (ADR 0087):
 *   - 💡 5000 cap 사용자 입력 그대로 통과 → pageable.pageSize 5000 (DB 위임)
 *   - 🔄 99999 cap 초과 입력 → 5000으로 clamp (방어적 LOCK)
 *   - ✨ 0 입력 → 1로 clamp (최소 안전 LOCK)
 */
class CandleQueryServiceLimitClampTest {
    private fun buildService(
        repository: CandleJpaRepository = mock<CandleJpaRepository>(),
        props: ChartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
    ): CandleQueryService {
        val redisTemplate = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(hashOps.entries(any())).thenReturn(emptyMap())
        whenever(valueOps.get(any())).thenReturn(null)
        return CandleQueryService(
            candleJpaRepository = repository,
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper(),
            chartCandleProperties = props,
        )
    }

    @Test
    fun `maxLimit getter — ChartCandleProperties maxResponseLimit 위임 (default 5000)`() {
        val service = buildService()

        assertThat(service.maxLimit).isEqualTo(5000)
        assertThat(service.defaultLimit).isEqualTo(600)
    }

    @Test
    fun `maxLimit getter — application yml override 3000 시 반영`() {
        val service = buildService(props = ChartCandleProperties(maxResponseLimit = 3000))

        assertThat(service.maxLimit).isEqualTo(3000)
    }

    @Test
    fun `getCandles — limit 5000 사용자 입력 그대로 DB 위임 (clamp 통과)`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 5000)

        // pageable.pageSize 검증 — limit 5000 그대로 DB layer 위임.
        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(5000)
    }

    @Test
    fun `getCandles — limit 99999 cap 초과 → 5000으로 clamp`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 99999)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        // clamp 적용 — 99999 → maxResponseLimit(5000).
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(5000)
    }

    @Test
    fun `getCandles — limit 0 이하 → 1로 clamp (방어적 최소)`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 0)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        // 0 → coerceIn(1, 5000) → 1.
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(1)
    }

    @Test
    fun `getCandles — limit 음수 → 1로 clamp`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = -100)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(1)
    }

    @Test
    fun `getCandles — application yml override 시 max 3000 cap 적용`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service =
            buildService(
                repository = repository,
                props = ChartCandleProperties(maxResponseLimit = 3000),
            )

        // 5000 입력이지만 maxResponseLimit=3000 override → 3000으로 clamp.
        service.getCandles(code = "005930", interval = "1d", before = null, limit = 5000)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(3000)
    }

    @Test
    fun `getCandles 1d 최신 요청은 quote last 등락 필드로 마지막 봉 close와 등락률을 보정한다`() {
        val repository = mock<CandleJpaRepository>()
        val redisTemplate = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(hashOps.entries(any())).thenReturn(emptyMap())
        whenever(valueOps.get("market:quote:last:code:066570")).thenReturn(
            """{"price":"237000","ts":"2026-05-22T10:59:59Z","source":"live-nxt","priceChange":"2000","changeRate":"0.85","changeSign":"2"}""",
        )
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(
            listOf(
                candle(ts = "2026-05-21T15:00:00Z", open = 235_000, high = 254_500, low = 230_500, close = 237_500, volume = 5_200_571L),
                candle(ts = "2026-05-20T15:00:00Z", open = 185_200, high = 195_900, low = 185_200, close = 191_800, volume = 1_982_341L),
            ),
        )
        val service =
            CandleQueryService(
                candleJpaRepository = repository,
                redisTemplate = redisTemplate,
                objectMapper = ObjectMapper(),
                chartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
            )

        val response = service.getCandles(code = "066570", interval = "1d", before = null, limit = 10)
        val last = response.candles.last()

        assertThat(last.close).isEqualTo("237000")
        assertThat(last.previousClose).isEqualTo("235000")
        assertThat(last.change).isEqualTo("2000")
        assertThat(last.changeRate).isEqualTo("0.85")
    }

    @Test
    fun `getCandles 1d 최신 요청은 unseeded current를 무시하고 seeded today OHLCV를 합류한다`() {
        val repository = mock<CandleJpaRepository>()
        val redisTemplate = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get("market:quote:last:code:168360")).thenReturn(null)
        whenever(hashOps.entries("market:candle:current:code:168360:interval:1d")).thenReturn(
            mapOf(
                "ts" to "2026-06-09T15:00:00Z",
                "startTs" to "2026-06-09T15:00:00Z",
                "open" to "20950",
                "high" to "20950",
                "low" to "20950",
                "close" to "20950",
                "volume" to "1",
            ),
        )
        whenever(hashOps.entries("market:candle:session:code:168360:interval:1d")).thenReturn(
            mapOf(
                "ts" to "2026-06-09T15:00:00Z",
                "startTs" to "2026-06-09T15:00:00Z",
                "open" to "18430",
                "high" to "21750",
                "low" to "18430",
                "close" to "20950",
                "volume" to "246000",
                "seeded" to "true",
            ),
        )
        whenever(repository.findByCodeAndIntervalLatest(eq("168360"), eq("1d"), any())).thenReturn(
            listOf(
                candle(ts = "2026-06-08T15:00:00Z", open = 21_300, high = 22_000, low = 21_000, close = 21_450, volume = 900_000L),
            ),
        )
        val service =
            CandleQueryService(
                candleJpaRepository = repository,
                redisTemplate = redisTemplate,
                objectMapper = ObjectMapper(),
                chartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
            )

        val response = service.getCandles(code = "168360", interval = "1d", before = null, limit = 10)
        val last = response.candles.last()

        assertThat(response.candles).hasSize(2)
        assertThat(last.open).isEqualTo("18430")
        assertThat(last.high).isEqualTo("21750")
        assertThat(last.low).isEqualTo("18430")
        assertThat(last.close).isEqualTo("20950")
        assertThat(last.volume).isEqualTo(246_000L)
    }

    @Test
    fun `getCandles 상위봉 최신 요청은 같은 진행 기간이면 quote last로 마지막 close를 보정한다`() {
        val intervals = listOf("1wk", "1mo", "1y")

        for (interval in intervals) {
            val repository = mock<CandleJpaRepository>()
            val redisTemplate = mock<StringRedisTemplate>()
            val hashOps = mock<HashOperations<String, String, String>>()
            val valueOps = mock<ValueOperations<String, String>>()
            whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
            whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
            whenever(hashOps.entries(any())).thenReturn(emptyMap())
            whenever(valueOps.get("market:quote:last:code:066570")).thenReturn(
                """{"price":"293000","ts":"2026-05-29T10:59:54Z","source":"live-nxt","priceChange":"67500","changeRate":"29.93","changeSign":"1"}""",
            )
            whenever(repository.findByCodeAndIntervalLatest(eq("066570"), eq(interval), any())).thenReturn(
                listOf(
                    candle(
                        ts = "2026-05-25T15:00:00Z",
                        open = 241_500,
                        high = 254_000,
                        low = 228_000,
                        close = 240_000,
                        volume = 4_299_799L,
                    ),
                    candle(
                        ts = "2026-05-18T15:00:00Z",
                        open = 240_500,
                        high = 254_500,
                        low = 175_200,
                        close = 184_400,
                        volume = 27_943_337L,
                    ),
                ),
            )
            val service =
                CandleQueryService(
                    candleJpaRepository = repository,
                    redisTemplate = redisTemplate,
                    objectMapper = ObjectMapper(),
                    chartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
                )

            val response = service.getCandles(code = "066570", interval = interval, before = null, limit = 10)
            val last = response.candles.last()

            assertThat(last.close).isEqualTo("293000")
            assertThat(last.high).isEqualTo("293000")
            assertThat(last.previousClose).isEqualTo("184400")
            assertThat(last.change).isEqualTo("108600")
            assertThat(last.changeRate).isEqualTo("58.89")
        }
    }

    @Test
    fun `getCandles 상위봉 최신 요청은 quote last와 다른 기간이면 DB 값을 유지한다`() {
        val repository = mock<CandleJpaRepository>()
        val redisTemplate = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(hashOps.entries(any())).thenReturn(emptyMap())
        whenever(valueOps.get("market:quote:last:code:066570")).thenReturn(
            """{"price":"293000","ts":"2026-05-29T10:59:54Z","source":"live-nxt","priceChange":"67500","changeRate":"29.93","changeSign":"1"}""",
        )
        whenever(repository.findByCodeAndIntervalLatest(eq("066570"), eq("1wk"), any())).thenReturn(
            listOf(
                candle(ts = "2026-05-18T15:00:00Z", open = 240_500, high = 254_500, low = 175_200, close = 184_400, volume = 27_943_337L),
                candle(ts = "2026-05-11T15:00:00Z", open = 154_100, high = 269_500, low = 152_000, close = 238_500, volume = 62_427_168L),
            ),
        )
        val service =
            CandleQueryService(
                candleJpaRepository = repository,
                redisTemplate = redisTemplate,
                objectMapper = ObjectMapper(),
                chartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
            )

        val response = service.getCandles(code = "066570", interval = "1wk", before = null, limit = 10)
        val last = response.candles.last()

        assertThat(last.close).isEqualTo("184400")
        assertThat(last.high).isEqualTo("254500")
    }

    private fun candle(
        ts: String,
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        volume: Long,
    ): CandleJpaEntity =
        CandleJpaEntity(
            code = "TEST",
            interval = "1d",
            ts = Instant.parse(ts),
            openRaw = BigDecimal.valueOf(open.toLong()),
            highRaw = BigDecimal.valueOf(high.toLong()),
            lowRaw = BigDecimal.valueOf(low.toLong()),
            closeRaw = BigDecimal.valueOf(close.toLong()),
            volume = volume,
            source = "KIS",
        )
}
