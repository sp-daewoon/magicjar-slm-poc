package kr.spdaewoon.magicjar.domain.market.candle

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * 일봉(ONE_DAY) candle list 를 주봉(ONE_WEEK)·월봉(ONE_MONTH)·연봉(ONE_YEAR) 으로 롤업하는
 * 순수 함수 도메인 컴포넌트.
 *
 * ## 결정 이력 (ADR 0120 — 2026-06-10)
 *   년봉 회귀(2026-06-09 MIGRATE wipe 가 일봉 패밀리 4종 전부 삭제했으나 R-BF 캠페인은 1d 만 재적재 →
 *   1wk·1mo·1y 가 15종목만 남음) 근본 fix. 사용자(최고 관리자) 결재 A안:
 *   **1wk/1mo/1y 를 KIS 직접 fetch(FHKST03010100 W/M/Y)가 아니라 1d 에서 합산 생성** 으로 전환.
 *   기준봉(KIS raw fetch) = 1m + 1d 둘뿐 / 파생 합산 = 3m~12h ← 1m([CandleAggregator]) ·
 *   1wk·1mo·1y ← 1d(본 컴포넌트). 우리가 1d 10년 전종목 보유 → KIS 콜 0.
 *
 * ## ★ ts·OHLCV 관례 — KIS FHKST03010100 (W/M/Y) 실측 reverse-engineering (회귀 비교 evidence)
 *   기존 15종목(005930 삼성전자 등) KIS-fetch 봉을 DB 실측하여 KIS 의 period-boundary 관례를 1:1 정합:
 *
 *   | interval | 그룹 경계 | bar ts (KIS 관례) | open | close |
 *   |----------|-----------|-------------------|------|-------|
 *   | ONE_WEEK | ISO 주(월~일) | **그 주 첫 거래일** (월요일·휴장 시 화요일) | 첫 거래일 open | 마지막 거래일 close |
 *   | ONE_MONTH| 역월 | **그 달 마지막 거래일** | 첫 거래일 open | 마지막 거래일 close |
 *   | ONE_YEAR | 역년 | **그 해 마지막 거래일** | 첫 거래일 open | 마지막 거래일 close |
 *
 *   실측 evidence (005930, raw 비교 — DB candle 직접 조회 2026-06-10):
 *     - 주봉 2026-06-08(월): open=293000(=06-08 1d open) close=322000(=06-09 1d close) vol=67,918,935(=Mon+Tue)
 *     - 주봉 2026-05-26(화): 05-25 월요일 휴장 → 그 주 첫 거래일 화요일이 ts
 *     - 월봉 2026-05-29(금=5월 마지막 거래일): open=228000(=05-04 첫 거래일) close=317000 high=323000 low=224000
 *       vol=622,815,748 — 1d 18봉 롤업과 OHLCV 완전 일치 (회귀 비교 PASS)
 *     - 연봉 2025-12-30(화=2025 마지막 거래일) / 2024-12-30 / 2023-12-28 ...
 *
 *   ⚠ 주봉만 ts=첫 거래일, 월/연봉은 ts=마지막 거래일 (KIS 비대칭 관례 — 자체 추론 금지, 실측 정합).
 *   진행 중(오늘 포함) 미닫힌 봉도 KIS 는 "현재까지의 마지막 거래일"을 ts 로 준다(월봉 06-09 등).
 *   본 롤업은 입력 1d 의 마지막 거래일을 그대로 사용하므로 동일 동작.
 *
 * ## OHLCV 합산식 (group_by(boundary))
 *   open  = 구간 첫 거래일 openRaw
 *   high  = max(highRaw)
 *   low   = min(lowRaw)
 *   close = 구간 마지막 거래일 closeRaw
 *   vol   = sum(volume)
 *   adj   = 입력 1d 의 adj 와 동일 기준 (1d 가 raw-only(adj null)면 adj null — close_adj 정합).
 *           openAdj=첫날 openAdj, closeAdj=마지막날 closeAdj, highAdj=max(non-null), lowAdj=min(non-null).
 *   source= 첫 거래일 source (보통 "KIS" / 백필 source 마킹은 호출부에서 copy).
 *
 * ## 실시간 보존 (ADR 0120 §불변 제약)
 *   본 롤업은 **닫힌 봉(어제까지)** 을 생성한다. 오늘 진행 주/월/년봉의 라이브 merge 는
 *   `CandleQueryService.mergeQuoteLastCurrentCandle`(quote:last 병합 — isSameQuotePeriod 가
 *   weekStart(월)·year+month·year 로 같은 기간 판정) 가 담당. ts 관례를 KIS 와 동일하게 유지하므로
 *   live-merge 의 isSameQuotePeriod 매칭이 깨지지 않는다.
 *
 * ## 원칙 (CandleAggregator 패턴 정합 — ADR 0004 헥사고날)
 *   - Spring·JPA 의존 0. 외부 의존 0 (Kotlin stdlib + kotlinx.datetime).
 *   - 순수 함수 — 입력 1d candle list → 출력 합산 candle list. 부수효과 0, 결정론적.
 *   - mock 합성 금지 — 구간 내 1d 0건이면 해당 bar 생략(거래정지·미상장 구간 자연 누락).
 *
 * ## 경계 케이스
 *   - candles 빈 list                → 빈 list
 *   - target ∉ {ONE_WEEK·ONE_MONTH·ONE_YEAR} → IllegalArgumentException
 *   - 입력 candle 의 interval ≠ ONE_DAY      → IllegalArgumentException
 *   - 정렬 무관 입력                  → 내부에서 ts ASC 정렬 후 그룹화
 *
 * @see kr.spdaewoon.magicjar.domain.market.candle.CandleAggregator (1m → 3m~12h 합산 — 대칭 컴포넌트)
 */
