package kr.spdaewoon.magicjar.pattern

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.pattern.helper.ConsecutiveDirectionCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 57 패턴 인식기 (KIS Strategy Builder candlestick.py 정합).
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py
 * License: MIT
 * Catalog: pr-38-kis-pattern-raw-verification.md §4 진입점 LOCK + spec §6-3 + §5-C LOCK
 *
 * ### 책임
 * 1. 57 [PatternDetector] @Component 자동 수집 → registry Map 등록 (`detectors.associateBy { it.id }`).
 * 2. KIS L48 정합 — `candles.size < 5` 시 emptyList() 반환.
 * 3. KIS L52 정합 — `registry[id] == null` 시 미감지 graceful (KIS는 PATTERN_DETECTORS.get(...) is None → 0).
 * 4. KIS L57 정합 — detector 예외는 swallow + log.debug. 사용자 화면 응답 끊지 않음.
 * 5. T-DSL [PatternRecognizerStub] 시그니처 호환 — `@Component(name="patternRecognizer")` 자동 비활성화.
 *
 * ### bean name LOCK
 * `@Component("patternRecognizer")` — T-DSL `PatternRecognizerStub`의
 * `@ConditionalOnMissingBean(name = ["patternRecognizer"])`이 본 bean 등록 시 자동 비활성화.
 * 이름 정합 누락 시 stub과 본 bean 동시 등록 → NoUniqueBeanDefinitionException risk (spec §7-2 가드).
 *
 * ### detectors.size 검증
 * `init { require(detectors.size == 57) }` — 56 또는 58 등록 시 부팅 실패 + 명확한 에러 메시지.
 */
@Component("patternRecognizer")
class JvmPatternRecognizer(
    detectors: List<PatternDetector>,
) : PatternRecognizer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val registry: Map<String, PatternDetector> = detectors.associateBy { it.id }

    init {
        require(detectors.size == EXPECTED_DETECTOR_COUNT) {
            "PatternDetector count mismatch: expected $EXPECTED_DETECTOR_COUNT (catalog), actual ${detectors.size}. " +
                "ids=${detectors.map { it.id }.sorted()}"
        }
        require(registry.size == detectors.size) {
            "PatternDetector duplicate ids detected: total=${detectors.size}, unique=${registry.size}"
        }
        log.info(
            "Registered {} pattern detectors (R1 active=10, stub=47, KIS-first candlestick.py 정합)",
            detectors.size,
        )
    }

    override fun recognize(
        candles: List<Candle>,
        patterns: Set<String>,
    ): List<PatternMatch> {
        if (candles.size < MIN_CANDLES) return emptyList() // KIS L48 정합
        val last = candles.last()
        return patterns.mapNotNull { id ->
            val detector = registry[id] ?: return@mapNotNull null // KIS L52 미감지 graceful
            try {
                val result = detector.detect(candles)
                if (result.direction == Direction.NEUTRAL) {
                    null
                } else {
                    PatternMatch(
                        pattern = id,
                        startTime = last.ts,
                        endTime = last.ts,
                        direction = result.direction,
                        confidence = result.confidence,
                        params = result.params,
                    )
                }
            } catch (ex: Exception) {
                // KIS L57 try/except 정합 — 미감지 graceful + 로그만
                log.debug("Detector '{}' threw exception, treating as no-match: {}", id, ex.message)
                null
            }
        }
    }

    override fun recognize(
        name: String,
        candles: List<Candle>,
    ): Boolean {
        // T-DSL stub 시그니처 호환 (`pattern("hammer")` evaluator 호환)
        return recognize(candles, setOf(name)).isNotEmpty()
    }

    override fun consecutive(
        direction: String,
        candles: List<Candle>,
    ): Int {
        // T-DSL stub 시그니처 호환 — magicJar own (KIS 미존재 — spec §8-7)
        return ConsecutiveDirectionCalculator.consecutive(direction, candles)
    }

    companion object {
        /** catalog 57 LOCK — pr-38-kis-pattern-raw-verification.md §2 매핑 정합. */
        private const val EXPECTED_DETECTOR_COUNT = 57

        /** KIS L48 `len(df) < 5` 정합. */
        private const val MIN_CANDLES = 5
    }
}
