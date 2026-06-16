package kr.spdaewoon.magicjar.domain.screener

import java.math.BigDecimal

/**
 * Screener 단일 매칭 결과 (ADR 0071 §2-5 + spec §2-2).
 *
 * - [rank]는 ranker 호출 후 1-base 부여 (랭킹 미적용 상태에서는 0 또는 임시값).
 * - [score]는 [breakdown.total] 정합 (BigDecimal 0~1 normalize).
 *
 * @param instrumentCode 종목 코드 (KIS 6자리)
 * @param instrumentName 종목명
 * @param rank 랭킹 1-base (ranker 호출 후 부여)
 * @param score 랭킹 점수 (0~1 normalize, breakdown.total과 정합)
 * @param breakdown 점수 4 기준 분해 (signal·volume·atr·cap)
 * @param reason BUY 시그널 사유 (DSL evalResult.reason JSON 직렬화 — 한국어 또는 trigger info)
 */
data class ScreenerMatch(
    val instrumentCode: String,
    val instrumentName: String,
    val rank: Int,
    val score: BigDecimal,
    val breakdown: ScreenerScoreBreakdown,
    val reason: String,
)
