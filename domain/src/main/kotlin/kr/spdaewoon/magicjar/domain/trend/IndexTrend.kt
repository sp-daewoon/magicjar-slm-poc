package kr.spdaewoon.magicjar.domain.trend

import java.math.BigDecimal

/**
 * 지수 추세 분석 결과 DTO (ADR 0100 §2-2-C).
 *
 * service layer 에서 `TrendCalculator.computeIndexTrend(...)` 호출 결과를 그대로 응답.
 * `periodsUsed` 와 `isUserCustom` 은 frontend 가 "default vs user pref" 구분 표시 의무.
 *
 * `dataInsufficient = true` 시 frontend 는 한국어 "데이터 부족" 표시 (mock 합성 0 — `feedback_no_mock_data` 정합).
 */
data class IndexTrend(
    /** KIS index code (예: 0001=KOSPI · .IXIC=NASDAQ) */
    val code: String,
    /** {period → MA 값} map (예: {5 → 2670.12, 20 → 2685.34, 60 → 2710.55}). 결측 시 일부만 채움. */
    val ma: Map<Int, BigDecimal>,
    /** GOLDEN · DEAD · NEUTRAL — `shortMa(), longMa()` cross 분류 */
    val crossSignal: CrossSignal,
    /** UP · SIDEWAYS · DOWN — longMa 5일 변화율 기준 */
    val trendLabel: TrendLabel,
    /** 응답 echo — frontend default vs user 구분 + URL share 정합 */
    val periodsUsed: List<Int>,
    /** false = default · true = 사용자 설정 적용 */
    val isUserCustom: Boolean,
    /** true 시 candles.size < longMa — frontend "데이터 부족" 한국어 표시 의무 */
    val dataInsufficient: Boolean,
    /** 마지막 종가 (frontend 카드 표시 + AI prompt context) */
    val lastClose: BigDecimal?,
    /** 전일 대비 등락률 (%) · null 가능 (1일치 미만 데이터) */
    val changeRate: BigDecimal?,
)
