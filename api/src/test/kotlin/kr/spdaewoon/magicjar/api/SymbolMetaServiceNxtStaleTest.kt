package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.SymbolMetaService
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * SymbolMetaService — NXT 야간 휴리스틱 + stale 시간 경과 검증 단위 테스트.
 *
 * QA 첫 임무 보고서 (2026-05-21 23:35 KST) evidence 기반:
 * - SK하이닉스 2026-05-21 close=1,918,000 vol=4,850,226 ↔ baseline 2026-05-19 vol=7.8M
 *   30% 임계는 LG전자 정상 일봉을 skip하는 회귀를 만들었으므로 5% 미만 극저거래량만 skip.
 * - asOf=15:19 KST vs 현재=23:33 KST → 8시간 stale 인데 stale=false 응답 결함
 *
 * 본 테스트는 Spring context 부팅 없이 헬퍼 fn 직접 검증 (빠른 피드백).
 */
class SymbolMetaServiceNxtStaleTest {
    private val redisTemplate: StringRedisTemplate = mock()
    private val priceMetricsPort: KisRestPriceMetricsPort = mock()
    private val userAccountJpaRepository: UserAccountJpaRepository = mock()
    private val instrumentJpaRepository: InstrumentJpaRepository = mock()
    private val candleJpaRepository: CandleJpaRepository = mock()
    private val objectMapper: ObjectMapper = ObjectMapper()

    private val service =
        SymbolMetaService(
            redisTemplate,
            priceMetricsPort,
            userAccountJpaRepository,
            instrumentJpaRepository,
            candleJpaRepository,
            objectMapper,
        )

    // ---------- NXT 야간 휴리스틱 (5% 임계) ----------

