package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyBackfillCampaign — ADR 0118 R-BF 멱등 캠페인 단위테스트.
 *
 * 검증 매트릭스 (PR 본문 evidence):
 *   - ENV off → DISABLED 즉시 종료(가동 0, KIS 콜 0).
 *   - progress skip(멱등) — 이미 완료(row≥cap) 종목 fetch 0.
 *   - listed_at clamp — fetch from = max(today-10y, listed_at).
 *   - 콜 cap 이연 — cap 도달 시 CAP_REACHED, 미처리 종목은 다음 실행 이연.
 *   - throttle/circuit OPEN → 캠페인 run 이월(다음 실행).
 *   - DO NOTHING idempotency — saveAll(과거봉 immutable) 경로 사용.
 *   - range fetch 실패(fail-closed) → 종목 완료 마킹 0 + remaining 유지.
 *
 * 모든 KIS 콜은 [KisDailyCandleRangePort](어댑터 BACKFILL 버킷 고정 — 어댑터 단위테스트에서 별도 검증)으로만 발생.
 */
class MarketDailyBackfillCampaignTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.now(zone)
    private val toDate = today.minusDays(1)

    private fun candle(
        code: String,
        date: LocalDate,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = KtInstant.fromEpochSeconds(date.atStartOfDay(zone).toEpochSecond()),
            openRaw = BigDecimal("1000"),
            highRaw = BigDecimal("1100"),
            lowRaw = BigDecimal("950"),
            closeRaw = BigDecimal("1050"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 10_000L,
            source = "KIS",
        )

    private fun props(
        enabled: Boolean = true,
        nightlyCallCap: Int = 6000,
        completionMinRows: Int = 200,
        historyYears: Int = 10,
        recencyGraceDays: Int = 7,
        noAdvanceCooldownDays: Int = 7,
        // 기존 순차 가정 테스트의 결정성 유지 — concurrency=1·delay=0(완전 직렬). 동시성 회계 테스트만 별도로 높임.
        concurrency: Int = 1,
        launchDelayMs: Long = 0L,
    ) = MarketDailyBackfillProperties(
        enabled = enabled,
        historyYears = historyYears,
        nightlyCallCap = nightlyCallCap,
        completionMinRows = completionMinRows,
        recencyGraceDays = recencyGraceDays,
        noAdvanceCooldownDays = noAdvanceCooldownDays,
        concurrency = concurrency,
        launchDelayMs = launchDelayMs,
    )

    private fun newCampaign(
        properties: MarketDailyBackfillProperties,
        provider: MarketDailyUniverseProvider,
        rangePort: KisDailyCandleRangePort,
        candleRepo: CandleRepository,
        universeRepo: ConsumerMarketDailyUniverseJpaRepository = mockk(relaxed = true),
        throttle: BackfillThrottleGate = passThroughThrottle(),
        audit: ConsumerAuditTrailJpaRepository = mockk(relaxed = true),
        wipeService: MarketDailyWipeService = mockk(relaxed = true),
    ): MarketDailyBackfillCampaign {
        val worker = mockk<CandleBackfillWorker>()
        // sanity check 재사용 — 본 테스트는 통과 candle을 그대로 반환(정합성 검증은 CandleBackfillWorkerTest 책임).
        every { worker.sanityCheck(any(), any()) } answers { firstArg() }
        return MarketDailyBackfillCampaign(
            universeProvider = provider,
            universeRepository = universeRepo,
            kisDailyRangePort = rangePort,
            candleRepository = candleRepo,
            candleBackfillWorker = worker,
            backfillThrottleGate = throttle,
            auditRepository = audit,
            objectMapper = ObjectMapper(),
            properties = properties,
            wipeService = wipeService,
        )
    }

    /**
     * throttle gate — block을 그대로 실행(통과)하는 stub.
     * 캠페인은 동시성 보강(ADR 0118 amend §9-D)으로 [BackfillThrottleGate.runWithCircuitGuard] 사용.
     */
    private fun passThroughThrottle(): BackfillThrottleGate {
        val gate = mockk<BackfillThrottleGate>(relaxed = true)
        every { gate.runWithCircuitGuard<Any?>(any(), any()) } answers { secondArg<() -> Any?>().invoke() }
        return gate
    }

    private fun provider(codes: List<String>): MarketDailyUniverseProvider {
        val p = mockk<MarketDailyUniverseProvider>()
        every { p.resolveCodes() } returns codes
        every { p.count() } returns codes.size.toLong()
        return p
    }

    @Test
    fun `ENV off - DISABLED 즉시 종료 (KIS 콜 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>()
        val campaign = newCampaign(props(enabled = false), provider(listOf("005930")), rangePort, candleRepo)

        val r = campaign.runCampaign()

        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.DISABLED)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `EMPTY_UNIVERSE - 보통주 0종목 (mock 합성 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val campaign = newCampaign(props(), provider(emptyList()), rangePort, mockk(relaxed = true))

        val r = campaign.runCampaign()

        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.EMPTY_UNIVERSE)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `progress skip (멱등) - 이미 완료(row 충분) 종목 fetch 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>()
        // 완료 판정 row≥completionMinRows(200) — 2500 row면 완료.
        every { candleRepo.countByCodeAndInterval("005930", CandleInterval.ONE_DAY) } returns 2500L
        every { candleRepo.findMaxTsByCodeAndInterval("005930", CandleInterval.ONE_DAY) } returns
            toDate.atStartOfDay(zone).toInstant()

        val campaign = newCampaign(props(), provider(listOf("005930")), rangePort, candleRepo)
        val r = campaign.runCampaign()

        assertThat(r.alreadyComplete).isEqualTo(1)
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.COMPLETED)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `listed_at clamp - fetch from = max(today-10y, listed_at)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        // 미완료(row 0).
        every { candleRepo.countByCodeAndInterval("999999", CandleInterval.ONE_DAY) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        // 상장 3년 전 — historyFloor(10년 전)보다 늦으므로 clamp 경계 = listed_at.
        val listedAt = today.minusYears(3)
        every { universeRepo.findListedAtByCode("999999") } returns listedAt

        val fromSlot = slot<LocalDate>()
        every {
            rangePort.fetchDailyCandlesInRange("999999", capture(fromSlot), any())
        } returns listOf(candle("999999", listedAt.plusDays(10)))
        every { candleRepo.saveAll(any()) } returns 1

        val campaign = newCampaign(props(), provider(listOf("999999")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // clamp 경계 = listed_at (historyFloor=today-10y 보다 늦음).
        assertThat(fromSlot.captured).isEqualTo(listedAt)
    }

    @Test
    fun `listed_at null - historyFloor(today-10y) 사용`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode("005930") } returns null

        val fromSlot = slot<LocalDate>()
        every { rangePort.fetchDailyCandlesInRange("005930", capture(fromSlot), any()) } returns emptyList()

        val campaign = newCampaign(props(historyYears = 10), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        assertThat(fromSlot.captured).isEqualTo(today.minusYears(10))
    }

    @Test
    fun `콜 cap 이연 - cap 도달 시 CAP_REACHED + 잔여 종목 다음 실행 이연`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        // 모든 종목 미완료.
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        // 종목당 대량 적재 → 1종목 처리에 estimateCalls(~26콜). cap=10 이면 1종목 후 cap 초과.
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("A", today.minusYears(5)))
        every { candleRepo.saveAll(any()) } returns 1

        val campaign =
            newCampaign(props(nightlyCallCap = 10), provider(listOf("000001", "000002", "000003")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        // cap=10 < estimateCalls 누적 → 1종목 처리 후 cap 도달로 중단.
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.CAP_REACHED)
        assertThat(r.processedSymbols).isEqualTo(1)
        assertThat(r.remainingSymbols).isGreaterThan(0)
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `DO NOTHING idempotency - saveAll(과거봉 immutable) 경로 사용`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("005930", today.minusYears(2)))
        val saveSlot = slot<List<Candle>>()
        every { candleRepo.saveAll(capture(saveSlot)) } returns 1

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // upsertAll(ON CONFLICT DO UPDATE)이 아니라 saveAll(DO NOTHING) 사용 — 과거봉 immutable.
        verify(exactly = 1) { candleRepo.saveAll(any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
        // source 마킹 검증 — 상수 BACKFILL_SOURCE.
        assertThat(saveSlot.captured.first().source).isEqualTo(MarketDailyBackfillCampaign.BACKFILL_SOURCE)
    }

    @Test
    fun `회귀 가드 - BACKFILL_SOURCE 길이 candle source VARCHAR(20) 이내`() {
        // candle.source = VARCHAR(20) (V1__baseline.sql:115). 마킹이 20자를 넘으면
        // PostgreSQL 22001(value too long)로 saveAll 전건 실패 → 0행 적재.
        // 직전 결함 "KIS-MARKET-DAILY-BACKFILL"(25자) 재발 방지.
        assertThat(MarketDailyBackfillCampaign.BACKFILL_SOURCE.length)
            .isLessThanOrEqualTo(MarketDailyBackfillCampaign.SOURCE_MAX_LEN)
        assertThat(MarketDailyBackfillCampaign.SOURCE_MAX_LEN).isEqualTo(20)
    }

    @Test
    fun `회귀 가드 - saveAll 에 적재되는 candle source 가 varchar(20) 위반 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("005930", today.minusYears(2)), candle("005930", today.minusYears(1)))
        val saveSlot = slot<List<Candle>>()
        every { candleRepo.saveAll(capture(saveSlot)) } returns 2

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // 적재되는 모든 candle의 source 가 varchar(20) 한계를 넘지 않음 (전건 길이 위반 0).
        assertThat(saveSlot.captured).isNotEmpty
        assertThat(saveSlot.captured).allSatisfy { c ->
            assertThat(c.source!!.length).isLessThanOrEqualTo(MarketDailyBackfillCampaign.SOURCE_MAX_LEN)
        }
    }

    @Test
    fun `range fetch 실패(fail-closed) - 종목 완료 마킹 0 + failed 집계`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } throws
            KisDailyRangeUnavailableException("KIS rate limit")

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        assertThat(r.failedSymbols).isEqualTo(1)
        assertThat(r.insertedRows).isEqualTo(0L)
        // saveAll 미호출(fetch 실패) — 종목은 미완료로 남아 다음 실행 재시도.
        verify(exactly = 0) { candleRepo.saveAll(any()) }
        assertThat(r.remainingSymbols).isEqualTo(1)
    }

    @Test
    fun `throttle OPEN - 캠페인 run 이월 (다음 실행 이연)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        // throttle gate가 null 반환(circuit OPEN) → 캠페인 신규 launch 중단.
        val throttle = mockk<BackfillThrottleGate>(relaxed = true)
        every { throttle.runWithCircuitGuard<Any?>(any(), any()) } returns null

        val campaign =
            newCampaign(props(), provider(listOf("000001", "000002")), rangePort, candleRepo, universeRepo, throttle = throttle)
        val r = campaign.runCampaign()

        assertThat(r.skippedThrottle).isEqualTo(1)
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.CAP_REACHED)
        // 첫 종목에서 throttle → break. 두 번째 종목 fetch 0.
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `초신규 상장(상장일 가장 늦음) - 거래일 0 종목 완료 간주 fetch 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        // 상장일이 toDate(어제) 이후 — 적재 대상 거래일 0.
        every { universeRepo.findListedAtByCode("777777") } returns today.plusDays(5)

        val campaign = newCampaign(props(), provider(listOf("777777")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
        assertThat(r.processedSymbols).isEqualTo(1)
    }

    // ---- ★ gap-aware 완료 판정 + no-advance cooldown (2026-06-09 hotfix) ----------------------

    @Test
    fun `gap-aware - stale-but-rowful(5000행·max_ts 4개월 전) INCOMPLETE → 재시도(OR 버그 회귀 차단)`() {
        // 036930 재현: 일봉 5,000행(≥ minRows 200) 이나 max_ts=4개월 전(recent=false).
        // 직전 OR 구현은 row 수 만으로 "완료" 오판 → 영구 skip. gap-aware 는 recency 필수 → INCOMPLETE → fetch.
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns 5000L
        // max_ts 4개월 전 — recencyGraceDays(7) 밖 → recent=false.
        val staleMaxTs = today.minusMonths(4).atStartOfDay(zone).toInstant()
        every { candleRepo.findMaxTsByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns staleMaxTs
        every { universeRepo.findListedAtByCode("036930") } returns today.minusYears(15)
        // 재시도 시 KIS 가 새 데이터를 줌(max_ts 전진) — fetch 후 적재.
        val newCandle = candle("036930", today.minusDays(1))
        every { rangePort.fetchDailyCandlesInRange("036930", any(), any()) } returns listOf(newCandle)
        every { candleRepo.saveAll(any()) } returns 1

        val campaign = newCampaign(props(), provider(listOf("036930")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        // OR 버그였다면 alreadyComplete=1·fetch 0 이었을 것. gap-aware 는 fetch 시도함.
        assertThat(r.alreadyComplete).isEqualTo(0)
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange("036930", any(), any()) }
    }

    @Test
    fun `gap-aware - 신규단기상장(거래일 전수 보유·recent) COMPLETE → 매 run 재fetch 안 함`() {
        // 상장 20일 전, clamp 구간 거래일(~14 평일) 전수 보유 + max_ts=어제(recent) → COMPLETE.
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val listedAt = today.minusDays(20)
        every { universeRepo.findListedAtByCode("900900") } returns listedAt
        // 상장~어제 평일 수 전수 보유(coverage ≥ 0.90). rows < minRows(200) 이지만 clamp 전수.
        var expectedWeekdays = 0L
        var cur = listedAt
        while (!cur.isAfter(toDate)) {
            if (cur.dayOfWeek.value <= 5) expectedWeekdays++
            cur = cur.plusDays(1)
        }
        every { candleRepo.countByCodeAndInterval("900900", CandleInterval.ONE_DAY) } returns expectedWeekdays
        every { candleRepo.findMaxTsByCodeAndInterval("900900", CandleInterval.ONE_DAY) } returns
            toDate.atStartOfDay(zone).toInstant()

        val campaign = newCampaign(props(), provider(listOf("900900")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        assertThat(r.alreadyComplete).isEqualTo(1)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `no-advance cooldown - KIS 0전진 시 마킹 + 다음 run 재fetch 안 함(over-fetch 닫기)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val audit = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
        // run1: 미완료(stale). fetch 했으나 max_ts 전진 0(pre==post) → no-advance 마킹.
        val staleMaxTs = today.minusMonths(4).atStartOfDay(zone).toInstant()
        every { candleRepo.countByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns 5000L
        every { candleRepo.findMaxTsByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns staleMaxTs
        every { universeRepo.findListedAtByCode("036930") } returns today.minusYears(15)
        // KIS 가 (이미 보유한) 과거봉만 반환 → saveAll DO NOTHING 0 INSERT, max_ts 불변.
        every { rangePort.fetchDailyCandlesInRange("036930", any(), any()) } returns
            listOf(candle("036930", today.minusMonths(5)))
        every { candleRepo.saveAll(any()) } returns 0
        // cooldown 조회 — run1 시점엔 마킹 없음(null).
        every { audit.findLatestOccurredAt(any(), eq("036930"), any()) } returns null
        val savedActions = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { audit.save(capture(savedActions)) } answers { firstArg() }

        val campaign = newCampaign(props(), provider(listOf("036930")), rangePort, candleRepo, universeRepo, audit = audit)
        campaign.runCampaign()

        // run1: no-advance 마킹 1건(entity_id=code, ≤40).
        val noAdvance = savedActions.filter { it.action == MarketDailyBackfillCampaign.ACTION_NO_ADVANCE }
        assertThat(noAdvance).hasSize(1)
        assertThat(noAdvance.first().entityId).isEqualTo("036930")
        assertThat(noAdvance.first().entityId.length).isLessThanOrEqualTo(40)

        // run2: cooldown 마킹이 어제(7일 cooldown 이내) → fetch skip(over-fetch 닫기).
        every { audit.findLatestOccurredAt(any(), eq("036930"), eq(MarketDailyBackfillCampaign.ACTION_NO_ADVANCE)) } returns
            today.minusDays(1).atStartOfDay(zone).toInstant()
        val r2 = campaign.runCampaign()

        assertThat(r2.coolingDownSymbols).isEqualTo(1)
        // run1 1회만 fetch, run2 는 cooldown skip → 누적 정확히 1회.
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange("036930", any(), any()) }
    }

    @Test
    fun `no-advance cooldown 만료 - cooldownDays 경과 후 재시도(영구 skip 아님)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val audit = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns 5000L
        every { candleRepo.findMaxTsByCodeAndInterval("036930", CandleInterval.ONE_DAY) } returns
            today.minusMonths(4).atStartOfDay(zone).toInstant()
        every { universeRepo.findListedAtByCode("036930") } returns today.minusYears(15)
        every { rangePort.fetchDailyCandlesInRange("036930", any(), any()) } returns emptyList()
        // 직전 마킹이 cooldown(7일) 보다 오래됨(10일 전) → cooldown 만료 → 재시도.
        every { audit.findLatestOccurredAt(any(), eq("036930"), eq(MarketDailyBackfillCampaign.ACTION_NO_ADVANCE)) } returns
            today.minusDays(10).atStartOfDay(zone).toInstant()

        val campaign =
            newCampaign(props(noAdvanceCooldownDays = 7), provider(listOf("036930")), rangePort, candleRepo, universeRepo, audit = audit)
        val r = campaign.runCampaign()

        assertThat(r.coolingDownSymbols).isEqualTo(0)
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange("036930", any(), any()) }
    }

    @Test
    fun `audit entity_id - 캠페인 run audit entity_id ≤40 (varchar(40) overflow 회귀 차단)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val audit = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
        // 완료 종목 1개 → START·RUN_DONE audit 발생(runId ~60자였던 결함 entity_id).
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 5000L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns toDate.atStartOfDay(zone).toInstant()
        val saved = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { audit.save(capture(saved)) } answers { firstArg() }

        val campaign = newCampaign(props(), provider(listOf("005930")), rangePort, candleRepo, universeRepo, audit = audit)
        campaign.runCampaign()

        // 모든 audit row 의 entity_id 가 audit_trail.entity_id VARCHAR(40) 이내(START·RUN_DONE 포함).
        assertThat(saved).isNotEmpty
        assertThat(saved).allSatisfy { row ->
            assertThat(row.entityId.length).isLessThanOrEqualTo(40)
        }
        // START·RUN_DONE 은 짧은 고정 식별자(runId 아님).
        assertThat(saved.map { it.entityId }).contains(MarketDailyBackfillCampaign.CAMPAIGN_ENTITY_ID)
    }

    // ---- ★ MIGRATE 모드 + 동시성 회계 (ADR 0118 amend §9) -------------------------------------

    @Test
    fun `MIGRATE 모드 - wipe 선행 후 재적재 + wipedRows·mode 응답 노출`() {
        val rangePort = mockk<KisDailyCandleRangePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val wipe = mockk<MarketDailyWipeService>()
        every { wipe.wipeDailyFamily(any(), any()) } returns 12_345L
        // 모든 종목 미완료(wipe 직후이므로 row 0).
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("005930", today.minusYears(2)))
        every { candleRepo.saveAll(any()) } returns 1

        val campaign = newCampaign(props(), provider(listOf("005930")), rangePort, candleRepo, universeRepo, wipeService = wipe)
        val r = campaign.runCampaign(MarketDailyBackfillCampaign.BackfillMode.MIGRATE)

        assertThat(r.mode).isEqualTo(MarketDailyBackfillCampaign.BackfillMode.MIGRATE)
        assertThat(r.wipedRows).isEqualTo(12_345L)
        // wipe 선행 1회 + 재적재 fetch 1회.
        verify(exactly = 1) { wipe.wipeDailyFamily(any(), any()) }
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `NORMAL 모드(default) - wipe 절대 호출 0 (기존 멱등 재개 보존)`() {
        val rangePort = mockk<KisDailyCandleRangePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val wipe = mockk<MarketDailyWipeService>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns emptyList()

        val campaign = newCampaign(props(), provider(listOf("005930")), rangePort, candleRepo, universeRepo, wipeService = wipe)
        val r = campaign.runCampaign() // default NORMAL

        assertThat(r.mode).isEqualTo(MarketDailyBackfillCampaign.BackfillMode.NORMAL)
        assertThat(r.wipedRows).isEqualTo(0L)
        // ★ NORMAL 은 절대 wipe 하지 않음.
        verify(exactly = 0) { wipe.wipeDailyFamily(any(), any()) }
    }

    @Test
    fun `동시성 회계 불변식 - permits=4·12종목 처리 시 processed·inserted·calls 합산 일관`() {
        val rangePort = mockk<KisDailyCandleRangePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        val codes = (1..12).map { "%06d".format(it) }
        // 모든 종목 미완료 → 전부 fetch 대상. cap 충분(이연 0).
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(2)
        // 각 종목 1봉 적재(전진).
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } answers {
            listOf(candle(firstArg(), today.minusYears(1)))
        }
        every { candleRepo.saveAll(any()) } returns 1

        // permits=4 동시 + launchDelay 0 — 12종목 동시 처리. callCap 충분.
        val campaign =
            newCampaign(
                props(nightlyCallCap = 999_999, concurrency = 4, launchDelayMs = 0L),
                provider(codes),
                rangePort,
                candleRepo,
                universeRepo,
            )
        val r = campaign.runCampaign()

        // 불변식(원자 카운터 보호): 전 종목 처리 + 각 1봉 적재 + 콜 누락/중복 0.
        // (lost-update 시 processed·inserted 가 12 미만으로 깨짐 — 동시성 회계 회귀 가드.)
        assertThat(r.processedSymbols).isEqualTo(12)
        assertThat(r.insertedRows).isEqualTo(12L)
        assertThat(r.failedSymbols).isEqualTo(0)
        // 정확히 12 종목 fetch — 동시성에서도 종목당 1회(중복 launch 0·skip 0).
        verify(exactly = 12) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }
}
