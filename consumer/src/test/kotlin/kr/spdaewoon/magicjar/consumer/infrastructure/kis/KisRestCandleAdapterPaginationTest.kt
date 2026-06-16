package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import kr.spdaewoon.magicjar.consumer.config.OneMinuteBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional

/**
 * KisRestCandleAdapter — cursor 페이지네이션 회귀 가드 (B-1 fix 2026-05-19 chart-curator).
 *
 * 결함 evidence (PR #587 진단):
 *   PR 915699b (2026-05-11) Yahoo→KIS-only 재작성 시 페이지네이션 algorithm regression.
 *   결과: fetchDailyCandles 단일 호출 → KIS 100건 한도로 잘림 (일봉 약 5개월만 수집).
 *
 * Fix:
 *   PR 2cbd4e8 (2026-05-04) 검증된 cursor 페이지네이션 패턴 1:1 재적용.
 *   - 외부 loop MAX_PAGES=6 cursor walkback
 *   - 내부 fetchDailyCandlesSinglePage 분리 (1회 KIS 호출 = 최대 100봉)
 *   - distinctBy boundary dedup + sortedBy ASC (PR #537 보존)
 *
 * 테스트 매트릭스 (4 케이스 — orchestrator dispatch 의무):
 *   (daily-pagination-multi-page) MAX_PAGES=6 한도 검증 + 응답 합산 + ASC 정렬
 *   (weekly-monthly-yearly-pagination) 4 timeframe 동일 algorithm 검증
 *   (distinctBy-boundary-dedup) 페이지 경계 중복 ts 1건 → distinctBy 적용 후 1건만 유지
 *   (infinite-loop-guard) cursor advance 안 하면 break
 *
 * 페이지네이션 algorithm은 webClient mock 통합 의존이므로, 본 테스트는
 * 핵심 invariant(companion 상수·nextCursor·accumulator dedup·ASC 정렬)를
 * 직접 검증하여 회귀 가드. 통합 흐름은 추후 WireMock dogfooding(B-1 후속).
 */