object DailyRollupAggregator {
    private val KST: TimeZone = TimeZone.of("Asia/Seoul")

    /** 지원 target interval 3종 — 1d 에서 롤업 가능한 상위 timeframe. */
    private val SUPPORTED_TARGETS: Set<CandleInterval> =
        setOf(
            CandleInterval.ONE_WEEK,
            CandleInterval.ONE_MONTH,
            CandleInterval.ONE_YEAR,
        )

    /**
     * 1d candle list 를 [target] 상위 interval 로 롤업.
     *
     * @param candles 1d candle list (정렬 무관 — 내부 ts ASC 정렬). 모든 candle interval=ONE_DAY.
     * @param target  ONE_WEEK·ONE_MONTH·ONE_YEAR
     * @return 롤업 candle list (ts ASC). 구간 내 1d 0건이면 해당 bar 생략.
     * @throws IllegalArgumentException target 미지원 또는 입력에 비-1d candle 포함
     */
    fun rollup(
        candles: List<Candle>,
        target: CandleInterval,
    ): List<Candle> {
        require(target in SUPPORTED_TARGETS) {
            "target=$target — 지원하지 않는 롤업 interval (지원: 1wk·1mo·1y)"
        }
        if (candles.isEmpty()) return emptyList()

        val invalid = candles.firstOrNull { it.interval != CandleInterval.ONE_DAY }
        require(invalid == null) {
            "입력 candles 는 모두 ONE_DAY 이어야 함 (발견: interval=${invalid?.interval})"
        }

        // ts ASC 정렬 — 그룹 내 first=가장 이른 거래일, last=가장 늦은 거래일.
        val sorted = candles.sortedBy { it.ts.epochSeconds }

        // 안정 그룹화 — LinkedHashMap 으로 boundary key 등장 순서(=ts ASC) 보존.
        val grouped = LinkedHashMap<String, MutableList<Candle>>()
        for (c in sorted) {
            val key = groupKey(c.ts, target)
            grouped.getOrPut(key) { mutableListOf() }.add(c)
        }

        return grouped.values
            .map { group -> rollupGroup(group, target) }
            .sortedBy { it.ts.epochSeconds }
    }

    /**
     * 한 boundary 그룹의 1d candle list 를 1건의 롤업 candle 로 reduce.
     *
     * group 은 rollup() 에서 ts ASC 정렬·그룹화되었으므로 first=첫 거래일, last=마지막 거래일.
     * ts 는 interval 별 KIS 관례: 주봉=첫 거래일 ts / 월·연봉=마지막 거래일 ts (상단 매트릭스).
     */
    private fun rollupGroup(
        group: List<Candle>,
        target: CandleInterval,
    ): Candle {
        val first = group.first()
        val last = group.last()
        val ts =
            when (target) {
                CandleInterval.ONE_WEEK -> first.ts // 주봉 = 그 주 첫 거래일 (KIS 관례)
                else -> last.ts // 월봉·연봉 = 그 기간 마지막 거래일 (KIS 관례)
            }
        return Candle(
            id = null,
            code = first.code,
            interval = target,
            ts = ts,
            openRaw = first.openRaw,
            highRaw = group.maxOf { it.highRaw },
            lowRaw = group.minOf { it.lowRaw },
            closeRaw = last.closeRaw,
            openAdj = first.openAdj,
            highAdj = group.mapNotNull { it.highAdj }.maxOrNull(),
            lowAdj = group.mapNotNull { it.lowAdj }.minOrNull(),
            closeAdj = last.closeAdj,
            volume = group.sumOf { it.volume },
            source = first.source,
        )
    }

