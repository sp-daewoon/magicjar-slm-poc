package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyDateRepairService — ADR 0118 R-BF amend 특정 날짜 1d 정밀 복구 단위테스트.
 *
 * 검증 매트릭스 (PR 본문 evidence):
 *   - ENV off → DISABLED 즉시 종료(KIS 콜 0).
 *   - EMPTY_UNIVERSE → 가동 0(mock 합성 0).
 *   - 손상 row 가 KIS 정상값으로 upsert 교정(1d 1봉만) + 정확 ts·source 검증.
 *   - 1d만 건드림 — 다른 날짜·다른 interval DELETE/upsert 0 (정확 키 매칭).
 *   - KIS 미보유(0건)·여전히 0값 → 손상 row 정확 ts DELETE(손상 잔존 금지, mock 합성 0).
 *   - 대상 날짜 소속 주/월/년봉 재롤업 호출.
 *   - KIS unavailable(fail-closed) → 손상 row 보존(잘못 삭제 0) + failed 집계.
 */
class MarketDailyDateRepairServiceTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val targetDate: LocalDate = LocalDate.of(2026, 6, 12)
    private val targetTs: Instant = targetDate.atStartOfDay(zone).toInstant()

    private fun candle(
        code: String,
        date: LocalDate,
        open: String = "1000",
        high: String = "1100",
        low: String = "950",
        close: String = "1050",
        volume: Long = 10_000L,
        source: String = "KIS",
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = KtInstant.fromEpochSeconds(date.atStartOfDay(zone).toEpochSecond()),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = volume,
            source = source,
        )

    private fun props(
        enabled: Boolean = true,
        concurrency: Int = 1,
        launchDelayMs: Long = 0L,
    ) = MarketDailyBackfillProperties(
        enabled = enabled,
        concurrency = concurrency,
        launchDelayMs = launchDelayMs,
    )

    private fun passThroughThrottle(): BackfillThrottleGate {
        val gate = mockk<BackfillThrottleGate>(relaxed = true)
        every { gate.runWithCircuitGuard<Any?>(any(), any()) } answers { secondArg<() -> Any?>().invoke() }
        return gate
    }

    private fun circuitOpenThrottle(): BackfillThrottleGate {
        val gate = mockk<BackfillThrottleGate>(relaxed = true)
        every { gate.runWithCircuitGuard<Any?>(any(), any()) } returns null
        return gate
    }

    private fun provider(codes: List<String>): MarketDailyUniverseProvider {
        val p = mockk<MarketDailyUniverseProvider>()
        every { p.resolveCodes() } returns codes
        every { p.count() } returns codes.size.toLong()
        return p
    }

    private fun noopRollup(): DailyFamilyRollupRunner {
        val r = mockk<DailyFamilyRollupRunner>()
        every { r.rollupDetailed(any(), any()) } returns emptyMap()
        return r
    }

    private fun newService(
        properties: MarketDailyBackfillProperties,
        prov: MarketDailyUniverseProvider,
        rangePort: KisDailyCandleRangePort,
        candleRepo: CandleRepository,
        rollup: DailyFamilyRollupRunner = noopRollup(),
        throttle: BackfillThrottleGate = passThroughThrottle(),
        audit: ConsumerAuditTrailJpaRepository = mockk(relaxed = true),
    ): MarketDailyDateRepairService =
        MarketDailyDateRepairService(
            universeProvider = prov,
            kisDailyRangePort = rangePort,
            candleRepository = candleRepo,
            rollupRunner = rollup,
            backfillThrottleGate = throttle,
            auditRepository = audit,
            objectMapper = ObjectMapper(),
            properties = properties,
        )

    @Test
    fun `ENV off - DISABLED 즉시 종료 (KIS 콜 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val svc = newService(props(enabled = false), provider(listOf("005930")), rangePort, candleRepo)

        val r = svc.repairDate(targetDate)

        assertThat(r.status).isEqualTo(MarketDailyDateRepairService.RepairStatus.DISABLED)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `EMPTY_UNIVERSE - 보통주 0종목 (mock 합성 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val svc = newService(props(), provider(emptyList()), rangePort, mockk(relaxed = true))

        val r = svc.repairDate(targetDate)

        assertThat(r.status).isEqualTo(MarketDailyDateRepairService.RepairStatus.EMPTY_UNIVERSE)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `손상 row 교정 - KIS 정상값으로 대상 날짜 1d 1봉만 upsert(DO UPDATE)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        // KIS daily range [date,date] 정상 봉 반환(OHLC 전부 >0).
        every { rangePort.fetchDailyCandlesInRange("005930", targetDate, targetDate) } returns
            listOf(candle("005930", targetDate, open = "70000", high = "71000", low = "69500", close = "70500", volume = 5_000_000L))
        val upsertSlot = slot<List<Candle>>()
        every { candleRepo.upsertAll(capture(upsertSlot)) } returns 1

        val svc = newService(props(), provider(listOf("005930")), rangePort, candleRepo)
        val r = svc.repairDate(targetDate)

        assertThat(r.status).isEqualTo(MarketDailyDateRepairService.RepairStatus.COMPLETED)
        assertThat(r.repairedSymbols).isEqualTo(1)
        assertThat(r.upsertedRows).isEqualTo(1L)
        assertThat(r.deletedCorruptRows).isEqualTo(0L)
        // upsert(DO UPDATE) 경로 — 손상 row 덮음. DELETE 미발생.
        verify(exactly = 1) { candleRepo.upsertAll(any()) }
        verify(exactly = 0) { candleRepo.deleteByCodeAndIntervalAndTs(any(), any(), any()) }
        verify(exactly = 0) { candleRepo.saveAll(any()) }

        // 정확히 1봉만 upsert + 대상 날짜 ts + 1d interval + 정상 OHLC.
        assertThat(upsertSlot.captured).hasSize(1)
        val c = upsertSlot.captured.first()
        assertThat(c.interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(c.ts).isEqualTo(KtInstant.fromEpochSeconds(targetTs.epochSecond))
        assertThat(c.openRaw).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(c.source).isEqualTo(MarketDailyBackfillCampaign.BACKFILL_SOURCE)
    }

    @Test
    fun `1d만 건드림 - KIS range가 인접일 포함해도 대상 날짜 1봉만 upsert (다른 날짜 무영향)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        // KIS 가 대상일 + 인접일을 함께 반환해도 대상 날짜 ts 1봉만 교정 대상.
        every { rangePort.fetchDailyCandlesInRange("005930", targetDate, targetDate) } returns
            listOf(
                candle("005930", targetDate.minusDays(1), open = "60000"),
                candle("005930", targetDate, open = "70000"),
                candle("005930", targetDate.plusDays(1), open = "80000"),
            )
        val upsertSlot = slot<List<Candle>>()
        every { candleRepo.upsertAll(capture(upsertSlot)) } returns 1

        val svc = newService(props(), provider(listOf("005930")), rangePort, candleRepo)
        svc.repairDate(targetDate)

        // 정확히 대상 날짜 1봉만(인접일 2봉 제외).
        assertThat(upsertSlot.captured).hasSize(1)
        assertThat(upsertSlot.captured.first().ts).isEqualTo(KtInstant.fromEpochSeconds(targetTs.epochSecond))
        assertThat(upsertSlot.captured.first().openRaw).isEqualByComparingTo(BigDecimal("70000"))
        // DELETE 는 인접일·다른 interval 어디에도 호출 0.
        verify(exactly = 0) { candleRepo.deleteByCodeAndIntervalAndTs(any(), any(), any()) }
    }

    @Test
    fun `KIS 미보유(0건) - 손상 row 정확 ts DELETE (손상 잔존 금지·mock 합성 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        // KIS 가 대상 날짜 0건 반환(무거래·거래정지) — 정상값으로 덮을 수 없음 → DELETE.
        every { rangePort.fetchDailyCandlesInRange("123456", targetDate, targetDate) } returns emptyList()
        every { candleRepo.deleteByCodeAndIntervalAndTs("123456", CandleInterval.ONE_DAY, targetTs) } returns 1

        val svc = newService(props(), provider(listOf("123456")), rangePort, candleRepo)
        val r = svc.repairDate(targetDate)

        assertThat(r.deletedCorruptRows).isEqualTo(1L)
        assertThat(r.repairedSymbols).isEqualTo(0)
        // 정확 ts·1d 1행 DELETE — upsert 미발생(가짜값 채우기 0).
        verify(exactly = 1) { candleRepo.deleteByCodeAndIntervalAndTs("123456", CandleInterval.ONE_DAY, targetTs) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `여전히 0값 - KIS 봉이 OHL ≤0 이면 upsert 아니라 DELETE`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        // KIS 가 봉을 줬으나 open/high/low ≤0(여전히 손상값) — upsert 로 덮으면 손상 전파 → DELETE.
        every { rangePort.fetchDailyCandlesInRange("123456", targetDate, targetDate) } returns
            listOf(candle("123456", targetDate, open = "0", high = "0", low = "0", close = "70500", volume = 0L))
        every { candleRepo.deleteByCodeAndIntervalAndTs("123456", CandleInterval.ONE_DAY, targetTs) } returns 1

        val svc = newService(props(), provider(listOf("123456")), rangePort, candleRepo)
        val r = svc.repairDate(targetDate)

        assertThat(r.deletedCorruptRows).isEqualTo(1L)
        verify(exactly = 1) { candleRepo.deleteByCodeAndIntervalAndTs("123456", CandleInterval.ONE_DAY, targetTs) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `대상 날짜 소속 주월년봉 재롤업 호출 - 손상 전파 교정`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        every { rangePort.fetchDailyCandlesInRange("005930", targetDate, targetDate) } returns
            listOf(candle("005930", targetDate, open = "70000"))
        every { candleRepo.upsertAll(any()) } returns 1
        val rollup = mockk<DailyFamilyRollupRunner>()
        every { rollup.rollupDetailed("005930", null) } returns
            mapOf(
                CandleInterval.ONE_WEEK.code to DailyFamilyRollupRunner.IntervalRollupResult(2, null),
                CandleInterval.ONE_MONTH.code to DailyFamilyRollupRunner.IntervalRollupResult(1, null),
                CandleInterval.ONE_YEAR.code to DailyFamilyRollupRunner.IntervalRollupResult(1, null),
            )

        val svc = newService(props(), provider(listOf("005930")), rangePort, candleRepo, rollup = rollup)
        val r = svc.repairDate(targetDate)

        // full-regen(recentDays=null) 롤업 1회 + 집계 노출.
        verify(exactly = 1) { rollup.rollupDetailed("005930", null) }
        assertThat(r.rollupWeeklyUpserted).isEqualTo(2L)
        assertThat(r.rollupMonthlyUpserted).isEqualTo(1L)
        assertThat(r.rollupYearlyUpserted).isEqualTo(1L)
    }

    @Test
    fun `KIS unavailable(fail-closed) - 손상 row 보존(잘못 삭제 0) + failed 집계`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        every { rangePort.fetchDailyCandlesInRange("005930", targetDate, targetDate) } throws
            KisDailyRangeUnavailableException("KIS rate limit")

        val svc = newService(props(), provider(listOf("005930")), rangePort, candleRepo)
        val r = svc.repairDate(targetDate)

        assertThat(r.failedSymbols).isEqualTo(1)
        assertThat(r.repairedSymbols).isEqualTo(0)
        // ★ KIS 실패 시 손상 row 를 잘못 삭제하면 안 됨(다음 실행 재시도) — DELETE·upsert 0.
        verify(exactly = 0) { candleRepo.deleteByCodeAndIntervalAndTs(any(), any(), any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `circuit OPEN - 손상 row 보존 + failed 집계(다음 실행 이연)`() {
        val rangePort = mockk<KisDailyCandleRangePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)

        val svc =
            newService(props(), provider(listOf("005930")), rangePort, candleRepo, throttle = circuitOpenThrottle())
        val r = svc.repairDate(targetDate)

        assertThat(r.failedSymbols).isEqualTo(1)
        // circuit OPEN → fetch 자체 0 + 손상 row 보존(잘못 삭제 0).
        verify(exactly = 0) { candleRepo.deleteByCodeAndIntervalAndTs(any(), any(), any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `전종목 - 정상·미보유 혼재 시 집계 정확 (병렬 회계 일관)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val codes = (1..10).map { "%06d".format(it) }
        // 짝수 코드 = 정상 봉 upsert, 홀수 코드 = KIS 0건 → DELETE.
        codes.forEachIndexed { idx, code ->
            if (idx % 2 == 0) {
                every { rangePort.fetchDailyCandlesInRange(code, targetDate, targetDate) } returns
                    listOf(candle(code, targetDate, open = "1000"))
            } else {
                every { rangePort.fetchDailyCandlesInRange(code, targetDate, targetDate) } returns emptyList()
                every { candleRepo.deleteByCodeAndIntervalAndTs(code, CandleInterval.ONE_DAY, targetTs) } returns 1
            }
        }
        every { candleRepo.upsertAll(any()) } returns 1

        val svc =
            newService(props(concurrency = 4, launchDelayMs = 0L), provider(codes), rangePort, candleRepo)
        val r = svc.repairDate(targetDate)

        // 5 정상 upsert + 5 DELETE — 병렬에서도 누락·중복 0.
        assertThat(r.repairedSymbols).isEqualTo(5)
        assertThat(r.upsertedRows).isEqualTo(5L)
        assertThat(r.deletedCorruptRows).isEqualTo(5L)
        assertThat(r.failedSymbols).isEqualTo(0)
        verify(exactly = 10) { rangePort.fetchDailyCandlesInRange(any(), targetDate, targetDate) }
    }
}