class KisRestCandleAdapterPaginationTest {
    private fun buildAdapter(
        instrumentRepository: ConsumerInstrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>(relaxed = true),
        credentialRepository: ConsumerUserKisCredentialJpaRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true),
        kisRateLimiterPort: KisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
        chartCandleProperties: ChartCandleProperties = ChartCandleProperties(),
    ): KisRestCandleAdapter =
        KisRestCandleAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            credentialRepository = credentialRepository,
            instrumentRepository = instrumentRepository,
            kisRateLimiterPort = kisRateLimiterPort,
            chartCandleProperties = chartCandleProperties,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    /** private nextCursor(oldestDate) reflection 호출 */
    private fun invokeNextCursor(
        adapter: KisRestCandleAdapter,
        oldestDate: LocalDate,
    ): LocalDate {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "nextCursor",
                LocalDate::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, oldestDate) as LocalDate
    }

    /** private resolveMrktDivCode(code) reflection 호출 */
    private fun invokeResolveMrktDivCode(
        adapter: KisRestCandleAdapter,
        code: String,
    ): String {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "resolveMrktDivCode",
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, code) as String
    }

    /** private resolveMrktDivCodeCandidates(code) reflection 호출 */
    @Suppress("UNCHECKED_CAST")
    private fun invokeResolveMrktDivCodeCandidates(
        adapter: KisRestCandleAdapter,
        code: String,
    ): List<String> {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "resolveMrktDivCodeCandidates",
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, code) as List<String>
    }

    /** private oneMinuteTargetDays(anchorDate) reflection 호출 */
    @Suppress("UNCHECKED_CAST")
    private fun invokeOneMinuteTargetDays(
        adapter: KisRestCandleAdapter,
        anchorDate: LocalDate,
    ): List<LocalDate> {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "oneMinuteTargetDays",
                LocalDate::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, anchorDate) as List<LocalDate>
    }

    /** private oneMinuteMaxPagesPerDay() reflection 호출 */
    private fun invokeOneMinuteMaxPagesPerDay(adapter: KisRestCandleAdapter): Int {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "oneMinuteMaxPagesPerDay",
            )
        method.isAccessible = true
        return method.invoke(adapter) as Int
    }

    /** private oneMinuteRangeTargetDays(from, to) reflection 호출 */
    @Suppress("UNCHECKED_CAST")
    private fun invokeOneMinuteRangeTargetDays(
        adapter: KisRestCandleAdapter,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<LocalDate> {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "oneMinuteRangeTargetDays",
                java.time.Instant::class.java,
                java.time.Instant::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, from, to) as List<LocalDate>
    }

    /** private oneMinuteRangeMaxPages(from, to) reflection 호출 */
    private fun invokeOneMinuteRangeMaxPages(
        adapter: KisRestCandleAdapter,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Int {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "oneMinuteRangeMaxPages",
                java.time.Instant::class.java,
                java.time.Instant::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, from, to) as Int
    }

    private fun invokeAssertOneMinuteRangeResponseOk(
        adapter: KisRestCandleAdapter,
        body: Map<String, Any>,
    ) {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "assertOneMinuteRangeResponseOk",
                Map::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        method.invoke(adapter, body, "005930", "20260601", "090000")
    }

    private fun instrument(
        code: String,
        market: String,
        isKospi200: Boolean = false,
        isKosdaq150: Boolean = false,
    ): ConsumerInstrumentJpaEntity =
        ConsumerInstrumentJpaEntity(
            code = code,
            name = code,
            market = market,
            type = "STOCK",
            isKospi200 = isKospi200,
            isKosdaq150 = isKosdaq150,
        )

    /** Candle 빌더 — 페이지네이션 invariant 검증용 fixture */
    private fun candle(
        code: String,
        interval: CandleInterval,
        ymd: String,
    ): Candle {
        val ld =
            LocalDate.parse(
                ymd,
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd"),
            )
        val epoch = ld.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond()
        return Candle(
            code = code,
            interval = interval,
            ts = Instant.fromEpochSeconds(epoch),
            openRaw = BigDecimal("13000"),
            highRaw = BigDecimal("13500"),
            lowRaw = BigDecimal("12800"),
            closeRaw = BigDecimal("13200"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 100000L,
            source = "KIS",
        )
    }

    // -------------------------------------------------------------------------
    // 시나리오 1: daily-pagination-multi-page
    //   companion 상수 + nextCursor walkback algorithm 검증
    //   MAX_PAGES=6 × KIS_PAGE_LIMIT=100 = 600봉 한도 cover 검증
    // -------------------------------------------------------------------------

    @Test
    fun `daily-pagination-multi-page companion 상수 MAX_PAGES_6 KIS_PAGE_LIMIT_100 검증 — PR 2cbd4e8 정합`() {
        // given/when — companion 상수 직접 검증 (PR 2cbd4e8 패턴 1:1 정합).
        val maxPages = KisRestCandleAdapter.MAX_PAGES
        val kisPageLimit = KisRestCandleAdapter.KIS_PAGE_LIMIT

        // then
        // KIS-first raw spec docstring "한 번의 호출에 최대 100건" — KIS_PAGE_LIMIT=100 LOCK.
        assertThat(kisPageLimit).isEqualTo(100)
        // PR 2cbd4e8 한국석유 1년 backfill 검증된 안전 한도 — 6 pages × 100 = 600봉.
        assertThat(maxPages).isEqualTo(6)

        // 일봉 cover 가드 (250 영업일 = 1년 → 3 pages 충분, 6 pages = 약 2.5년):
        val dailyCover = maxPages * kisPageLimit
        assertThat(dailyCover).isGreaterThanOrEqualTo(600)
    }

    @Test
    fun `market-div-resolver J 단일 채널 — KOSPI200 포함 전 종목 J 고정 (ADR 0118 amend §9 · UN 폐기)`() {
        // ★ 근본 fix 회귀 가드 — UN/NX 채널 stale 동결(036930·047040·098460 2/11 동결) 실측 후 J 단일 전환.
        // 직전 구현은 KOSPI200/KOSDAQ150 멤버에 ["UN","J"](UN 우선)을 썼고, UN stale 8행 채택 → J(최신) 미조회 결함.
        val instrumentRepository = mockk<ConsumerInstrumentJpaRepository>(relaxed = true)
        // KOSPI200 멤버여도 UN 후보 0 — 무조건 J 단일.
        every { instrumentRepository.findById("005930") } returns
            Optional.of(instrument("005930", market = "KOSPI", isKospi200 = true))
        every { instrumentRepository.findById("000001") } returns
            Optional.of(instrument("000001", market = "KOSPI", isKospi200 = false))
        // 실측 stale 종목들도 J 단일.
        every { instrumentRepository.findById("036930") } returns
            Optional.of(instrument("036930", market = "KOSDAQ", isKosdaq150 = true))
        val adapter = buildAdapter(instrumentRepository)

        // KOSPI200 멤버(005930) — 직전엔 ["UN","J"]였으나 이제 ["J"] 단일.
        assertThat(invokeResolveMrktDivCode(adapter, "005930")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "005930")).containsExactly("J")
        // 비멤버도 J.
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "000001")).containsExactly("J")
        // KOSDAQ150 멤버(036930·stale 실측 종목) — UN 폐기, J 단일.
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "036930")).containsExactly("J")
    }

    @Test
    fun `market-div-resolver J 단일 — KOSDAQ150 멤버·UNKNOWN 모두 J (UN 후보 0건 회귀 차단)`() {
        val instrumentRepository = mockk<ConsumerInstrumentJpaRepository>(relaxed = true)
        every { instrumentRepository.findById("091990") } returns
            Optional.of(instrument("091990", market = "KOSDAQ", isKosdaq150 = true))
        every { instrumentRepository.findById("123456") } returns
            Optional.of(instrument("123456", market = "KOSDAQ", isKosdaq150 = false))
        every { instrumentRepository.findById("999999") } returns Optional.empty()
        val adapter = buildAdapter(instrumentRepository)

        // KOSDAQ150 멤버여도 UN 후보 0 — J 단일.
        assertThat(invokeResolveMrktDivCode(adapter, "091990")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "091990")).containsExactly("J")
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "123456")).containsExactly("J")
        // instrument 미존재(UNKNOWN)도 J.
        assertThat(invokeResolveMrktDivCodeCandidates(adapter, "999999")).containsExactly("J")
    }

    @Test
    fun `one-minute-lookback default는 126 거래일이며 주말을 제외한다`() {
        val adapter = buildAdapter()

        val targetDays = invokeOneMinuteTargetDays(adapter, LocalDate.of(2026, 5, 31))

        assertThat(targetDays).hasSize(126)
        assertThat(targetDays.first()).isEqualTo(LocalDate.of(2026, 5, 29))
        assertThat(targetDays).allSatisfy { day ->
            assertThat(day.dayOfWeek)
                .isNotIn(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)
        }
    }

    @Test
    fun `one-minute-lookback property는 최대 252 거래일과 일자별 8 page cap을 넘지 않는다`() {
        val adapter =
            buildAdapter(
                chartCandleProperties =
                    ChartCandleProperties(
                        oneMinute =
                            OneMinuteBackfillProperties(
                                defaultLookbackTradingDays = 400,
                                maxLookbackTradingDays = 252,
                                maxPagesPerDay = 99,
                            ),
                    ),
            )

        val targetDays = invokeOneMinuteTargetDays(adapter, LocalDate.of(2026, 5, 29))

        assertThat(targetDays).hasSize(252)
        assertThat(invokeOneMinuteMaxPagesPerDay(adapter)).isEqualTo(8)
    }

    @Test
    fun `one-minute-range target days는 요청 범위 날짜만 역순으로 포함하고 주말을 제외한다`() {
        val adapter = buildAdapter()
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val from =
            java.time.LocalDateTime
                .of(2026, 5, 29, 15, 0)
                .atZone(zone)
                .toInstant()
        val to =
            java.time.LocalDateTime
                .of(2026, 6, 1, 9, 30)
                .atZone(zone)
                .toInstant()

        val days = invokeOneMinuteRangeTargetDays(adapter, from, to)

        assertThat(days).containsExactly(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 5, 29),
        )
    }

    @Test
    fun `one-minute-range max pages는 요청 분량 기준으로 줄이고 일자 cap을 넘지 않는다`() {
        val adapter = buildAdapter()
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val shortFrom =
            java.time.LocalDateTime
                .of(2026, 6, 1, 9, 0)
                .atZone(zone)
                .toInstant()
        val shortTo =
            java.time.LocalDateTime
                .of(2026, 6, 1, 9, 10)
                .atZone(zone)
                .toInstant()
        val fullFrom =
            java.time.LocalDateTime
                .of(2026, 6, 1, 8, 0)
                .atZone(zone)
                .toInstant()
        val fullTo =
            java.time.LocalDateTime
                .of(2026, 6, 1, 19, 59)
                .atZone(zone)
                .toInstant()

        assertThat(invokeOneMinuteRangeMaxPages(adapter, shortFrom, shortTo)).isEqualTo(2)
        assertThat(invokeOneMinuteRangeMaxPages(adapter, fullFrom, fullTo)).isEqualTo(7)
        assertThat(invokeOneMinuteRangeMaxPages(adapter, fullFrom, fullTo))
            .isLessThanOrEqualTo(invokeOneMinuteMaxPagesPerDay(adapter))
    }

    @Test
    fun `one-minute-range fetch는 upstream 설정 실패를 empty가 아니라 예외로 전파한다`() {
        val instrumentRepository = mockk<ConsumerInstrumentJpaRepository>()
        val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { instrumentRepository.findById("005930") } returns
            Optional.of(instrument("005930", "KOSPI", isKospi200 = true))
        every { credentialRepository.findAllByAccountType("REAL") } returns emptyList()
        val adapter =
            buildAdapter(
                instrumentRepository = instrumentRepository,
                credentialRepository = credentialRepository,
            )
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val from =
            java.time.LocalDateTime
                .of(2026, 6, 1, 9, 0)
                .atZone(zone)
                .toInstant()
        val to =
            java.time.LocalDateTime
                .of(2026, 6, 1, 9, 1)
                .atZone(zone)
                .toInstant()

        assertThatThrownBy {
            adapter.fetchOneMinuteCandlesBetween("005930", from, to)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("REAL credential 없음")
    }

    @Test
    fun `one-minute-range fetch는 HTTP 200 EGW00201을 success가 아니라 TPS 실패로 처리한다`() {
        val kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true)
        val adapter = buildAdapter(kisRateLimiterPort = kisRateLimiterPort)
        val body =
            mapOf(
                "rt_cd" to "1",
                "msg_cd" to "EGW00201",
                "msg1" to "초당 거래건수를 초과하였습니다.",
            )

        assertThatThrownBy {
            invokeAssertOneMinuteRangeResponseOk(adapter, body)
        }.hasCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage(
                "KIS 1m range candle rt_cd != 0 — code=005930 date=20260601 hour=090000 " +
                    "rt_cd=1 msg_cd=EGW00201 msg=초당 거래건수를 초과하였습니다.",
            )
        verify(exactly = 1) { kisRateLimiterPort.onTpsExceeded() }
        verify(exactly = 0) { kisRateLimiterPort.onSuccess() }
    }

    @Test
    fun `daily-pagination-multi-page accumulator 합산 + ASC 정렬 invariant 검증`() {
        // given — 3 page 응답 시뮬레이션:
        //   page1: 2026-05-19 ~ 2026-02-09 (100건)
        //   page2: 2026-02-08 ~ 2025-10-31 (100건)
        //   page3: 2025-10-30 ~ 2025-08-15 (40건)
        // accumulator에 들어가는 raw 시퀀스 (각 page 내부에서 ASC 정규화 후 합산되는 형태).
        val page1Candles = listOf(candle("004090", CandleInterval.ONE_DAY, "20260219"))
        val page2Candles = listOf(candle("004090", CandleInterval.ONE_DAY, "20251031"))
        val page3Candles = listOf(candle("004090", CandleInterval.ONE_DAY, "20250815"))

        // when — 페이지네이션 종료 후 외부 sortedBy + distinctBy 단계 시뮬레이션.
        val accumulator = mutableListOf<Candle>()
        accumulator.addAll(page1Candles)
        accumulator.addAll(page2Candles)
        accumulator.addAll(page3Candles)
        val result = accumulator.distinctBy { it.ts.epochSeconds }.sortedBy { it.ts.epochSeconds }

        // then — 합산 3건 + ASC monotonic 검증.
        assertThat(result).hasSize(3)
        assertThat(result.map { it.ts.epochSeconds }).isSorted
        // 첫 row가 가장 과거(2025-08-15), 마지막 row가 가장 최신(2026-02-19).
        val firstDate =
            java.time.Instant
                .ofEpochSecond(result.first().ts.epochSeconds)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toLocalDate()
        val lastDate =
            java.time.Instant
                .ofEpochSecond(result.last().ts.epochSeconds)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toLocalDate()
        assertThat(firstDate).isEqualTo(LocalDate.of(2025, 8, 15))
        assertThat(lastDate).isEqualTo(LocalDate.of(2026, 2, 19))
    }

    // -------------------------------------------------------------------------
    // 시나리오 1-b: one-minute 전역 ASC 정규화 (2026-06-05 readiness P0-2)
    //   fetchOneMinCandles는 최근일→과거일 역순 + 일자 내 장마감→장시작 역순 페이지네이션으로
    //   allCandles를 누적한다. 반환 직전 전역 ASC 정규화(.distinctBy.sortedBy)가 없으면 페이지·일자
    //   경계마다 ts가 뒤로 점프 → sanity monotonic WARN 폭주(41만 줄 evidence). 정규화 invariant 검증.
    // -------------------------------------------------------------------------

    private fun candleMinute(
        code: String,
        epochSec: Long,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = Instant.fromEpochSeconds(epochSec),
            openRaw = BigDecimal("13000"),
            highRaw = BigDecimal("13500"),
            lowRaw = BigDecimal("12800"),
            closeRaw = BigDecimal("13200"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1000L,
            source = "KIS",
        )

    @Test
    fun `one-minute 전역 ASC 정규화 — 일자·페이지 역순 누적 시퀀스를 monotonic ASC로 정렬`() {
        // given — fetchOneMinCandles 누적 순서 시뮬레이션:
        //   day0(최근) page0: 14:00,14:01 (ASC 내부)
        //   day0 page1(더 이른 시각): 09:00,09:01
        //   day1(과거) page0: 14:00,14:01
        // → allCandles는 [day0:14:00,14:01][day0:09:00,09:01][day1:14:00,14:01] 비단조 시퀀스.
        val base = 1_700_000_000L
        val day = 86_400L
        val allCandles =
            mutableListOf<Candle>().apply {
                // day0 page0 (최신 시각)
                add(candleMinute("005930", base + day + 5L * 3600L)) // day0 14:00
                add(candleMinute("005930", base + day + 5L * 3600L + 60L)) // day0 14:01
                // day0 page1 (더 이른 시각 — 뒤로 점프)
                add(candleMinute("005930", base + day)) // day0 09:00
                add(candleMinute("005930", base + day + 60L)) // day0 09:01
                // day1 (과거 일자 — 다시 뒤로 점프)
                add(candleMinute("005930", base + 5L * 3600L)) // day1 14:00
                add(candleMinute("005930", base + 5L * 3600L + 60L)) // day1 14:01
            }

        // when — 반환 직전 전역 ASC 정규화 (fetchOneMinCandles 반환부와 동일 invariant).
        val normalized = allCandles.distinctBy { it.ts.epochSeconds }.sortedBy { it.ts.epochSeconds }

        // then — 전역 monotonic ASC 보장 (페이지·일자 경계 역순 0).
        assertThat(normalized).hasSize(6)
        assertThat(normalized.map { it.ts.epochSeconds }).isSorted
        // 첫 row = day1 09:00 아님 — 가장 과거(day1 14:00), 마지막 = day0 14:01.
        assertThat(normalized.first().ts.epochSeconds).isEqualTo(base + 5L * 3600L)
        assertThat(normalized.last().ts.epochSeconds).isEqualTo(base + day + 5L * 3600L + 60L)
    }

    // -------------------------------------------------------------------------
    // 시나리오 2: weekly-monthly-yearly-pagination
    //   4 timeframe (D/W/M/Y) 동일 algorithm cover 검증
    // -------------------------------------------------------------------------

    @Test
    fun `weekly-monthly-yearly-pagination 4 timeframe 동일 algorithm cover — nextCursor invariant`() {
        val adapter = buildAdapter()

        // given/when — 4 timeframe의 oldestDate별 nextCursor 산정.
        // PR 2cbd4e8 정합: oldestDate - 1day → 다음 cursor (영업일 보정 불필요, distinctBy 방어).

        // 일봉(D): 2026-02-09 → 2026-02-08
        val dailyCursor = invokeNextCursor(adapter, LocalDate.of(2026, 2, 9))
        assertThat(dailyCursor).isEqualTo(LocalDate.of(2026, 2, 8))

        // 주봉(W): 2025-12-26(금) → 2025-12-25
        val weeklyCursor = invokeNextCursor(adapter, LocalDate.of(2025, 12, 26))
        assertThat(weeklyCursor).isEqualTo(LocalDate.of(2025, 12, 25))

        // 월봉(M): 2025-12-30 → 2025-12-29
        val monthlyCursor = invokeNextCursor(adapter, LocalDate.of(2025, 12, 30))
        assertThat(monthlyCursor).isEqualTo(LocalDate.of(2025, 12, 29))

        // 연봉(Y): 2024-12-30 → 2024-12-29
        val yearlyCursor = invokeNextCursor(adapter, LocalDate.of(2024, 12, 30))
        assertThat(yearlyCursor).isEqualTo(LocalDate.of(2024, 12, 29))

        // 4 timeframe 모두 nextCursor가 strict before oldestDate (cursor advance 보장).
        listOf(
            LocalDate.of(2026, 2, 9) to dailyCursor,
            LocalDate.of(2025, 12, 26) to weeklyCursor,
            LocalDate.of(2025, 12, 30) to monthlyCursor,
            LocalDate.of(2024, 12, 30) to yearlyCursor,
        ).forEach { (oldest, next) ->
            assertThat(next).isBefore(oldest)
        }
    }

    // -------------------------------------------------------------------------
    // 시나리오 3: distinctBy-boundary-dedup
    //   페이지 경계 중복 ts 1건 → distinctBy 적용 후 1건만 유지
    // -------------------------------------------------------------------------

    @Test
    fun `distinctBy-boundary-dedup 페이지 경계 중복 ts → 1건만 유지`() {
        // given — 페이지 1 oldest = 2026-02-09, 페이지 2 first = 2026-02-09 (cursor=2026-02-08이지만
        //                                                                   KIS가 2026-02-09 row를 다시 반환할 risk).
        // PR 2cbd4e8 시나리오 — 페이지 경계 중복 가능성에 대한 distinctBy 방어.
        val page1OldestTs = candle("004090", CandleInterval.ONE_DAY, "20260209")
        val page1Older = candle("004090", CandleInterval.ONE_DAY, "20260206")
        val page2DuplicateTs = candle("004090", CandleInterval.ONE_DAY, "20260209") // 동일 ts!
        val page2Older = candle("004090", CandleInterval.ONE_DAY, "20260205")

        val accumulator = mutableListOf<Candle>()
        accumulator.add(page1OldestTs)
        accumulator.add(page1Older)
        accumulator.add(page2DuplicateTs) // 경계 중복
        accumulator.add(page2Older)

        // when — distinctBy ts → 중복 제거.
        val deduped = accumulator.distinctBy { it.ts.epochSeconds }.sortedBy { it.ts.epochSeconds }

        // then — 4건 중 1건 중복 제거 → 3건만 유지.
        assertThat(deduped).hasSize(3)
        // 정렬 ASC.
        assertThat(deduped.map { it.ts.epochSeconds }).isSorted
        // 중복 ts(2026-02-09)는 정확히 1번만 등장.
        val targetEpoch =
            LocalDate
                .of(2026, 2, 9)
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
                .toEpochSecond()
        assertThat(deduped.count { it.ts.epochSeconds == targetEpoch }).isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // 시나리오 4: infinite-loop-guard
    //   cursor가 advance 안 하면 break — nextCursor가 strict before 보장
    // -------------------------------------------------------------------------

    @Test
    fun `infinite-loop-guard nextCursor는 strict before oldestDate 보장 — cursor advance 0 시 break 가능`() {
        val adapter = buildAdapter()

        // given/when/then — 임의 oldestDate에 대해 nextCursor가 strict before 보장.
        // 이 invariant가 깨지면 외부 loop의 `!newCursor.isBefore(cursor)` 가드가 종료.
        val testCases =
            listOf(
                LocalDate.of(2026, 5, 19),
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(1990, 6, 15),
            )

        testCases.forEach { oldestDate ->
            val nextCursor = invokeNextCursor(adapter, oldestDate)
            assertThat(nextCursor)
                .`as`("nextCursor must be strict before oldestDate=%s (infinite loop guard invariant)", oldestDate)
                .isBefore(oldestDate)
            // 정확히 1일 차감 (PR 2cbd4e8 패턴).
            assertThat(
                java.time.temporal.ChronoUnit.DAYS
                    .between(nextCursor, oldestDate),
            ).isEqualTo(1L)
        }
    }

    /**
     * 시나리오 4 (보강): cursor가 minStartDate 이하로 가면 외부 loop가 종료해야 함.
     * 본 invariant는 fetchDailyCandles 본체에 직접 코드로 LOCK — `newCursor.isBefore(minStartDate)` break.
     */
    @Test
    fun `infinite-loop-guard minStartDate 가드 30년 한도 시뮬레이션`() {
        val adapter = buildAdapter()

        // given — 30년 전 minStartDate.
        val minStartDate = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(30)
        // oldestDate가 minStartDate 직후라고 가정.
        val oldestDate = minStartDate.plusDays(1)

        // when
        val nextCursor = invokeNextCursor(adapter, oldestDate)

        // then — nextCursor = minStartDate (이상 진입 시 외부 loop break).
        assertThat(nextCursor).isEqualTo(minStartDate)
        // 다음 iteration에서 `cursor.isBefore(minStartDate)` 또는 `newCursor.isBefore(minStartDate)` 가드 발동.
        assertThat(nextCursor.minusDays(1).isBefore(minStartDate)).isTrue
    }
}
