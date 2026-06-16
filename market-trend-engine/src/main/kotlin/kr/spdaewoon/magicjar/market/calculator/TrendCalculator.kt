package kr.spdaewoon.magicjar.market.calculator

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot

/**
 * 시장 추세 계산 추상화 (ADR 0100 §2-2-B).
 *
 * **Pure math** — Spring 의존 0 (interface 자체는 Kotlin std 만).
 * service layer 에서 user pref 또는 query override 적용 후 본 메서드 호출.
 *
 * 확장: post-MVP EMA · WMA · DEMA 등 신규 구현 추가 시 본 interface plug-in (본체 변경 0).
 */
interface TrendCalculator {
    /**
     * 지수 단일 추세 계산.
     *
     * @param code KIS index code (예: 0001 · .IXIC)
     * @param candles 일봉 list ASC (오래된 → 최신). longMa 미만 시 `dataInsufficient=true` echo.
     * @param periods MA 기간 list (1~5개 · 각 1~252 · sorted ASC — 호출자 invariant 강제 의무).
     * @param crossPair? 명시 시 본 short/long 사용. null 시 maPeriods 최소/최대 auto.
     * @param isUserCustom 응답 echo — frontend default vs user 구분.
     */
    fun computeIndexTrend(
        code: String,
        candles: List<Candle>,
        periods: List<Int>,
        crossPair: Pair<Int, Int>? = null,
        isUserCustom: Boolean = false,
    ): IndexTrend

    /**
     * 섹터 ranking 계산.
     *
     * @param snapshots 섹터 1분 snapshot list (각 sector_code 의 최신 1행)
     * @param baselineSnapshots 직전 1 영업일 snapshot list (전일 대비 거래대금 정규화 용 · null 가능)
     * @param sectorDailyCandles {sectorCode → 일봉 ASC list} — RS rating + maPeriods 우상향 fit 계산 input
     * @param baselineCandles KOSPI 일봉 ASC list (Mansfield RS 기준)
     * @param periods MA 기간 list (longTop 우상향 fit 계산 input)
     * @param rsPeriod Mansfield RS 비교 기간 (영업일)
     * @param isUserCustom 응답 echo
     */
    fun computeSectorRanking(
        snapshots: List<SectorSnapshot>,
        baselineSnapshots: List<SectorSnapshot>?,
        sectorDailyCandles: Map<String, List<Candle>>,
        baselineCandles: List<Candle>,
        periods: List<Int>,
        rsPeriod: Int,
        isUserCustom: Boolean = false,
        topN: Int = TOP_N_DEFAULT,
    ): SectorRanking

    companion object {
        const val TOP_N_DEFAULT: Int = 5
    }
}
