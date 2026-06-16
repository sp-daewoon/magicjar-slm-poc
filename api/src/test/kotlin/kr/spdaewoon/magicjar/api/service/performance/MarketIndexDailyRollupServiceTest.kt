package kr.spdaewoon.magicjar.api.service.performance

import kr.spdaewoon.magicjar.api.persistence.performance.MarketIndexDailyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.MarketIndexDailyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.trend.MarketIndexDailyOhlcProjection
import kr.spdaewoon.magicjar.api.persistence.trend.MarketIndexSnapshotJpaRepository
import kr.spdaewoon.magicjar.domain.performance.MarketRegime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [MarketIndexDailyRollupService] 단위 테스트 — spec §4-4·§5-1 정합.
 *
 * 검증: OHLC 집계 매핑 · 전일 대비 change_pct · range_pct · 레짐 분류 · UPSERT · snapshot 없는 날 skip.
 * KIS/AI 호출 0 — repository mock 만 사용.
 */
class MarketIndexDailyRollupServiceTest {
    private lateinit var snapshotRepository: MarketIndexSnapshotJpaRepository
    private lateinit var dailyRepository: MarketIndexDailyJpaRepository
    private lateinit var service: MarketIndexDailyRollupService

    private val tradeDate = LocalDate.of(2026, 6, 15)

    @BeforeEach
    fun setup() {
        snapshotRepository = mock()
        dailyRepository = mock()
        service =
            MarketIndexDailyRollupService(
                snapshotRepository = snapshotRepository,
                dailyRepository = dailyRepository,
                highVolThresholdPct = BigDecimal("2.5"),
                trendThresholdPct = BigDecimal("0.6"),
            )
    }

    private fun ohlc(
        open: String,
        high: String,
        low: String,
        close: String,
    ): MarketIndexDailyOhlcProjection =
        object : MarketIndexDailyOhlcProjection {
            override fun getOpen() = BigDecimal(open)

            override fun getHigh() = BigDecimal(high)

            override fun getLow() = BigDecimal(low)

            override fun getClose() = BigDecimal(close)
        }

    @Test
    fun `여러 틱 OHLC 집계 + 전일 close 대비 change_pct + range_pct + UPTREND`() {
        // 첫틱(open) 2500, max 2560, min 2490, 막틱(close) 2540 — 코스피만
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("0001"))
        whenever(snapshotRepository.aggregateDailyOhlc("0001", tradeDate))
            .thenReturn(ohlc(open = "2500.00", high = "2560.00", low = "2490.00", close = "2540.00"))
        // 전일 close = 2520 → change_pct = (2540-2520)/2520*100 = 0.7937% → UPTREND
        whenever(dailyRepository.findMostRecentBefore("0001", tradeDate))
            .thenReturn(dailyEntity(close = "2520.00"))
        whenever(dailyRepository.findByIndexCodeAndTradeDate("0001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = service.rollup(tradeDate)

        assertThat(result).hasSize(1)
        val r = result.first()
        assertThat(r.indexCode).isEqualTo("0001")
        assertThat(r.open).isEqualByComparingTo("2500.00")
        assertThat(r.high).isEqualByComparingTo("2560.00")
        assertThat(r.low).isEqualByComparingTo("2490.00")
        assertThat(r.close).isEqualByComparingTo("2540.00")
        // (2540-2520)/2520*100 = 0.7937 (4 scale HALF_UP)
        assertThat(r.changePct).isEqualByComparingTo("0.7937")
        // (2560-2490)/2500*100 = 2.8000 → range ≥ 2.5 → HIGH_VOL 우선
        assertThat(r.rangePct).isEqualByComparingTo("2.8000")
        assertThat(r.regime).isEqualTo(MarketRegime.HIGH_VOL)
    }

    @Test
    fun `전일 행 없으면 change_pct 는 open 대비 fallback`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("1001"))
        whenever(snapshotRepository.aggregateDailyOhlc("1001", tradeDate))
            .thenReturn(ohlc(open = "800.00", high = "808.00", low = "798.00", close = "806.00"))
        whenever(dailyRepository.findMostRecentBefore("1001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.findByIndexCodeAndTradeDate("1001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = service.rollup(tradeDate)

        val r = result.single()
        // (806-800)/800*100 = 0.7500 (open fallback)
        assertThat(r.changePct).isEqualByComparingTo("0.7500")
        // (808-798)/800*100 = 1.2500 → < 2.5, change 0.75 ≥ 0.6 → UPTREND
        assertThat(r.rangePct).isEqualByComparingTo("1.2500")
        assertThat(r.regime).isEqualTo(MarketRegime.UPTREND)
    }

    @Test
    fun `하락장 DOWNTREND 분류`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("0001"))
        whenever(snapshotRepository.aggregateDailyOhlc("0001", tradeDate))
            .thenReturn(ohlc(open = "2500.00", high = "2505.00", low = "2470.00", close = "2475.00"))
        whenever(dailyRepository.findMostRecentBefore("0001", tradeDate))
            .thenReturn(dailyEntity(close = "2500.00"))
        whenever(dailyRepository.findByIndexCodeAndTradeDate("0001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        val r = service.rollup(tradeDate).single()
        // (2475-2500)/2500*100 = -1.0000 ≤ -0.6 → DOWNTREND, range (2505-2470)/2500*100=1.4 < 2.5
        assertThat(r.changePct).isEqualByComparingTo("-1.0000")
        assertThat(r.regime).isEqualTo(MarketRegime.DOWNTREND)
    }

    @Test
    fun `횡보장 SIDEWAYS 분류`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("0001"))
        whenever(snapshotRepository.aggregateDailyOhlc("0001", tradeDate))
            .thenReturn(ohlc(open = "2500.00", high = "2510.00", low = "2495.00", close = "2505.00"))
        whenever(dailyRepository.findMostRecentBefore("0001", tradeDate))
            .thenReturn(dailyEntity(close = "2502.00"))
        whenever(dailyRepository.findByIndexCodeAndTradeDate("0001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        val r = service.rollup(tradeDate).single()
        // (2505-2502)/2502*100 = 0.1199 < 0.6, range 0.6 < 2.5 → SIDEWAYS
        assertThat(r.regime).isEqualTo(MarketRegime.SIDEWAYS)
    }

    @Test
    fun `snapshot 없는 날은 skip - 행 안 만들고 save 호출 0`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(emptyList())

        val result = service.rollup(tradeDate)

        assertThat(result).isEmpty()
        verify(dailyRepository, never()).save(any())
    }

