package kr.spdaewoon.magicjar.domain.pattern

/**
 * 패턴 인식 도메인 예외 (sealed).
 *
 * Source: spec §6-3 LOCK.
 *
 * 단 KIS L57 try/except 정합으로 JvmPatternRecognizer는 detector 예외를 swallow + null 반환 의무.
 * 본 sealed class는 호출자(REST controller·DSL evaluator)가 명시적으로 throw 또는 catch 시 사용.
 */
sealed class PatternException(
    message: String,
) : RuntimeException(message) {
    /**
     * catalog에 등록되지 않은 패턴 id 호출.
     *
     * KIS L52 `PATTERN_DETECTORS.get(pattern_id) is None` 정합 — 단 KIS는 0 반환 (graceful).
     * REST 호출자 graceful 처리 의무 시점은 throw 안 함. 명시적으로 strict 모드일 때만 throw.
     */
    class UnknownPatternException(
        id: String,
    ) : PatternException("Unknown pattern '$id' (not in catalog 57)")

    /**
     * 캔들 부족 (KIS L48 `len(df) < 5` 정합).
     *
     * 단 JvmPatternRecognizer는 emptyList() 반환 (graceful) — strict 모드 호출자만 throw.
     */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : PatternException("Insufficient candles: required=$required, actual=$actual")
}