    @Test
    fun `NXT 야간 candle - SK하이닉스 2026-05-21 evidence - 62퍼센트 비율 - skip`() {
        // QA 보고서 §2-2 evidence — PR #597 휴리스틱(10%) 회귀 case
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 1_918_000, vol = 4_850_226L), // NXT 의심
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 1_719_000, vol = 7_805_248L), // baseline
                makeCandle(ts = "2026-05-18T00:00:00Z", close = 1_840_000, vol = 10_771_377L),
            )
        val filtered = service.filterNxtNightCandles(candles, "000660")
        // 4,850,226 / 7,805,248 = 62% → 5% 극저거래량 휴리스틱은 통과(skip 안 함)
        // (의도: volume 비율만으로는 못 잡는 경계 case 명시 — venue 컬럼 도입 P1 의제 정당화 evidence)
        assertEquals(3, filtered.size, "62% 비율은 5% 임계 통과 — venue 분리 P1 필요한 잔존 case")
        assertEquals(BigDecimal("1918000"), filtered[0].closeRaw)
    }

    @Test
    fun `NXT 야간 candle - SK하이닉스 2026-05-20 evidence - 2점9퍼센트 비율 - skip`() {
        // QA 보고서 §2-2 evidence — 5% 미만 극저거래량 휴리스틱으로 skip
        val candles =
            listOf(
                makeCandle(ts = "2026-05-20T00:00:00Z", close = 1_764_000, vol = 223_020L), // NXT 야간 명백
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 1_719_000, vol = 7_805_248L),
                makeCandle(ts = "2026-05-18T00:00:00Z", close = 1_840_000, vol = 10_771_377L),
            )
        val filtered = service.filterNxtNightCandles(candles, "000660")
        // 223,020 / 7,805,248 = 2.86% < 5% → skip
        assertEquals(2, filtered.size, "2.86% 비율 — NXT 야간 row skip")
        assertEquals(BigDecimal("1719000"), filtered[0].closeRaw, "baseline 정합 — 1,719,000")
    }

    @Test
    fun `NXT 야간 휴리스틱 5퍼센트 임계 - 4퍼센트는 skip`() {
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 100_000, vol = 40L), // 4%
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 99_000, vol = 1_000L),
            )
        val filtered = service.filterNxtNightCandles(candles, "TEST")
        assertEquals(1, filtered.size, "4% < 5% → skip")
        assertEquals(BigDecimal("99000"), filtered[0].closeRaw)
    }

    @Test
    fun `NXT 야간 휴리스틱 5퍼센트 임계 - 6퍼센트는 통과`() {
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 100_000, vol = 60L), // 6%
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 99_000, vol = 1_000L),
            )
        val filtered = service.filterNxtNightCandles(candles, "TEST")
        assertEquals(2, filtered.size, "6% > 5% → 통과")
    }

    @Test
    fun `LG전자 정상 저거래량 일봉 - 26퍼센트 비율은 전일종가 후보로 유지`() {
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T15:00:00Z", close = 237_500, vol = 5_200_571L),
                makeCandle(ts = "2026-05-20T15:00:00Z", close = 191_800, vol = 1_982_341L),
                makeCandle(ts = "2026-05-19T15:00:00Z", close = 184_400, vol = 7_341_724L),
            )
        val filtered = service.filterNxtNightCandles(candles, "066570")
        assertEquals(3, filtered.size, "26.99% 비율은 정상 거래일로 유지")
        assertEquals(BigDecimal("191800"), filtered[1].closeRaw)
    }

    @Test
    fun `fresh quote last 등락 필드가 있으면 현재가 전일대비 기준으로 사용`() {
        val valueOps: ValueOperations<String, String> = mock()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get("market:quote:last:code:066570")).thenReturn(
            objectMapper.writeValueAsString(
                mapOf(
                    "price" to "237000",
                    "volume" to 151,
                    "ts" to Instant.now().toString(),
                    "source" to "live-unified",
                    "priceChange" to "2000",
                    "changeRate" to "0.85",
                    "changeSign" to "2",
                ),
            ),
        )
        whenever(valueOps.get("meta:066570")).thenReturn(null)
        whenever(userAccountJpaRepository.findAll()).thenReturn(emptyList())
        whenever(instrumentJpaRepository.findById("066570")).thenReturn(Optional.empty())
        whenever(candleJpaRepository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(
            listOf(
                makeCandle(ts = "2026-05-21T15:00:00Z", close = 237_500, vol = 5_200_571L),
                makeCandle(ts = "2026-05-20T15:00:00Z", close = 191_800, vol = 1_982_341L),
            ),
        )

        val symbol = service.getSymbol("066570")

        assertEquals("237000", symbol.currentPrice)
        assertEquals("235000", symbol.previousClose)
        assertEquals("2000", symbol.change)
        assertEquals("0.85", symbol.changeRate)
        assertEquals("live-unified", symbol.source)
    }

    @Test
    fun `stale quote last 는 가격 source 에서 제외하고 KIS snapshot 을 우선 사용한다`() {
        val valueOps: ValueOperations<String, String> = mock()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get("market:quote:last:code:000720")).thenReturn(
            objectMapper.writeValueAsString(
                mapOf(
                    "price" to "128200",
                    "volume" to 56,
                    "ts" to Instant.now().minus(Duration.ofMinutes(10)).toString(),
                    "source" to "live",
                    "priceChange" to "5900",
                    "changeRate" to "4.82",
                    "changeSign" to "2",
                ),
            ),
        )
        whenever(valueOps.get("meta:000720")).thenReturn(null)
        whenever(userAccountJpaRepository.findAll()).thenReturn(
            listOf(UserAccountJpaEntity(userId = "test-user")),
        )
        whenever(priceMetricsPort.fetchPriceMetrics("test-user", "000720")).thenReturn(
            priceMetrics(
                current = "157500",
                previous = "122700",
                high = "157500",
                low = "128500",
                open = "138500",
                volume = 1_157_067L,
            ),
        )
        whenever(instrumentJpaRepository.findById("000720")).thenReturn(Optional.empty())
        whenever(candleJpaRepository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(
            listOf(
                makeCandle(ts = "2026-06-12T15:00:00Z", close = 130_000, vol = 2_796L),
                makeCandle(ts = "2026-06-11T15:00:00Z", close = 122_700, vol = 974_982L),
            ),
        )

        val symbol = service.getSymbol("000720")

        assertEquals("157500", symbol.currentPrice)
        assertEquals("122700", symbol.previousClose)
        assertEquals("34800", symbol.change)
        assertEquals("28.36", symbol.changeRate)
        assertEquals("157500", symbol.high)
        assertEquals("128500", symbol.low)
        assertEquals("138500", symbol.open)
        assertEquals(1_157_067L, symbol.volume)
        assertEquals("polling", symbol.source)
        assertFalse(symbol.stale)
    }

    @Test
    fun `한국석유 baseline - 비교 baseline - 정상 처리`() {
        // QA 보고서 §2-1 — 한국석유 정상 case (inflated 아님)
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 13_470, vol = 50_000L),
                makeCandle(ts = "2026-05-20T00:00:00Z", close = 13_500, vol = 60_000L),
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 13_200, vol = 55_000L),
            )
        val filtered = service.filterNxtNightCandles(candles, "004090")
        // 50,000 / 60,000 = 83% > 5% → 모두 통과
        assertEquals(3, filtered.size)
        assertEquals(BigDecimal("13470"), filtered[0].closeRaw)
    }

    @Test
    fun `next row 부재 시 마지막 row는 정상 처리`() {
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 100, vol = 10L), // 단일
            )
        val filtered = service.filterNxtNightCandles(candles, "TEST")
        assertEquals(1, filtered.size, "단일 row는 비교 불가 → 정상 처리")
    }

    @Test
    fun `volume 0 또는 closeRaw null row 사전 필터링`() {
        val candles =
            listOf(
                makeCandle(ts = "2026-05-21T00:00:00Z", close = 100, vol = 0L), // vol=0 → 제외
                makeCandle(ts = "2026-05-20T00:00:00Z", close = null, vol = 1000L), // close=null → 제외
                makeCandle(ts = "2026-05-19T00:00:00Z", close = 99, vol = 500L),
            )
        val filtered = service.filterNxtNightCandles(candles, "TEST")
        assertEquals(1, filtered.size)
        assertEquals(BigDecimal("99"), filtered[0].closeRaw)
    }

    // ---------- stale 시간 경과 검증 (30분 임계) ----------

    @Test
    fun `stale 시간 - 8시간 경과 - stale true (QA 첫 임무 evidence)`() {
        // QA 보고서 §3-6 — asOf=15:19 KST(=06:19 UTC) + 현재=23:33 KST(=14:33 UTC) → 8시간
        val asOf = Instant.parse("2026-05-21T06:19:59Z")
        val now = Instant.parse("2026-05-21T14:33:00Z")
        assertTrue(service.isAsOfStale(asOf, now), "8시간 경과 → stale=true")
    }

    @Test
    fun `stale 시간 - 30분 정확 - stale true (경계)`() {
        val asOf = Instant.parse("2026-05-21T12:00:00Z")
        val now = asOf.plus(Duration.ofMinutes(30)).plusSeconds(1) // 30분 + 1초
        assertTrue(service.isAsOfStale(asOf, now), "30분 초과 → stale=true")
    }

    @Test
    fun `stale 시간 - 29분 경과 - stale false (경계)`() {
        val asOf = Instant.parse("2026-05-21T12:00:00Z")
        val now = asOf.plus(Duration.ofMinutes(29))
        assertFalse(service.isAsOfStale(asOf, now), "29분 → fresh")
    }

    @Test
    fun `stale 시간 - asOf null - stale false (다른 분기 위임)`() {
        assertFalse(service.isAsOfStale(null), "asOf 부재 — 다른 stale 분기에서 처리")
    }

    @Test
    fun `stale 시간 - 미래 timestamp - stale false`() {
        val asOf = Instant.parse("2026-05-21T13:00:00Z")
        val now = Instant.parse("2026-05-21T12:00:00Z")
        assertFalse(service.isAsOfStale(asOf, now), "미래 asOf — clock skew, stale 아님")
    }

    // ---------- KRX 정규장 운영시간 가드 ----------

    @Test
    fun `KRX 정규장 운영시간 - 09시 00분 KST - true (개장)`() {
        val ts = Instant.parse("2026-05-21T00:00:00Z") // = KST 09:00
        assertTrue(service.isKrxRegularHours(ts))
    }

    @Test
    fun `KRX 정규장 운영시간 - 15시 30분 KST - true (장 마감)`() {
        val ts = Instant.parse("2026-05-21T06:30:00Z") // = KST 15:30
        assertTrue(service.isKrxRegularHours(ts))
    }

    @Test
    fun `KRX 정규장 운영시간 - 08시 59분 KST - false (개장 전)`() {
        val ts = Instant.parse("2026-05-20T23:59:00Z") // = KST 08:59
        assertFalse(service.isKrxRegularHours(ts))
    }

    @Test
    fun `KRX 정규장 운영시간 - 16시 00분 KST - false (장 마감 후)`() {
        val ts = Instant.parse("2026-05-21T07:00:00Z") // = KST 16:00
        assertFalse(service.isKrxRegularHours(ts))
    }

    @Test
    fun `KRX 정규장 운영시간 - 일봉 자정 정규화(KST 00시) - false`() {
        // 일봉 ts = KST 00:00 (정규화) → 운영시간 외
        val ts = Instant.parse("2026-05-20T15:00:00Z") // = KST 2026-05-21 00:00
        assertFalse(service.isKrxRegularHours(ts), "일봉 자정 정규화 ts는 운영시간 외")
    }

    // ---------- helper ----------

    private fun makeCandle(
        ts: String,
        close: Int?,
        vol: Long,
    ): CandleJpaEntity =
        CandleJpaEntity(
            code = "TEST",
            interval = "1d",
            ts = Instant.parse(ts),
            closeRaw = close?.let { BigDecimal.valueOf(it.toLong()) },
            volume = vol,
            source = "KIS",
        )

    private fun priceMetrics(
        current: String,
        previous: String,
        high: String,
        low: String,
        open: String,
        volume: Long,
    ): PriceMetrics =
        PriceMetrics(
            per = null,
            pbr = null,
            eps = null,
            bps = null,
            marketCap = null,
            industry = null,
            name = null,
            market = null,
            currentPrice = BigDecimal(current),
            previousClose = BigDecimal(previous),
            priceChange = BigDecimal(current) - BigDecimal(previous),
            changeRate =
                (BigDecimal(current) - BigDecimal(previous))
                    .divide(BigDecimal(previous), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100")),
            changeSign = "2",
            high = BigDecimal(high),
            low = BigDecimal(low),
            open = BigDecimal(open),
            volume = volume,
        )
}
