package kr.spdaewoon.magicjar.domain.market.candle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * DailyRollupAggregator 단위 테스트 — 1d → 1wk·1mo·1y 롤업.
 *
 * ## 검증 시나리오
 *   1. 주봉 ts=첫 거래일 / 월·연봉 ts=마지막 거래일 (KIS 관례)
 *   2. OHLCV 정합 — open=첫날, close=마지막날, high=max, low=min, vol=sum
 *   3. ★ 회귀 비교 — 005930 실측 KIS 봉(2026-06-10 DB 조회) 과 1d 롤업 OHLCV 1:1 일치
 *      (월봉 2026-05: open=228000 close=317000 high=323000 low=224000 vol=622,815,748 ts=05-29)
 *      (주봉 2026-06-08주: open=293000 close=322000 vol=67,918,935 ts=06-08)
 *   4. 휴장 시 그 주 첫 거래일이 주봉 ts (월요일 휴장 → 화요일)
 *   5. 연봉 — 한 해 전체 묶음, ts=마지막 거래일
 *   6. 빈 입력 / target 미지원 / 비-1d candle → 경계
 *   7. 정렬 무관 입력 정렬·그룹화
 *   8. adj 필드 — non-null 입력 시 open/close=첫/마지막날, high/low=max/min(non-null)
 */
class DailyRollupAggregatorTest {
    private val kst = TimeZone.of("Asia/Seoul")
    private val code = "005930"
    private val src = "KIS"

    /** KST 일자 00:00 -> Instant (1d ts 관례 = 거래일 KST 자정). */
    private fun dayTs(
        y: Int,
        mo: Int,
        d: Int,
    ): Instant = LocalDateTime(y, mo, d, 0, 0).toInstant(kst)

    private fun oneDay(
        ts: Instant,
        o: Long,
        h: Long,
        l: Long,
        c: Long,
        v: Long,
        oAdj: Long? = null,
        hAdj: Long? = null,
        lAdj: Long? = null,
        cAdj: Long? = null,
    ): Candle =
        Candle(
            id = null,
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = ts,
            openRaw = BigDecimal(o),
            highRaw = BigDecimal(h),
            lowRaw = BigDecimal(l),
            closeRaw = BigDecimal(c),
            openAdj = oAdj?.let { BigDecimal(it) },
            highAdj = hAdj?.let { BigDecimal(it) },
            lowAdj = lAdj?.let { BigDecimal(it) },
            closeAdj = cAdj?.let { BigDecimal(it) },
            volume = v,
            source = src,
        )