    @Test
    fun `code 는 있으나 aggregate null 인 지수는 skip`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("0001", "1001"))
        whenever(snapshotRepository.aggregateDailyOhlc("0001", tradeDate)).thenReturn(null)
        whenever(snapshotRepository.aggregateDailyOhlc("1001", tradeDate))
            .thenReturn(ohlc(open = "800.00", high = "802.00", low = "799.00", close = "801.00"))
        whenever(dailyRepository.findMostRecentBefore(eq("1001"), eq(tradeDate))).thenReturn(null)
        whenever(dailyRepository.findByIndexCodeAndTradeDate("1001", tradeDate)).thenReturn(null)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = service.rollup(tradeDate)

        assertThat(result).hasSize(1)
        assertThat(result.single().indexCode).isEqualTo("1001")
    }

    @Test
    fun `기존 행 있으면 DELETE 후 INSERT (멱등 UPSERT)`() {
        whenever(snapshotRepository.findDistinctCodesByTradeDate(tradeDate)).thenReturn(listOf("0001"))
        whenever(snapshotRepository.aggregateDailyOhlc("0001", tradeDate))
            .thenReturn(ohlc(open = "2500.00", high = "2510.00", low = "2495.00", close = "2505.00"))
        whenever(dailyRepository.findMostRecentBefore("0001", tradeDate)).thenReturn(null)
        val existing = dailyEntity(close = "9999.00")
        whenever(dailyRepository.findByIndexCodeAndTradeDate("0001", tradeDate)).thenReturn(existing)
        whenever(dailyRepository.save(any())).thenAnswer { it.arguments[0] }

        service.rollup(tradeDate)

        verify(dailyRepository).delete(existing)
        val captor = argumentCaptor<MarketIndexDailyJpaEntity>()
        verify(dailyRepository).save(captor.capture())
        assertThat(captor.firstValue.close).isEqualByComparingTo("2505.00")
        assertThat(captor.firstValue.indexCode).isEqualTo("0001")
    }

    private fun dailyEntity(close: String): MarketIndexDailyJpaEntity =
        MarketIndexDailyJpaEntity(
            indexCode = "0001",
            tradeDate = tradeDate.minusDays(1),
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            changePct = null,
            rangePct = null,
            regime = null,
        )
}