    /**
     * ts 가 속하는 [target] boundary 의 그룹 key (KST 기준).
     *
     *   ONE_WEEK : ISO week-based year + week number (월~일 1주). "2026-W24"
     *   ONE_MONTH: 역월. "2026-05"
     *   ONE_YEAR : 역년. "2026"
     */
    internal fun groupKey(
        ts: Instant,
        target: CandleInterval,
    ): String {
        val date = ts.toLocalDateTime(KST).date
        return when (target) {
            CandleInterval.ONE_WEEK -> {
                val (isoYear, isoWeek) = isoWeek(date)
                "$isoYear-W%02d".format(isoWeek)
            }
            CandleInterval.ONE_MONTH -> "%04d-%02d".format(date.year, date.monthNumber)
            CandleInterval.ONE_YEAR -> "%04d".format(date.year)
            else -> throw IllegalArgumentException("groupKey: 지원하지 않는 target=$target")
        }
    }

    /**
     * ISO-8601 week-based year + week number 계산 (kotlinx.datetime 에 직접 API 부재 — 직접 구현).
     *
     * ISO week: 월요일 시작, 한 해의 1주는 그 해 첫 목요일을 포함하는 주.
     * 1d 의 ts(거래일)를 그 거래일이 속한 ISO 주로 매핑하므로, 같은 주의 모든 거래일이 동일 key.
     * 주말(토·일) 거래일은 없으므로 (월~금) 동일 ISO week 로 자연 그룹화된다.
     *
     * @return Pair(week-based year, week number 1..53)
     */
    internal fun isoWeek(date: LocalDate): Pair<Int, Int> {
        // 그 날이 속한 주의 목요일을 기준으로 week-based year·week 를 산정 (ISO 규칙).
        val dow = date.dayOfWeek.isoNumber() // Mon=1..Sun=7
        val thursday = date.plusDaysSafe(4 - dow) // 같은 ISO 주의 목요일
        val weekYear = thursday.year
        val jan1 = LocalDate(weekYear, 1, 1)
        val daysFromJan1 = thursday.epochDay() - jan1.epochDay()
        val week = (daysFromJan1 / 7L).toInt() + 1
        return weekYear to week
    }

    /**
     * [ts] 가 속한 [target] 기간 버킷의 반열린 경계 `[start, end)` 를 KST 기준으로 반환 (ADR 0120 amend — 2026-06-11).
     *
     * 고아 앵커 정리([CandleRepository.deleteStaleBucketAnchors])가 "같은 버킷의 다른 ts 행"을 안전하게
     * 식별하기 위한 경계. 버킷 내 어떤 ts 든(앵커가 첫·마지막 거래일이든) `start ≤ ts < end` 로 동일 버킷에 묶인다.
     *
     *   ONE_WEEK : ISO 주(월 00:00 KST) ~ 다음 주 월 00:00 (7일)
     *   ONE_MONTH: 역월 1일 00:00 KST ~ 다음 달 1일 00:00
     *   ONE_YEAR : 역년 1/1 00:00 KST ~ 다음 해 1/1 00:00
     *
     * 반열린 구간이므로 버킷 마지막 날의 23:59:59 거래일 ts 도 포함되고, 다음 버킷 시작 ts 는 제외된다.
     *
     * @return Pair(start inclusive, end exclusive) — KST 자정 경계의 Instant.
     */
    fun bucketRange(
        ts: Instant,
        target: CandleInterval,
    ): Pair<Instant, Instant> {
        require(target in SUPPORTED_TARGETS) {
            "target=$target — 지원하지 않는 버킷 interval (지원: 1wk·1mo·1y)"
        }
        val date = ts.toLocalDateTime(KST).date
        val (startDate, endDate) =
            when (target) {
                CandleInterval.ONE_WEEK -> {
                    val dow = date.dayOfWeek.isoNumber() // Mon=1..Sun=7
                    val monday = date.plusDaysSafe(1 - dow) // 그 ISO 주의 월요일
                    monday to monday.plusDaysSafe(7)
                }
                CandleInterval.ONE_MONTH -> {
                    val first = LocalDate(date.year, date.monthNumber, 1)
                    val nextMonth =
                        if (date.monthNumber == 12) {
                            LocalDate(date.year + 1, 1, 1)
                        } else {
                            LocalDate(date.year, date.monthNumber + 1, 1)
                        }
                    first to nextMonth
                }
                CandleInterval.ONE_YEAR -> {
                    LocalDate(date.year, 1, 1) to LocalDate(date.year + 1, 1, 1)
                }
                else -> throw IllegalArgumentException("bucketRange: 지원하지 않는 target=$target")
            }
        return startDate.atStartOfDayIn(KST) to endDate.atStartOfDayIn(KST)
    }

    private fun DayOfWeek.isoNumber(): Int = this.ordinal + 1 // kotlinx MONDAY.ordinal=0 → 1

    private fun LocalDate.epochDay(): Long = this.atStartOfDayIn(TimeZone.UTC).epochSeconds / 86_400L

    private fun LocalDate.plusDaysSafe(days: Int): LocalDate {
        val target = this.epochDay() + days
        val instant = Instant.fromEpochSeconds(target * 86_400L)
        return instant.toLocalDateTime(TimeZone.UTC).date
    }
}
