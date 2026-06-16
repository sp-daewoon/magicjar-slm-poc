package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentStatusHistoryJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatus
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * CandleGapDetectorPort 구현 — consumer 모듈 (ADR 0086 §2-2 + T-CC Phase 2-A/B + ADR 0090 Phase 2).
 *
 * 책임:
 *   - 영업일 캘린더 기반 expected sequence 생성 (BusinessDayCalendar.isBusinessDay() 의무 호출)
 *   - DB actual sequence 조회 (CandleRepository.findAllTsByCodeAndIntervalBetween)
 *   - HashSet diff O(1) — gaps 산출 (ASC)
 *
 * 햇징 룰 적용 (ADR 0086 §2-1):
 *   - 룰 #6 false positive 가드 (passive):
 *       * 거래정지·정리매매 종목 → empty list 반환 (KIS_COLD_START_SKIPPED audit action 정합)
 *       * 휴장일 제외 (BusinessDayCalendar.isBusinessDay() 호출)
 *       * NXT 후보(`is_kospi200` 또는 `is_kosdaq150`)는 08:00~20:00 KST expected sequence 사용
 *       * 그 외 종목은 KIS `J` fallback 정합상 KRX 정규장 09:00~15:30 KST expected sequence 사용
 *   - 룰 #7 false negative 가드: actual 0건이면 expected 전체 = 갭 list (last_ts null Phase 1 패턴 정합)
 *   - **룰 §12 Active gap detection 4축 교차 검증 (ADR 0090 Phase 2 정밀화, 2026-05-19)**:
 *       1축: `market_calendar.is_business_day=TRUE` (BusinessDayCalendar 권위 — 위 #6과 동일 호출)
 *       2축: `instrument_status_history at ts != TRADING_HALT` (★ Phase 2 history snapshot LOCK)
 *       3축: `instrument_status_history at ts != LIQUIDATION` (★ Phase 2 history snapshot LOCK)
 *       4축: `instrument.listed_at <= ts` (상장일 이후 ts)
 *     → 4축 모두 TRUE인데 candle row 부재 = 명확한 결함 → audit `CANDLE_GAP_DETECTED` 발행.
 *     사용자(최고 관리자) 명시 정합 룰. 임의 임계치("5+ 영업일 연속") 영구 폐기.
 *
 * ADR 0090 Phase 2 — axis 2·3 시점 정밀도 향상 (false positive·false negative 영구 차단):
 *   - 기존 한계 (Phase 2-B 1차 MVP): `instrument.trading_halt`·`instrument.liquidation` flag는 **현재 상태**만 보존
 *     → 5개월 전 거래정지 후 NORMAL 복귀한 종목의 과거 정지 구간을 결함으로 오인할 risk
 *   - Phase 2 정밀화: `instrument_status_history` lens (`ConsumerInstrumentStatusHistoryJpaRepository`)를
 *     `passesActiveGapAxes(ts)`에서 각 ts마다 조회 → 시점별 status 판단
 *   - 호환성 fallback: history row 0건 또는 repository 미주입 시 기존 entity flag로 폴백 (회귀 0)
 *
 * 윈도우 (ADR 0086 §2-3 LOCK):
 *   - 최근 30 거래일 (default windowDays=30)
 *   - KIS 호출 cost 보호 + in-memory diff O(1) (30 거래일 × 1m = 21,600 ts/종목 in-memory)
 *
 * 자본 영향 0 — 조회만 수행 + 데이터 변경 0건 (audit_trail row만 추가).
 *
 * 룰 5 위반 0: LLM provider import 0건 (ADR 0023 정합).
 */
@Component
class CandleGapDetectorAdapter(
    private val candleRepository: CandleRepository,
    private val businessDayCalendar: BusinessDayCalendar,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditRepositoryProvider: ObjectProvider<ConsumerAuditTrailJpaRepository>,
    private val objectMapperProvider: ObjectProvider<ObjectMapper>,
    private val statusHistoryRepositoryProvider: ObjectProvider<ConsumerInstrumentStatusHistoryJpaRepository>,
) : CandleGapDetectorPort {
    private val log = LoggerFactory.getLogger(CandleGapDetectorAdapter::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** KRX-only fallback 시작 시각 (KST) — NXT 후보가 아닌 종목 expected sequence 시작점 */
        private const val KRX_OPEN_MIN_OF_DAY = 9 * 60

        /** KRX-only fallback 마감 시각 (KST) — 15:30 → 1m expected sequence 종료점 (15:29 마지막) */
        private const val KRX_CLOSE_MIN_OF_DAY = 15 * 60 + 30

        /** 1d expected ts 정규화 — 00:00 KST (Candle.ts 정합 baseline) */
        private const val DAILY_TS_HOUR = 0
        private const val DAILY_TS_MIN = 0
    }

    override fun detectGaps(
        code: String,
        interval: CandleInterval,
        windowDays: Int,
    ): List<Instant> {
        require(windowDays in 1..365) { "windowDays out of range: $windowDays (1..365)" }
        // 햇징 룰 #6 false positive 가드 — 거래정지·정리매매 skip (passive — entity 단위)
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
            log.info(
                "CandleGapDetector skip — 거래정지·정리매매: code={} halt={} liquidation={}",
                code,
                instrument.tradingHalt,
                instrument.liquidation,
            )
            return emptyList()
        }

        // expected sequence 생성 (영업일 30거래일 — BusinessDayCalendar 의무 호출 — §12 1축)
        val nowKst = LocalDate.now(KST)
        val expectedDates = computeExpectedBusinessDays(nowKst, windowDays)
        if (expectedDates.isEmpty()) {
            log.warn("CandleGapDetector — expected business days 0: code={} interval={}", code, interval.code)
            return emptyList()
        }

        val expected: List<Instant> =
            when (interval) {
                CandleInterval.ONE_DAY -> expectedDailyTs(expectedDates)
                CandleInterval.ONE_MIN -> expectedMinuteTs(expectedDates, isNxtIntegratedCandidate(instrument))
                else -> {
                    log.warn(
                        "CandleGapDetector — interval={} 미지원 (Phase 2-A는 1d·1m만 LOCK)",
                        interval.code,
                    )
                    return emptyList()
                }
            }

        if (expected.isEmpty()) return emptyList()

        // §12 4축 교차 검증 — 각 ts별로 axes 2·3·4 통과 여부 필터 (axis 1은 위 expectedDates에서 이미 적용)
        // axis 2: instrument_status_history at ts != TRADING_HALT (ADR 0090 Phase 2 시점 정밀화)
        // axis 3: instrument_status_history at ts != LIQUIDATION (ADR 0090 Phase 2 시점 정밀화)
        // axis 4: instrument.listed_at <= ts (ts가 상장일 이전이면 false positive — 데이터 부재가 정상)
        val statusHistoryRepo = statusHistoryRepositoryProvider.getIfAvailable()
        val verifiedExpected =
            expected.filter { ts -> passesActiveGapAxes(code, instrument, ts, statusHistoryRepo) }
        if (verifiedExpected.isEmpty()) {
            log.info(
                "CandleGapDetector — 4축 통과 ts 0건 (상장일 이전 등): code={} interval={} rawExpected={}",
                code,
                interval.code,
                expected.size,
            )
            return emptyList()
        }

        // actual sequence 조회 — 범위 from·to (inclusive · 4축 통과 ts 기준)
        val from = verifiedExpected.first()
        val to = verifiedExpected.last()
        val actual = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
        val actualSet: Set<Instant> = HashSet(actual)

        // diff — gaps (4축 통과 expected 중 actual에 없는 ts) → §12 active 결함 정의 적용
        val gaps = verifiedExpected.filterNot { it in actualSet }

        log.info(
            "CandleGapDetector: code={} interval={} windowDays={} expected={} verified={} actual={} gaps={}",
            code,
            interval.code,
            windowDays,
            expected.size,
            verifiedExpected.size,
            actual.size,
            gaps.size,
        )

        // §12 audit_trail CANDLE_GAP_DETECTED 발행 (gaps > 0 시) — 4축 evidence inline
        if (gaps.isNotEmpty()) {
            recordGapDetectedAudit(
                code = code,
                interval = interval,
                windowDays = windowDays,
                instrument = instrument,
                rawExpected = expected.size,
                verifiedExpected = verifiedExpected.size,
                actualCount = actual.size,
                gaps = gaps,
                statusHistoryRepo = statusHistoryRepo,
            )
        }

        return gaps
    }

    /**
     * §12 4축 교차 검증 중 axis 2·3·4 통과 여부 (axis 1은 expectedDates 생성 단계에서 이미 적용).
     *
     * ADR 0090 Phase 2 정밀화 (2026-05-19):
     *
     * axis 2 (TRADING_HALT) + axis 3 (LIQUIDATION):
     *   `instrument_status_history` lens — `findStatusAt(code, ts)` 조회로 시점별 status 판단:
     *     - row 발견 + status == TRADING_HALT → axis 2 fail
     *     - row 발견 + status == LIQUIDATION  → axis 3 fail
     *     - row 발견 + status == DELISTED    → axis 2·3 fail (상장폐지는 매매 불가)
     *     - row 발견 + status == NORMAL      → 통과
     *     - row 부재 → fallback: 기존 entity flag (`instrument.tradingHalt`·`liquidation`) 적용 (Phase 1 backfill 종목 cover)
     *     - statusHistoryRepo null (DI 부재·테스트) → fallback: entity flag (회귀 0 가드)
     *
     *   ★ 기존 detectGaps() early-return은 이미 entity 현재 상태 기준으로 통과 보장.
     *     본 함수는 그 위에 history snapshot lens를 덧입혀 axis 2·3 시점 정밀도를 ts 단위로 향상.
     *
     * axis 4 (listed_at <= ts):
     *   instrument.listedAt null 시 "상장일 미상" → 통과로 간주 (false positive 회피).
     *   listedAt 존재 시 ts가 상장일 이전이면 skip — 정상 부재(상장 전 데이터 없음).
     *
     * ts → KST LocalDate 변환 후 비교 (listed_at은 LocalDate).
     */
    private fun passesActiveGapAxes(
        code: String,
        instrument: ConsumerInstrumentJpaEntity?,
        ts: Instant,
        statusHistoryRepo: ConsumerInstrumentStatusHistoryJpaRepository?,
    ): Boolean {
        // axis 2·3 — instrument_status_history at ts (ADR 0090 Phase 2)
        val statusAt = resolveStatusAt(code, instrument, ts, statusHistoryRepo)
        if (statusAt == InstrumentStatus.TRADING_HALT) return false
        if (statusAt == InstrumentStatus.LIQUIDATION) return false
        if (statusAt == InstrumentStatus.DELISTED) return false

        // axis 4 — 상장일 이후 ts 검증
        val listedAt = instrument?.listedAt ?: return true // 상장일 미상 → 통과 (false positive 회피)
        val tsKstDate = ts.atZone(KST).toLocalDate()
        return !tsKstDate.isBefore(listedAt)
    }

    /**
     * 시점 ts의 instrument status 해석 (ADR 0090 Phase 2 권위 path).
     *
     * 우선순위:
     *   1. `instrument_status_history` row at ts (Phase 1 baseline backfill 종목 cover)
     *   2. fallback: entity 현재 상태 flag (history row 부재 종목 — 회귀 0 가드)
     *   3. fallback: NORMAL (instrument 자체 null — false positive 회피)
     *
     * 호출자: `passesActiveGapAxes(...)` + `recordGapDetectedAudit(...)` audit evidence 단일 source.
     */
    private fun resolveStatusAt(
        code: String,
        instrument: ConsumerInstrumentJpaEntity?,
        ts: Instant,
        statusHistoryRepo: ConsumerInstrumentStatusHistoryJpaRepository?,
    ): InstrumentStatus {
        // 1. instrument_status_history at ts (Phase 2 권위)
        if (statusHistoryRepo != null) {
            val rows =
                try {
                    statusHistoryRepo.findStatusAt(code, ts)
                } catch (e: Exception) {
                    log.warn(
                        "CandleGapDetector — instrument_status_history 조회 실패: code={} ts={} err={}",
                        code,
                        ts,
                        e.message,
                    )
                    emptyList()
                }
            val activeRow = rows.firstOrNull()
            if (activeRow != null) {
                val parsed = runCatching { InstrumentStatus.valueOf(activeRow.status) }.getOrNull()
                if (parsed != null) return parsed
                log.warn(
                    "CandleGapDetector — instrument_status_history status enum 변환 실패: code={} raw={}",
                    code,
                    activeRow.status,
                )
            }
        }
        // 2. fallback: entity 현재 상태 flag (history row 부재 종목)
        if (instrument != null) {
            if (instrument.tradingHalt) return InstrumentStatus.TRADING_HALT
            if (instrument.liquidation) return InstrumentStatus.LIQUIDATION
        }
        // 3. fallback: NORMAL (instrument null — false positive 회피)
        return InstrumentStatus.NORMAL
    }

    /**
     * §12 audit_trail `CANDLE_GAP_DETECTED` 발행 — 4축 evidence inline.
     *
     * ADR 0090 Phase 2 — axes.historyStatusAt evidence 추가:
     *   - 첫 번째 gap ts에서 resolveStatusAt() 호출 → 해당 시점 history snapshot status
     *   - history row 부재 시 "fallback:entity" 표기 (운영 audit 추적 가능)
     *
     * detail JSON 구조:
     *   {
     *     "code": "004090",
     *     "interval": "1d",
     *     "windowDays": 30,
     *     "rawExpected": 30,
     *     "verifiedExpected": 28,    // 4축 통과 ts 수
     *     "actualCount": 23,
     *     "gapCount": 5,
     *     "gapsSample": ["2026-05-15T00:00:00Z", ...],  // 상위 10개
     *     "axes": {                  // 4축 권위 source evidence
     *       "businessDayCalendar": true,         // axis 1 — 항상 true (filter 결과)
     *       "tradingHalt": false,                // axis 2 entity flag (현재 상태)
     *       "liquidation": false,                // axis 3 entity flag (현재 상태)
     *       "listedAt": "2010-12-09",            // axis 4 (null 시 "unknown")
     *       "historyStatusAt": "NORMAL",         // ADR 0090 Phase 2 — 첫 gap ts history snapshot status
     *       "historyLensActive": true            // history repository 활성 여부 (회귀 추적)
     *     }
     *   }
     */
    private fun recordGapDetectedAudit(
        code: String,
        interval: CandleInterval,
        windowDays: Int,
        instrument: ConsumerInstrumentJpaEntity?,
        rawExpected: Int,
        verifiedExpected: Int,
        actualCount: Int,
        gaps: List<Instant>,
        statusHistoryRepo: ConsumerInstrumentStatusHistoryJpaRepository?,
    ) {
        val auditRepo = auditRepositoryProvider.getIfAvailable() ?: return
        val objectMapper = objectMapperProvider.getIfAvailable() ?: return
        val firstGap = gaps.firstOrNull()
        val historyStatusAt =
            if (firstGap != null) {
                resolveStatusAt(code, instrument, firstGap, statusHistoryRepo).name
            } else {
                "unknown"
            }
        val detail =
            mapOf(
                "code" to code,
                "interval" to interval.code,
                "windowDays" to windowDays,
                "rawExpected" to rawExpected,
                "verifiedExpected" to verifiedExpected,
                "actualCount" to actualCount,
                "gapCount" to gaps.size,
                "gapsSample" to gaps.take(10).map { it.toString() },
                "axes" to
                    mapOf(
                        "businessDayCalendar" to true,
                        "tradingHalt" to (instrument?.tradingHalt ?: false),
                        "liquidation" to (instrument?.liquidation ?: false),
                        "listedAt" to (instrument?.listedAt?.toString() ?: "unknown"),
                        "historyStatusAt" to historyStatusAt,
                        "historyLensActive" to (statusHistoryRepo != null),
                    ),
            )
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("CandleGapDetector audit JSON 직렬화 실패: code={} err={}", code, e.message)
                "{}"
            }
        try {
            auditRepo.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "chart-curator",
                    action = "CANDLE_GAP_DETECTED",
                    entityType = "candle",
                    entityId = code,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("CandleGapDetector audit 저장 실패: code={} err={}", code, e.message)
        }
    }

    /**
     * 영업일 expected dates 생성 — 최근 windowDays 거래일 (BusinessDayCalendar 의무 호출).
     *
     * 알고리즘:
     *   - cursor = nowKst (오늘 포함 X — 장 진행 중 false positive 회피)
     *   - cursor 이전 거래일 windowDays개 누적 (calendar.isBusinessDay() 호출)
     *   - 결과 ASC 정렬
     */
    private fun computeExpectedBusinessDays(
        nowKst: LocalDate,
        windowDays: Int,
    ): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = nowKst.minusDays(1) // 오늘 제외 (장 진행 중)
        val maxLookback = windowDays * 3L // 휴일·주말 비율 가드 (3배 안전)
        var iterCount = 0L
        while (dates.size < windowDays && iterCount < maxLookback) {
            if (businessDayCalendar.isBusinessDay(cursor)) {
                dates.add(cursor)
            }
            cursor = cursor.minusDays(1)
            iterCount++
        }
        return dates.sorted() // ASC
    }

    /**
     * 일봉 expected ts — 각 거래일 00:00 KST → UTC Instant.
     * Candle.ts 적재 baseline 정합 (KIS daily candle ts는 KST 00:00 기준).
     */
    private fun expectedDailyTs(dates: List<LocalDate>): List<Instant> =
        dates.map { d ->
            ZonedDateTime.of(d, java.time.LocalTime.of(DAILY_TS_HOUR, DAILY_TS_MIN), KST).toInstant()
        }

    /**
     * 1분봉 expected ts.
     *
     * - NXT 후보(KOSPI200/KOSDAQ150): 08:00~19:59 KST (720 bar/일)
     * - 그 외 J fallback 종목: 09:00~15:29 KST (390 bar/일)
     */
    private fun expectedMinuteTs(
        dates: List<LocalDate>,
        nxtIntegrated: Boolean,
    ): List<Instant> {
        val openMin =
            if (nxtIntegrated) {
                CandleSession.NXT_INTEGRATED_OPEN_MIN
            } else {
                KRX_OPEN_MIN_OF_DAY
            }
        val closeMin =
            if (nxtIntegrated) {
                CandleSession.NXT_INTEGRATED_CLOSE_MIN
            } else {
                KRX_CLOSE_MIN_OF_DAY
            }
        val totalBars = closeMin - openMin
        val result = ArrayList<Instant>(dates.size * totalBars)
        for (d in dates) {
            val open = ZonedDateTime.of(d, java.time.LocalTime.of(openMin / 60, openMin % 60), KST)
            for (i in 0 until totalBars) {
                result.add(open.plusMinutes(i.toLong()).toInstant())
            }
        }
        return result
    }

    private fun isNxtIntegratedCandidate(instrument: ConsumerInstrumentJpaEntity?): Boolean =
        instrument?.let { it.isKospi200 || it.isKosdaq150 } ?: false
}
