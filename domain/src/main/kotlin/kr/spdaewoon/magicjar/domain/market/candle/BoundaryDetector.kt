package kr.spdaewoon.magicjar.domain.market.candle

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 1m tick ts 기준 boundary 마감 검사 — Pure Kotlin 도메인 (Spring 의존 0).
 *
 * TradingView 정합 boundary 시각 매트릭스 (NXT 통합 08:00~20:00 KST):
 *   3m  : 08:03·08:06·...·19:57·20:00                         → 240 bar/일
 *   5m  : 08:05·08:10·...·19:55·20:00 (boundary = bar 마감 ts)  → 144 bar/일
 *   15m : 08:15·08:30·...·19:45·20:00                          → 48 bar/일
 *   30m : 08:30·09:00·...·19:30·20:00                          → 24 bar/일
 *   90m : 09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00      → 8 bar/일
 *   1h  : 09:00·10:00·...·19:00·20:00                          → 12 bar/일
 *   4h  : 12:00·16:00·20:00                                     → 3 bar/일
 *   12h : 20:00                                                  → 1 bar/일
 *
 * boundary 마감 정의 (B-1 권장안 ADR 0064):
 *   "이 분(minute)이 target interval boundary의 마지막 1m bar이다"
 *   = 다음 분이 boundary 시작 시점이 됨.
 *
 *   예) 5m boundary 마감:
 *     09:04 → 다음 분 09:05 = 새 5m bar 시작 → 09:00~09:04 5건이 완결 → boundary closed
 *     09:29 → 다음 분 09:30 = 새 5m bar 시작 → boundary closed
 *
 * KIS-first 출처 (헌법 §3 의무):
 *   H0STCNT0 체결가 STCK_CNTG_HOUR 필드 (HHMMSS) 기반 boundary 검사.
 *   raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() cols[1] = STCK_CNTG_HOUR
 *   ADR 0064 §1-4 + _workspace/baseline/v3.2.2/03-use-sequences.md §시나리오 3 R5 인용.
 *
 * 90m boundary close anchor (CandleAggregator 정합):
 *   09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00 KST
 *   → boundary 마감 분 = close - 1 minute
 */
object BoundaryDetector {
    private val KST: TimeZone = TimeZone.of("Asia/Seoul")

    /**
     * 주어진 [ts]의 분(minute)이 [target] interval boundary의 마지막 1m bar인지 검사.
     *
     * boundary 마감 = "이 분이 끝나면 target interval이 확정된다"
     *   = 다음 분(ts+1m)이 새 boundary 시작점.
     *
     * @param ts 1m tick의 시작 ts (Instant — KST 기준 분 단위로 추출)
     * @param target 검사 대상 interval (THREE_MIN·FIVE_MIN·FIFTEEN_MIN·THIRTY_MIN·NINETY_MIN·ONE_HOUR·FOUR_HOUR·TWELVE_HOUR)
     * @return true = 이 분이 boundary 마감분 (합산·갱신 실행 필요)
     */
    fun isBoundaryClosed(
        ts: Instant,
        target: CandleInterval,
    ): Boolean {
        val kstLdt = ts.toLocalDateTime(KST)
        val minOfDay = kstLdt.hour * 60 + kstLdt.minute

        // NXT 통합 범위 밖이면 skip. 마감 분은 open..close-1 범위.
        if (!CandleSession.isInIntegratedSession(minOfDay)) return false

        // 다음 분 = 이 분 + 1
        val nextMin = minOfDay + 1

        return when (target) {
            // 3m: 다음 분이 3의 배수이면 이 분이 마감 (08:02→08:03, 08:05→08:06, ...)
            CandleInterval.THREE_MIN -> nextMin % 3 == 0 && nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN

            // 5m: 다음 분이 5의 배수이면 이 분이 마감 (08:04→08:05, 08:09→08:10, ...)
            CandleInterval.FIVE_MIN -> nextMin % 5 == 0 && nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN

            // 15m: 다음 분이 15의 배수이면 이 분이 마감
            CandleInterval.FIFTEEN_MIN -> nextMin % 15 == 0 && nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN

            // 30m: 다음 분이 30의 배수이면 이 분이 마감
            CandleInterval.THIRTY_MIN -> nextMin % 30 == 0 && nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN

            // 1h: 09:00·...·20:00 정각
            CandleInterval.ONE_HOUR ->
                (nextMin % 60 == 0 && nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN) ||
                    nextMin == CandleSession.NXT_INTEGRATED_CLOSE_MIN

            // 4h: NXT 통합 08:00 anchor 기준 12:00·16:00·20:00
            CandleInterval.FOUR_HOUR ->
                (
                    (nextMin - CandleSession.NXT_INTEGRATED_OPEN_MIN) % 240 == 0 &&
                        nextMin > CandleSession.NXT_INTEGRATED_OPEN_MIN
                ) ||
                    nextMin == CandleSession.NXT_INTEGRATED_CLOSE_MIN

            // 12h: NXT 통합 08:00~20:00 전체 1개 bar.
            CandleInterval.TWELVE_HOUR -> nextMin == CandleSession.NXT_INTEGRATED_CLOSE_MIN

            // 90m: 다음 분이 close set에 속하면 이 분이 마감
            CandleInterval.NINETY_MIN -> CandleSession.NINETY_MIN_CLOSE_MIN.contains(nextMin)

            else -> false
        }
    }

