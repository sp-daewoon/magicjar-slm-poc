package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyNightlySnapshotService — ADR 0118 R2 야간/장전 targetDate snapshot 단위테스트.
 *
 * 검증 매트릭스 (PR 본문 evidence):
 *   - ENV off → DISABLED 즉시 종료(멀티시세 콜 0, upsert 0).
 *   - universe 0건 → EMPTY_UNIVERSE graceful (콜 0).
 *   - 30종목 chunk 분할 — 65종목 → 3 chunk(30+30+5) fetchMultiPrice 호출.
 *   - today upsert 멱등 — upsertAll(ON CONFLICT DO UPDATE) 경로 사용 + ts=today 00:00 KST + J 채널.
 *   - OHLC 결손 quote skip(부분 봉 적재 금지 — mock 합성 0).
 *   - source 마킹 = KIS-MKT-DAILY-SNAP (varchar(20) 안전 길이).
 */
class MarketDailyNightlySnapshotServiceTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.now(zone)
    private val todayTs = KtInstant.fromEpochSeconds(today.atStartOfDay(zone).toEpochSecond())

    private fun quote(
        code: String,
        close: BigDecimal? = BigDecimal("1050"),
        open: BigDecimal? = BigDecimal("1000"),
        high: BigDecimal? = BigDecimal("1100"),
        low: BigDecimal? = BigDecimal("950"),
        volume: Long? = 12_345L,
    ): MultiPriceQuote =
        MultiPriceQuote(
            code = code,
            open = open,
            high = high,
            low = low,
            close = close,
            previousClose = BigDecimal("1040"),
            accumulatedVolume = volume,
            accumulatedTradeAmount = BigDecimal("99999"),
            kospiKosdaqClsName = "KOSPI",
            marketTreatmentClsName = null,
            hourClsCode = null,
        )

    private fun props(
        enabled: Boolean = true,
        chunkSpacingMs: Long = 0L,
    ) = MarketDailySnapshotProperties(enabled = enabled, chunkSpacingMs = chunkSpacingMs)

    private fun newService(
        properties: MarketDailySnapshotProperties,
        provider: MarketDailyUniverseProvider,
        multiPricePort: KisMultiPricePort,
        candleRepo: CandleRepository,
        auditRepo: ConsumerAuditTrailJpaRepository = mockk(relaxed = true),
        rollupRunner: DailyFamilyRollupRunner = mockk(relaxed = true),
    ) = MarketDailyNightlySnapshotService(
        universeProvider = provider,
        kisMultiPricePort = multiPricePort,
        candleRepository = candleRepo,
        auditRepository = auditRepo,
        objectMapper = ObjectMapper(),
        properties = properties,
        dailyFamilyRollupRunner = rollupRunner,
    )

    @Test
    fun `ENV off 면 DISABLED 즉시 종료 — 멀티시세 콜 0 upsert 0`() {
        val provider = mockk<MarketDailyUniverseProvider>(relaxed = true)
        val multiPricePort = mockk<KisMultiPricePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val service = newService(props(enabled = false), provider, multiPricePort, candleRepo)

        val result = service.runSnapshot()

        assertThat(result.status).isEqualTo(MarketDailyNightlySnapshotService.SnapshotStatus.DISABLED)
        verify(exactly = 0) { provider.resolveCodes() }
        verify(exactly = 0) { multiPricePort.fetchMultiPrice(any(), any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `universe 0건이면 EMPTY_UNIVERSE graceful — 콜 0`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns emptyList() }
        val multiPricePort = mockk<KisMultiPricePort>(relaxed = true)
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val service = newService(props(), provider, multiPricePort, candleRepo)

        val result = service.runSnapshot()

        assertThat(result.status).isEqualTo(MarketDailyNightlySnapshotService.SnapshotStatus.EMPTY_UNIVERSE)
        verify(exactly = 0) { multiPricePort.fetchMultiPrice(any(), any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `65종목이면 30 chunk 3건으로 fetchMultiPrice 호출 (30+30+5) — J 채널`() {
        val codes = (1..65).map { "%06d".format(it) }
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns codes }
        val multiPricePort = mockk<KisMultiPricePort>()
        val chunkSlot = mutableListOf<List<String>>()
        val mrktSlot = slot<String>()
        every { multiPricePort.fetchMultiPrice(capture(chunkSlot), capture(mrktSlot)) } answers
            { firstArg<List<String>>().map { quote(it) } }
        val candleRepo = mockk<CandleRepository> { every { upsertAll(any()) } answers { firstArg<List<Candle>>().size } }
        val service = newService(props(), provider, multiPricePort, candleRepo)

        val result = service.runSnapshot()

        assertThat(result.chunks).isEqualTo(3)
        assertThat(chunkSlot.map { it.size }).containsExactly(30, 30, 5)
        assertThat(mrktSlot.captured).isEqualTo(MarketDailyNightlySnapshotService.MRKT_DIV_CODE_J)
        verify(exactly = 3) { multiPricePort.fetchMultiPrice(any(), MarketDailyNightlySnapshotService.MRKT_DIV_CODE_J) }
        assertThat(result.upsertedRows).isEqualTo(65)
        assertThat(result.status).isEqualTo(MarketDailyNightlySnapshotService.SnapshotStatus.COMPLETED)
    }

    @Test
    fun `today upsert 는 upsertAll(DO UPDATE) 경로 + ts=today 00시 KST + source=SNAP`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930") }
        val multiPricePort =
            mockk<KisMultiPricePort> { every { fetchMultiPrice(any(), any()) } returns listOf(quote("005930")) }
        val candleSlot = slot<List<Candle>>()
        val candleRepo =
            mockk<CandleRepository> { every { upsertAll(capture(candleSlot)) } answers { firstArg<List<Candle>>().size } }
        val service = newService(props(), provider, multiPricePort, candleRepo)

        service.runSnapshot()

        // saveAll(DO NOTHING) 이 아니라 upsertAll(DO UPDATE) 만 호출 (권위 후행 교정).
        verify(exactly = 1) { candleRepo.upsertAll(any()) }
        verify(exactly = 0) { candleRepo.saveAll(any()) }
        val candle = candleSlot.captured.single()
        assertThat(candle.code).isEqualTo("005930")
        assertThat(candle.interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(candle.ts).isEqualTo(todayTs)
        assertThat(candle.closeRaw).isEqualByComparingTo(BigDecimal("1050"))
        assertThat(candle.volume).isEqualTo(12_345L)
        assertThat(candle.source).isEqualTo(MarketDailyNightlySnapshotService.SNAPSHOT_SOURCE)
        assertThat(MarketDailyNightlySnapshotService.SNAPSHOT_SOURCE.length)
            .isLessThanOrEqualTo(MarketDailyNightlySnapshotService.SOURCE_MAX_LEN)
    }

    @Test
    fun `today upsert 멱등 — 같은 날 중복 호출 시 행 안 늘고 값만 갱신 (DO UPDATE 시맨틱)`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930") }
        val multiPricePort =
            mockk<KisMultiPricePort> { every { fetchMultiPrice(any(), any()) } returns listOf(quote("005930")) }
        // DO UPDATE 시맨틱 시뮬레이션 — 이미 today row 존재 시 INSERT 아닌 UPDATE(행 수 불변, affected=1 반환).
        val stored = mutableMapOf<Triple<String, CandleInterval, KtInstant>, Candle>()
        val candleRepo =
            mockk<CandleRepository> {
                every { upsertAll(any()) } answers {
                    firstArg<List<Candle>>().forEach { stored[Triple(it.code, it.interval, it.ts)] = it }
                    firstArg<List<Candle>>().size
                }
            }
        val service = newService(props(), provider, multiPricePort, candleRepo)

        service.runSnapshot()
        val afterFirst = stored.size
        service.runSnapshot()
        val afterSecond = stored.size

        // 같은 (code,1d,today) 키라 중복 호출에도 행 수 불변(멱등) — DO UPDATE.
        assertThat(afterFirst).isEqualTo(1)
        assertThat(afterSecond).isEqualTo(1)
        verify(exactly = 2) { candleRepo.upsertAll(any()) }
    }

    @Test
    fun `OHLC 결손 quote 는 skip — 부분 봉 적재 금지 (mock 합성 0)`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930", "000660") }
        val multiPricePort =
            mockk<KisMultiPricePort> {
                every { fetchMultiPrice(any(), any()) } returns
                    listOf(
                        quote("005930"), // 정상
                        quote("000660", close = null), // close 결손 → skip
                    )
            }
        val candleSlot = slot<List<Candle>>()
        val candleRepo =
            mockk<CandleRepository> { every { upsertAll(capture(candleSlot)) } answers { firstArg<List<Candle>>().size } }
        val service = newService(props(), provider, multiPricePort, candleRepo)

        val result = service.runSnapshot()

        assertThat(result.skippedQuotes).isEqualTo(1)
        assertThat(result.candlesMapped).isEqualTo(1)
        assertThat(candleSlot.captured.map { it.code }).containsExactly("005930")
    }

    @Test
    fun `upsert 예외 시 다음 chunk 진행 — graceful (best-effort 야간)`() {
        val codes = (1..60).map { "%06d".format(it) }
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns codes }
        val multiPricePort =
            mockk<KisMultiPricePort> {
                every { fetchMultiPrice(any(), any()) } answers { firstArg<List<String>>().map { quote(it) } }
            }
        var call = 0
        val candleRepo =
            mockk<CandleRepository> {
                every { upsertAll(any()) } answers {
                    call++
                    if (call == 1) throw RuntimeException("DB 일시 오류") else firstArg<List<Candle>>().size
                }
            }
        val service = newService(props(), provider, multiPricePort, candleRepo)

        val result = service.runSnapshot()

        // 1번째 chunk upsert 실패해도 2번째 chunk 진행 — COMPLETED + 2번째분만 upserted.
        assertThat(result.status).isEqualTo(MarketDailyNightlySnapshotService.SnapshotStatus.COMPLETED)
        assertThat(result.chunks).isEqualTo(2)
        assertThat(result.upsertedRows).isEqualTo(30)
    }

    @Test
    fun `toTargetDateCandle — OHLC 전부 있으면 매핑 결손 하나라도 있으면 null`() {
        val service =
            newService(props(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

        assertThat(service.toTargetDateCandle(quote("005930"), todayTs)).isNotNull
        assertThat(service.toTargetDateCandle(quote("005930", open = null), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", high = null), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", low = null), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", close = null), todayTs)).isNull()
        // volume 결손은 0 으로 적재(skip 아님 — 거래대금/전일종가는 검증 보강용).
        val volNull = service.toTargetDateCandle(quote("005930", volume = null), todayTs)
        assertThat(volNull).isNotNull
        assertThat(volNull!!.volume).isEqualTo(0L)
    }

    @Test
    fun `toTargetDateCandle — OHLC 0값 손상 봉 skip (06-12 회귀 fix · 부분 봉 적재 금지)`() {
        val service =
            newService(props(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

        // ★ 06-12 실측 손상 패턴 — open/high/low=0, close 만 정상(멀티시세 inter2_prpr 만 carry).
        //   직전 null-only 가드는 BigDecimal.ZERO(non-null)를 통과시켜 전종목 손상 봉을 적재했다.
        val corrupt =
            quote(
                "403870",
                open = BigDecimal.ZERO,
                high = BigDecimal.ZERO,
                low = BigDecimal.ZERO,
                close = BigDecimal("5000"),
            )
        assertThat(service.toTargetDateCandle(corrupt, todayTs)).isNull()

        // 개별 필드 0 도 손상으로 취급(가격 ≤0 은 물리적으로 불가능 — null 가드와 동일).
        assertThat(service.toTargetDateCandle(quote("005930", open = BigDecimal.ZERO), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", high = BigDecimal.ZERO), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", low = BigDecimal.ZERO), todayTs)).isNull()
        assertThat(service.toTargetDateCandle(quote("005930", close = BigDecimal.ZERO), todayTs)).isNull()
        // 음수도 손상.
        assertThat(service.toTargetDateCandle(quote("005930", low = BigDecimal("-1")), todayTs)).isNull()
    }

    @Test
    fun `runSnapshot — OHL=0 손상 quote 는 전건 skip (upsert 0 · 손상 봉 DB 미적재)`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("403870", "005930") }
        // 06-12 패턴: 한 종목 손상(OHL=0·close 정상) + 한 종목 정상.
        val realPort =
            mockk<KisMultiPricePort> {
                every { fetchMultiPrice(any(), any()) } returns
                    listOf(
                        quote("403870", open = BigDecimal.ZERO, high = BigDecimal.ZERO, low = BigDecimal.ZERO, close = BigDecimal("5000")),
                        quote("005930"), // 정상
                    )
            }
        val candleSlot = slot<List<Candle>>()
        val candleRepo =
            mockk<CandleRepository> { every { upsertAll(capture(candleSlot)) } answers { firstArg<List<Candle>>().size } }
        val service = newService(props(), provider, realPort, candleRepo)

        val result = service.runSnapshot()

        // 손상 봉은 skip(1건), 정상 봉만 적재(1건) — 손상 봉이 DB 에 절대 안 들어감.
        assertThat(result.skippedQuotes).isEqualTo(1)
        assertThat(result.candlesMapped).isEqualTo(1)
        assertThat(candleSlot.captured.map { it.code }).containsExactly("005930")
    }

    @Test
    fun `audit 기록 — START·DONE 액션 저장`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930") }
        val multiPricePort =
            mockk<KisMultiPricePort> { every { fetchMultiPrice(any(), any()) } returns listOf(quote("005930")) }
        val candleRepo = mockk<CandleRepository> { every { upsertAll(any()) } returns 1 }
        val auditSlot = mutableListOf<ConsumerAuditTrailJpaEntity>()
        val auditRepo =
            mockk<ConsumerAuditTrailJpaRepository> { every { save(capture(auditSlot)) } answers { firstArg() } }
        val service = newService(props(), provider, multiPricePort, candleRepo, auditRepo)

        service.runSnapshot()

        val actions = auditSlot.map { it.action }
        assertThat(actions).contains("MARKET_DAILY_SNAPSHOT_START", "MARKET_DAILY_SNAPSHOT_DONE")
        assertThat(auditSlot.all { it.actor == "market-data" }).isTrue
        assertThat(auditSlot.all { it.entityId == MarketDailyNightlySnapshotService.AUDIT_ENTITY_ID }).isTrue
    }
}
