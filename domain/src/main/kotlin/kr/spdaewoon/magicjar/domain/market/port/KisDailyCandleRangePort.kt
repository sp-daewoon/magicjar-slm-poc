package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.time.LocalDate

/**
 * KIS 과거 일봉(1d) range 조회 포트 (ADR 0118 R-BF — 10년 전종목 일봉 backfill 캠페인 전용).
 *
 * 왜 [KisRestPort.fetchCandles] 가 아니라 별도 포트인가:
 *   - [KisRestPort.fetchCandles] 의 일봉 path(`KisRestCandleAdapter.fetchDailyCandles`)는
 *     `cursor=today` 고정 + 단일 페이지 acquire 가 **INGESTION** 버킷이다. R-BF 캠페인은
 *     ADR 0118 §5-1 LOCK 으로 **BACKFILL(BG) 버킷** 고정 의무 — USER_PATH·INGESTION 절대 침범 0.
 *   - R-BF 는 종목별 **상장일(listed_at) clamp** 된 시작 경계가 필요하다(상장 이전 false fetch 0).
 *     count 기반 fetch 는 시작 경계를 명시할 수 없다.
 *
 * 따라서 기존 INGESTION 일봉 path 를 건드리지 않고(회귀 0), BACKFILL 버킷 + 명시 range 전용
 * 메서드를 분리한다. KIS endpoint·tr_id·페이지네이션 알고리즘은 동일(FHKST03010100) — 어댑터에서 재사용.
 *
 * KIS-first raw 출처 (헌법 §3):
 *   FHKST03010100 inquire-daily-itemchartprice (period_div_code=D)
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 */
interface KisDailyCandleRangePort {
    /**
     * [from] 이상 [to] 이하 거래일의 KIS 1d candle 을 ASC 로 반환한다 (BACKFILL 버킷).
     *
     * - 과거 일봉은 settled 공식값·immutable. 호출자는 ON CONFLICT DO NOTHING(saveAll)으로 적재한다.
     * - KIS upstream/rate-limit/credential 실패는 빈 결과로 숨기지 않고 [KisDailyRangeUnavailableException]
     *   으로 전파한다(호출자가 fail-closed 재시도/이연 판단). 단, KIS 가 정상 응답했으나 해당 종목·구간 데이터가
     *   0건이면(상장 이전·거래 부재) 빈 list 반환(예외 아님 — uncovered).
     *
     * @param code 종목코드 6자리
     * @param from 시작 경계(inclusive) — 호출자가 max(today-historyYears, listed_at)로 clamp 의무
     * @param to   종료 경계(inclusive) — 보통 어제(today-1) 까지(today 봉은 야간 snapshot R2 소관)
     * @return ASC 정렬 1d Candle list. 빈 list = 해당 구간 KIS 미보유(uncovered).
     */
    fun fetchDailyCandlesInRange(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle>
}

/**
 * R-BF range fetch 가 KIS upstream/rate-limit/credential 결함으로 회복 불가능한 경우 전파.
 *
 * 빈 결과(uncovered)와 구분 — 캠페인은 본 예외를 retry/circuit/이연으로 처리하고, 종목을 완료로 마킹하지 않는다.
 */
class KisDailyRangeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
