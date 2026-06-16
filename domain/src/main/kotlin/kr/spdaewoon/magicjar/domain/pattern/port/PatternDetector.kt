package kr.spdaewoon.magicjar.domain.pattern.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult

/**
 * 단일 패턴 detector 인터페이스 — 57개 @Component 구현체가 Spring auto-collect 대상.
 *
 * Source: pr-38-kis-pattern-raw-verification.md §8 LOCK + spec §6-3 LOCK.
 *
 * 구현체 위치: `consumer/.../application/pattern/detector/{single,double,triple}/{XxxDetector}.kt`.
 * KIS Strategy Builder `candlestick.py` 65 PATTERN_DETECTORS 1:1 port (9 제외 = 57).
 */
interface PatternDetector {
    /** 패턴 id (KIS pattern_id 정합 — catalog id 정합) */
    val id: String

    /** 최소 필요 캔들 수 (KIS L48 정합 — default 5) */
    val minCandles: Int get() = 5

    /**
     * 마지막 캔들 기준 패턴 탐지.
     *
     * @return [DetectorResult] — direction=NEUTRAL 시 미감지로 처리됨 (KIS 0 정합).
     * KIS L57 try/except 정합 — JvmPatternRecognizer가 호출자 측에서 예외를 swallow.
     */
    fun detect(candles: List<Candle>): DetectorResult
}
