package kr.spdaewoon.magicjar.domain.pattern

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 패턴 인식 결과 (REST·DSL 응답 공용).
 *
 * Source: spec §6-3 LOCK + pr-38-kis-pattern-raw-verification.md §6 LOCK.
 *
 * @param pattern 패턴 id (KIS pattern_id 1:1 — catalog id 정합)
 * @param startTime 시작 캔들 시각 (single=마지막 1캔들 · double=직전 2캔들 시작 · triple+=3~5캔들 첫번째)
 * @param endTime 인식 시점 (마지막 캔들 시각)
 * @param direction [Direction.BULL] / [Direction.BEAR] — [Direction.NEUTRAL]은 결과에 미포함
 * @param confidence 신뢰도 (1차 = 1.0 고정)
 * @param params 디버그 측정값 (nullable)
 */
data class PatternMatch(
    val pattern: String,
    val startTime: Instant,
    val endTime: Instant,
    val direction: Direction,
    val confidence: BigDecimal,
    val params: Map<String, BigDecimal>? = null,
)
