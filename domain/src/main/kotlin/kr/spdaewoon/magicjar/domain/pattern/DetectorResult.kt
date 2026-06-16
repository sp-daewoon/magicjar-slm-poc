package kr.spdaewoon.magicjar.domain.pattern

import java.math.BigDecimal

/**
 * PatternDetector.detect() 내부 응답.
 *
 * Source: pr-38-kis-pattern-raw-verification.md §8 LOCK + spec §6-3 LOCK.
 *
 * @param direction [Direction.NEUTRAL] 시 미감지 → JvmPatternRecognizer가 결과에서 제외 (KIS L51 정합).
 * @param confidence 1차 = 1.0 고정 (KIS 0/1 binary 정합). post-MVP 신뢰도 모델은 catalog §"R2 사이클 보강" 합류.
 * @param params 디버그용 — 실제 측정값 (예: `body_ratio=0.08`·`lower_shadow_ratio=0.65`). nullable.
 */
data class DetectorResult(
    val direction: Direction,
    val confidence: BigDecimal = BigDecimal.ONE,
    val params: Map<String, BigDecimal>? = null,
)
