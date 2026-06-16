package kr.spdaewoon.magicjar.domain.trend

import java.math.BigDecimal

/**
 * 섹터 ranking 결과 DTO (ADR 0100 §2-2-C).
 *
 * - `todayTop` — 오늘 강세 sector 5 (등락률 0.7 + 거래대금 0.3 가중)
 * - `longTop` — 장기 강세 sector 5 (Mansfield RS rsPeriod 기준 vs KOSPI + maPeriods 우상향 fit)
 *
 * `periodsUsed` · `rsPeriod` echo — frontend default vs user 구분 + URL share 정합.
 */
data class SectorRanking(
    val todayTop: List<SectorRankEntry>,
    val longTop: List<SectorRankEntry>,
    val periodsUsed: List<Int>,
    val rsPeriod: Int,
    val isUserCustom: Boolean,
)

/**
 * 섹터 ranking 한 행.
 *
 * `score` 는 todayTop / longTop 본질이 다름:
 * - todayTop: 등락률 0.7 + 거래대금 (전일比 정규화) 0.3
 * - longTop: Mansfield RS + maPeriods 우상향 fit 가산점
 *
 * `metric` 은 frontend tooltip 보조 정보 (예: "RS=1.12 · 우상향 fit=3/3").
 */
data class SectorRankEntry(
    val sectorCode: String,
    val sectorName: String,
    val changeRate: BigDecimal,
    val tradeValue: BigDecimal?,
    val score: BigDecimal,
    val metric: String,
)
