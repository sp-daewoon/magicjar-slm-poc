package kr.spdaewoon.magicjar.consumer.application.indicator

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal

/**
 * LiveCandleMerger 단위 테스트 — IndicatorSource 진입 candles 1d Redis 합류 검증.
 *
 * 자본 보호 critical hotfix (2026-05-15) 회귀 방지 — stale DB close 사용으로 BUY/SELL 잘못 발생 risk.
 *
 * 3 케이스:
 *  (a) DB 마지막 row.ts == Redis ts → Redis close/high/low/volume 으로 덮어쓰기 (stale replace)
 *  (b) Redis 부재 (Hash 빈 Map) → DB 그대로 fallback (graceful no-op)
 *  (c) DB 마지막 row.ts < Redis ts → Redis bar 새로 add (오늘 row 없음)
 *
 * AI 호출 0 — T-IND 트랙 정책.
 */
class LiveCandleMergerTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val hashOps = mockk<HashOperations<String, String, String>>()

    init {
        @Suppress("UNCHECKED_CAST")
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
    }

    private val code = "005930"
    private val todayKstMidnight: Instant = Instant.parse("2026-05-15T00:00:00+09:00")
    private val yesterdayKstMidnight: Instant = Instant.parse("2026-05-14T00:00:00+09:00")

    private fun dbCandle(
        ts: Instant,
        close: String,
        high: String = close,
        low: String = close,
    ): Candle =
        Candle(
            id = null,
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = ts,
            openRaw = BigDecimal(close),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "KIS",
        )

    @Test
    fun `(a) DB 마지막 row 가 오늘 ts — Redis 값으로 close·high·low·volume replace`() {
        // given — DB 마지막 1d row 가 오늘 ts (stale close=70000)
        val dbCandles =
            listOf(
                dbCandle(yesterdayKstMidnight, close = "69500"),
                dbCandle(todayKstMidnight, close = "70000"),
            )
        // Redis live today bar — close=71200 (장중 갱신 후)
        every { hashOps.entries("market:candle:session:code:$code:interval:1d") } returns
            mapOf(
                "ts" to todayKstMidnight.toString(),
                "open" to "70000",
                "high" to "71500",
                "low" to "69800",
                "close" to "71200",
                "volume" to "5000",
                "seeded" to "true",
            )
        val merger = LiveCandleMerger(redisTemplate)

        // when
        val merged = merger.merge(dbCandles)

        // then — list 길이 그대로, 마지막 row 가 Redis 값으로 교체
        assertThat(merged).hasSize(2)
        assertThat(merged.first().ts).isEqualTo(yesterdayKstMidnight)
        assertThat(merged.first().closeRaw).isEqualByComparingTo("69500")
        val last = merged.last()
        assertThat(last.ts).isEqualTo(todayKstMidnight)
        assertThat(last.closeRaw).isEqualByComparingTo("71200") // stale 70000 → live 71200 replaced
        assertThat(last.highRaw).isEqualByComparingTo("71500")
        assertThat(last.lowRaw).isEqualByComparingTo("69800")
        assertThat(last.volume).isEqualTo(5_000L)
        assertThat(last.code).isEqualTo(code)
        assertThat(last.interval).isEqualTo(CandleInterval.ONE_DAY)
    }

    @Test
    fun `(b) Redis 부재 (Hash 빈 Map) — DB 그대로 fallback`() {
        // given — DB 마지막 1d row 가 오늘 ts (stale close)
        val dbCandles =
            listOf(
                dbCandle(yesterdayKstMidnight, close = "69500"),
                dbCandle(todayKstMidnight, close = "70000"),
            )
        // Redis 부재 (KIS WS 연결 끊김 또는 미 갱신 상태)
        every { hashOps.entries("market:candle:session:code:$code:interval:1d") } returns emptyMap()
        val merger = LiveCandleMerger(redisTemplate)

        // when
        val merged = merger.merge(dbCandles)

        // then — DB 원본 그대로 (graceful fallback)
        assertThat(merged).isEqualTo(dbCandles)
        assertThat(merged.last().closeRaw).isEqualByComparingTo("70000")
    }

    @Test
    fun `(c) DB 마지막 row 가 어제 ts — 오늘 Redis bar 새로 add`() {
        // given — DB 마지막 1d row 가 어제 (오늘 row 미생성 — DailyCloseFinalizer 20:00 INSERT 전)
        val dbCandles =
            listOf(
                dbCandle(yesterdayKstMidnight, close = "69500"),
            )
        // Redis live today bar 존재 — 장중
        every { hashOps.entries("market:candle:session:code:$code:interval:1d") } returns
            mapOf(
                "ts" to todayKstMidnight.toString(),
                "open" to "70000",
                "high" to "71500",
                "low" to "69800",
                "close" to "71200",
                "volume" to "5000",
                "seeded" to "true",
            )
        val merger = LiveCandleMerger(redisTemplate)

        // when
        val merged = merger.merge(dbCandles)

        // then — list 길이 +1, 마지막 row 가 오늘 Redis bar
        assertThat(merged).hasSize(2)
        assertThat(merged.first().ts).isEqualTo(yesterdayKstMidnight)
        val last = merged.last()
        assertThat(last.ts).isEqualTo(todayKstMidnight)
        assertThat(last.closeRaw).isEqualByComparingTo("71200")
        assertThat(last.volume).isEqualTo(5_000L)
        assertThat(last.interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(last.code).isEqualTo(code)
    }

    @Test
    fun `(d) seeded 마커 없는 1d Redis bar는 tick-only 합성으로 보고 합류하지 않는다`() {
        val dbCandles =
            listOf(
                dbCandle(yesterdayKstMidnight, close = "69500"),
            )
        every { hashOps.entries("market:candle:session:code:$code:interval:1d") } returns
            mapOf(
                "ts" to todayKstMidnight.toString(),
                "open" to "71200",
                "high" to "71200",
                "low" to "71200",
                "close" to "71200",
                "volume" to "100",
            )
        val merger = LiveCandleMerger(redisTemplate)

        val merged = merger.merge(dbCandles)

        assertThat(merged).isEqualTo(dbCandles)
    }
}
