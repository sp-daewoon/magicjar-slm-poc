package kr.spdaewoon.magicjar.domain.pattern.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch

/**
 * 패턴 인식기 도메인 port — REST·DSL evaluator 공용.
 *
 * Source: spec §6-3 LOCK + T-DSL [PatternRecognizerStub] 시그니처 호환 의무 (5-A LOCK).
 *
 * ### 책임
 * 1. [recognize] (multi) — REST `GET /api/patterns` 1차 호출 — 다중 패턴 동시 인식.
 * 2. [recognize] (single Boolean) — DSL `pattern("hammer")` evaluator 호환.
 * 3. [consecutive] — DSL `consecutive("up")` evaluator 호환 (magicJar 자체 룰 — KIS 미존재).
 *
 * 구현체: `pattern-engine/.../pattern/JvmPatternRecognizer` (`@Component(name="patternRecognizer")`).
 * T-DSL 시점 stub 자동 비활성화 → 실제 detector 활성화 (1줄 교체 LOCK).
 */
interface PatternRecognizer {
    /**
     * 다중 패턴 인식 (REST·시그널 공용).
     *
     * @param candles 시간순 오름차순 (마지막 = 최신)
     * @param patterns 인식 대상 패턴 id 집합
     * @return matches — direction=NEUTRAL 또는 미감지는 미포함 (KIS L51·L52 정합)
     */
    fun recognize(
        candles: List<Candle>,
        patterns: Set<String>,
    ): List<PatternMatch>

    /**
     * DSL `pattern("name")` 호환 — Boolean 응답 (T-DSL stub 시그니처 정합).
     *
     * @return true if 패턴 인식 (BULL 또는 BEAR), false otherwise
     */
    fun recognize(
        name: String,
        candles: List<Candle>,
    ): Boolean

    /**
     * DSL `consecutive("up"|"down")` 호환 — Int 응답 (T-DSL stub 시그니처 정합).
     *
     * @return 마지막 캔들부터 직전으로 같은 방향 연속 횟수 (KIS 미존재 — magicJar 자체 룰)
     */
    fun consecutive(
        direction: String,
        candles: List<Candle>,
    ): Int
}