    /**
     * [ts]가 속하는 [target] boundary의 시작 ts 반환 (Aggregator가 bar ts로 사용).
     *
     * CandleAggregator.computeBoundaryEpochSec()과 동일 알고리즘을 독립 재구현.
     * (도메인 내부 internal fun 호출 대신 인라인으로 구현 — cross-call 결합도 회피)
     *
     * 예: ts=09:04 KST + target=FIVE_MIN → 09:00 KST Instant
     *     ts=10:29 KST + target=NINETY_MIN → 09:30 KST Instant
     *
     * @param ts 1m tick ts (Instant)
     * @param target 목표 interval
     * @return 해당 boundary 시작 Instant
     */
    fun boundaryStartTs(
        ts: Instant,
        target: CandleInterval,
    ): Instant {
        val kstLdt = ts.toLocalDateTime(KST)
        val minOfDay = kstLdt.hour * 60 + kstLdt.minute
        val midnightEpochSec = ts.epochSeconds - (minOfDay * 60L) - kstLdt.second.toLong()

        val boundaryMin =
            when (target) {
                CandleInterval.THREE_MIN -> (minOfDay / 3) * 3
                CandleInterval.FIVE_MIN -> (minOfDay / 5) * 5
                CandleInterval.FIFTEEN_MIN -> (minOfDay / 15) * 15
                CandleInterval.THIRTY_MIN -> (minOfDay / 30) * 30
                CandleInterval.ONE_HOUR -> (minOfDay / 60) * 60
                CandleInterval.FOUR_HOUR -> CandleSession.anchoredStart(minOfDay, 240)
                CandleInterval.TWELVE_HOUR -> CandleSession.NXT_INTEGRATED_OPEN_MIN
                CandleInterval.NINETY_MIN -> CandleSession.ninetyMinuteStart(minOfDay)
                else -> throw IllegalArgumentException("boundaryStartTs: 지원하지 않는 target=$target")
            }

        return Instant.fromEpochSeconds(midnightEpochSec + boundaryMin * 60L)
    }

    /**
     * KIS WS H0STCNT0 STCK_CNTG_HOUR (HHMMSS 문자열) 파싱 → Instant (당일 KST 기준).
     *
     * KIS-first raw 출처:
     *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_krx() cols[1] = STCK_CNTG_HOUR = "HHMMSS" format
     *   ADR 0064 §1-4 인용.
     *
     * @param hhmmss KIS STCK_CNTG_HOUR 문자열 (예: "093015")
     * @param epochSecMidnight 당일 KST 자정 epochSecond (호출자 제공 — 날짜 경계 안전)
     * @return 해당 시각 Instant (분 단위 절사 — boundary 검사는 분 단위)
     */
    fun parseHhmmssToInstant(
        hhmmss: String,
        epochSecMidnight: Long,
    ): Instant {
        require(hhmmss.length == 6) { "STCK_CNTG_HOUR 형식 오류: '$hhmmss' (expected HHMMSS)" }
        val hour = hhmmss.substring(0, 2).toInt()
        val minute = hhmmss.substring(2, 4).toInt()
        // 분 단위 절사 (초는 버림 — boundary 검사는 분 단위)
        return Instant.fromEpochSeconds(epochSecMidnight + (hour * 60L + minute) * 60L)
    }
}