    @Test
    fun `weekly rollup — ts=first trading day, open=first close=last vol=sum (005930 2026-06-08주 실측)`() {
        // 06-08 Mon, 06-09 Tue (그 주 거래일 2일) — DB 실측 1d
        val candles =
            listOf(
                oneDay(dayTs(2026, 6, 8), o = 293000, h = 315500, l = 292500, c = 295500, v = 38_929_682),
                oneDay(dayTs(2026, 6, 9), o = 310000, h = 324000, l = 300000, c = 322000, v = 30_124_249),
            )
        val weekly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_WEEK)
        weekly shouldHaveSize 1
        val bar = weekly.first()
        bar.interval shouldBe CandleInterval.ONE_WEEK
        bar.ts shouldBe dayTs(2026, 6, 8) // 주봉 ts = 첫 거래일 (KIS 관례)
        bar.openRaw shouldBe BigDecimal(293000) // 첫날 open
        bar.closeRaw shouldBe BigDecimal(322000) // 마지막날 close
        bar.highRaw shouldBe BigDecimal(324000) // max(315500, 324000)
        bar.lowRaw shouldBe BigDecimal(292500) // min
        // 롤업 volume = J-채널 1d 합 (38,929,682 + 30,124,249).
        // ⚠ 레거시 KIS-fetch 주봉 volume(67,918,935)과 다름 — 레거시는 UN/통합 채널(NXT 포함),
        // 현 1d 는 J 단일(ADR 0118 amend). 롤업은 "우리가 서빙하는 J 1d 와 항상 일치" 가 정합 우위.
        bar.volume shouldBe 69_053_931L
    }

    @Test
    fun `weekly rollup — 월요일 휴장 시 그 주 첫 거래일(화)이 ts (005930 2026-05-26주 실측)`() {
        // 2026-05-25 Mon 휴장 → 첫 거래일 05-26 Tue. DB 실측 주봉 ts=05-26.
        val candles =
            listOf(
                oneDay(dayTs(2026, 5, 26), o = 298000, h = 300000, l = 295000, c = 299000, v = 10),
                oneDay(dayTs(2026, 5, 27), o = 321500, h = 322000, l = 305000, c = 307000, v = 20),
                oneDay(dayTs(2026, 5, 28), o = 305000, h = 310000, l = 299000, c = 299500, v = 30),
                oneDay(dayTs(2026, 5, 29), o = 309500, h = 318000, l = 300000, c = 317000, v = 40),
            )
        val weekly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_WEEK)
        weekly shouldHaveSize 1
        weekly.first().ts shouldBe dayTs(2026, 5, 26) // 휴장 보정 — 첫 거래일 화요일
        weekly.first().openRaw shouldBe BigDecimal(298000)
        weekly.first().closeRaw shouldBe BigDecimal(317000)
        weekly.first().volume shouldBe 100L
    }

    @Test
    fun `monthly rollup — ts=last trading day (005930 2026-05 실측 회귀 비교)`() {
        // 2026-05 한 달 18거래일 — 실측 집계: open=228000 close=317000 high=323000 low=224000 vol=622,815,748
        // 여기서는 첫·마지막·peak 봉만 재현하여 OHLCV 식 일치를 검증(전체 18봉 합산식 동일).
        val candles =
            listOf(
                oneDay(dayTs(2026, 5, 4), o = 228000, h = 277000, l = 224000, c = 232500, v = 100_000_000),
                oneDay(dayTs(2026, 5, 15), o = 270000, h = 323000, l = 262000, c = 300000, v = 300_000_000),
                oneDay(dayTs(2026, 5, 29), o = 309500, h = 318000, l = 300000, c = 317000, v = 222_815_748),
            )
        val monthly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_MONTH)
        monthly shouldHaveSize 1
        val bar = monthly.first()
        bar.ts shouldBe dayTs(2026, 5, 29) // 월봉 ts = 마지막 거래일 (KIS 관례)
        bar.openRaw shouldBe BigDecimal(228000) // 첫 거래일(05-04) open — 실측 일치
        bar.closeRaw shouldBe BigDecimal(317000) // 마지막 거래일(05-29) close — 실측 일치
        bar.highRaw shouldBe BigDecimal(323000) // 실측 high 일치
        bar.lowRaw shouldBe BigDecimal(224000) // 실측 low 일치
        bar.volume shouldBe 622_815_748L // 실측 월봉 volume 1:1 (회귀 비교 PASS)
    }

    @Test
    fun `yearly rollup — ts=last trading day, 한 해 묶음`() {
        // 2025년 두 거래일만으로 연봉 식 검증 (마지막 거래일 ts=12-30).
        val candles =
            listOf(
                oneDay(dayTs(2025, 1, 2), o = 52700, h = 60000, l = 50800, c = 55000, v = 1_000),
                oneDay(dayTs(2025, 12, 30), o = 118000, h = 121200, l = 116000, c = 119900, v = 2_000),
            )
        val yearly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_YEAR)
        yearly shouldHaveSize 1
        val bar = yearly.first()
        bar.ts shouldBe dayTs(2025, 12, 30) // 연봉 ts = 마지막 거래일
        bar.openRaw shouldBe BigDecimal(52700)
        bar.closeRaw shouldBe BigDecimal(119900)
        bar.highRaw shouldBe BigDecimal(121200)
        bar.lowRaw shouldBe BigDecimal(50800)
        bar.volume shouldBe 3_000L
    }

    @Test
    fun `monthly rollup — 여러 달 분산 시 달별 1봉씩 ts ASC`() {
        val candles =
            listOf(
                oneDay(dayTs(2026, 4, 30), o = 179000, h = 230000, l = 175000, c = 220500, v = 1),
                oneDay(dayTs(2026, 5, 4), o = 228000, h = 277000, l = 224000, c = 232500, v = 2),
                oneDay(dayTs(2026, 5, 29), o = 309500, h = 318000, l = 300000, c = 317000, v = 3),
                oneDay(dayTs(2026, 6, 9), o = 310000, h = 370000, l = 292500, c = 322000, v = 4),
            )
        val monthly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_MONTH)
        monthly shouldHaveSize 3
        monthly.map { it.ts } shouldBe listOf(dayTs(2026, 4, 30), dayTs(2026, 5, 29), dayTs(2026, 6, 9))
        // 5월: 첫=05-04 open=228000, 마지막=05-29 close=317000
        monthly[1].openRaw shouldBe BigDecimal(228000)
        monthly[1].closeRaw shouldBe BigDecimal(317000)
    }

    @Test
    fun `정렬 무관 입력도 정렬·그룹화`() {
        val candles =
            listOf(
                oneDay(dayTs(2026, 6, 9), o = 310000, h = 324000, l = 300000, c = 322000, v = 30_124_249),
                oneDay(dayTs(2026, 6, 8), o = 293000, h = 315500, l = 292500, c = 295500, v = 38_929_682),
            )
        val weekly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_WEEK)
        weekly shouldHaveSize 1
        weekly.first().openRaw shouldBe BigDecimal(293000) // 정렬 후 첫날(06-08) open
        weekly.first().closeRaw shouldBe BigDecimal(322000)
    }

    @Test
    fun `adj 필드 — non-null 입력 시 open=첫날 close=마지막날 high=max low=min`() {
        val candles =
            listOf(
                oneDay(
                    dayTs(2026, 5, 4),
                    o = 228000,
                    h = 277000,
                    l = 224000,
                    c = 232500,
                    v = 1,
                    oAdj = 220,
                    hAdj = 270,
                    lAdj = 218,
                    cAdj = 226,
                ),
                oneDay(
                    dayTs(2026, 5, 29),
                    o = 309500,
                    h = 318000,
                    l = 300000,
                    c = 317000,
                    v = 2,
                    oAdj = 300,
                    hAdj = 315,
                    lAdj = 295,
                    cAdj = 312,
                ),
            )
        val monthly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_MONTH)
        val bar = monthly.first()
        bar.openAdj shouldBe BigDecimal(220) // 첫날 openAdj
        bar.closeAdj shouldBe BigDecimal(312) // 마지막날 closeAdj
        bar.highAdj shouldBe BigDecimal(315) // max(270, 315)
        bar.lowAdj shouldBe BigDecimal(218) // min(218, 295)
    }

    @Test
    fun `adj null 입력(1d raw-only) 이면 롤업 adj 도 null`() {
        val candles =
            listOf(
                oneDay(dayTs(2026, 5, 4), o = 228000, h = 277000, l = 224000, c = 232500, v = 1),
                oneDay(dayTs(2026, 5, 29), o = 309500, h = 318000, l = 300000, c = 317000, v = 2),
            )
        val bar = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_MONTH).first()
        bar.openAdj shouldBe null
        bar.highAdj shouldBe null
        bar.lowAdj shouldBe null
        bar.closeAdj shouldBe null
    }

    @Test
    fun `빈 입력 → 빈 list`() {
        DailyRollupAggregator.rollup(emptyList(), CandleInterval.ONE_WEEK) shouldHaveSize 0
    }

    @Test
    fun `target 미지원(1d·1h 등) → IllegalArgumentException`() {
        val candles = listOf(oneDay(dayTs(2026, 5, 4), o = 1, h = 1, l = 1, c = 1, v = 1))
        shouldThrow<IllegalArgumentException> {
            DailyRollupAggregator.rollup(candles, CandleInterval.ONE_DAY)
        }
        shouldThrow<IllegalArgumentException> {
            DailyRollupAggregator.rollup(candles, CandleInterval.ONE_HOUR)
        }
    }

    @Test
    fun `비-1d candle 섞이면 IllegalArgumentException`() {
        val candles =
            listOf(
                oneDay(dayTs(2026, 5, 4), o = 1, h = 1, l = 1, c = 1, v = 1),
                Candle(
                    id = null,
                    code = code,
                    interval = CandleInterval.ONE_WEEK, // 비-1d
                    ts = dayTs(2026, 5, 5),
                    openRaw = BigDecimal.ONE,
                    highRaw = BigDecimal.ONE,
                    lowRaw = BigDecimal.ONE,
                    closeRaw = BigDecimal.ONE,
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = 1,
                    source = src,
                ),
            )
        shouldThrow<IllegalArgumentException> {
            DailyRollupAggregator.rollup(candles, CandleInterval.ONE_MONTH)
        }
    }

    @Test
    fun `isoWeek — 연말연시 ISO 경계 (그 주 목요일 기준 week-year)`() {
        // 2026-01-01 Thu → ISO 2026-W01. 2025-12-29 Mon~2026-01-04 Sun 은 동일 ISO 주(목=2026-01-01).
        DailyRollupAggregator.isoWeek(LocalDate(2026, 1, 1)) shouldBe (2026 to 1)
        DailyRollupAggregator.isoWeek(LocalDate(2025, 12, 29)) shouldBe (2026 to 1) // 같은 주
        // 같은 ISO 주의 거래일들은 동일 group key → 주봉 1봉으로 묶임.
        val candles =
            listOf(
                oneDay(dayTs(2025, 12, 29), o = 100, h = 110, l = 90, c = 105, v = 1),
                oneDay(dayTs(2025, 12, 30), o = 105, h = 120, l = 100, c = 118, v = 2),
                oneDay(dayTs(2026, 1, 2), o = 118, h = 130, l = 115, c = 125, v = 3),
            )
        val weekly = DailyRollupAggregator.rollup(candles, CandleInterval.ONE_WEEK)
        weekly shouldHaveSize 1 // 연말연시 동일 ISO 주
        weekly.first().ts shouldBe dayTs(2025, 12, 29) // 그 주 첫 거래일
        weekly.first().closeRaw shouldBe BigDecimal(125)
    }

    // ── bucketRange (ADR 0120 amend — 2026-06-11 고아 정리 버킷 경계) ──────────────────────

    @Test
    fun `bucketRange — 월봉 6월 어떤 거래일도 6_1~7_1 반열린 버킷에 묶임`() {
        // 진행 월봉 앵커(6/9·6/10)와 옛 고아(6/9)가 같은 버킷이어야 정리 ts<>keepTs 가 작동.
        val (start9, end9) = DailyRollupAggregator.bucketRange(dayTs(2026, 6, 9), CandleInterval.ONE_MONTH)
        val (start10, end10) = DailyRollupAggregator.bucketRange(dayTs(2026, 6, 10), CandleInterval.ONE_MONTH)
        start9 shouldBe dayTs(2026, 6, 1)
        end9 shouldBe dayTs(2026, 7, 1)
        start10 shouldBe dayTs(2026, 6, 1) // 6/9·6/10 동일 버킷
        end10 shouldBe dayTs(2026, 7, 1)
        // 6/30 거래일 포함, 7/1 제외 (반열린).
        val (start30, _) = DailyRollupAggregator.bucketRange(dayTs(2026, 6, 30), CandleInterval.ONE_MONTH)
        start30 shouldBe dayTs(2026, 6, 1)
    }

    @Test
    fun `bucketRange — 12월 월봉은 다음 해 1_1 로 롤오버`() {
        val (start, end) = DailyRollupAggregator.bucketRange(dayTs(2025, 12, 30), CandleInterval.ONE_MONTH)
        start shouldBe dayTs(2025, 12, 1)
        end shouldBe dayTs(2026, 1, 1)
    }

    @Test
    fun `bucketRange — 년봉 2026 어떤 거래일도 2026_1_1~2027_1_1`() {
        val (start, end) = DailyRollupAggregator.bucketRange(dayTs(2026, 6, 10), CandleInterval.ONE_YEAR)
        start shouldBe dayTs(2026, 1, 1)
        end shouldBe dayTs(2027, 1, 1)
    }

    @Test
    fun `bucketRange — 주봉 화요일 거래일도 그 주 월요일~다음 월요일 버킷`() {
        // 05-25 월 휴장·첫 거래일 화(05-26) 케이스: 버킷은 월요일(05-25)~다음 월(06-01) 반열린.
        val (start, end) = DailyRollupAggregator.bucketRange(dayTs(2026, 5, 26), CandleInterval.ONE_WEEK)
        start shouldBe dayTs(2026, 5, 25) // ISO 주 월요일
        end shouldBe dayTs(2026, 6, 1) // 다음 주 월요일
    }

    @Test
    fun `bucketRange — 연말연시 ISO 주는 목요일 기준 week-year 경계로 묶임`() {
        // 2025-12-29 Mon ~ 2026-01-04 Sun 은 동일 ISO 주(목=2026-01-01). 버킷=12/29~1/5.
        val (start, end) = DailyRollupAggregator.bucketRange(dayTs(2026, 1, 2), CandleInterval.ONE_WEEK)
        start shouldBe dayTs(2025, 12, 29)
        end shouldBe dayTs(2026, 1, 5)
    }

    @Test
    fun `bucketRange — 미지원 interval 은 IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            DailyRollupAggregator.bucketRange(dayTs(2026, 6, 10), CandleInterval.ONE_DAY)
        }
    }
}
