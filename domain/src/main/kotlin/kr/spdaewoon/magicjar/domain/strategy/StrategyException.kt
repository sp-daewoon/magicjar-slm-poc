package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 DSL 도메인 예외 (ADR 0047 §2-1).
 *
 * spec §6-2 LOCK.
 */
sealed class StrategyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * DSL 텍스트 syntax 오류 — tokenize / parse 단계.
     */
    class DslSyntaxException(
        detail: String,
    ) : StrategyException("DSL syntax error: $detail")

    /**
     * AST depth 한도 초과 (default 32, ADR 0047 §2-1-1).
     */
    class DslDepthExceededException(
        depth: Int,
        limit: Int,
    ) : StrategyException("AST depth $depth > limit $limit")

    /**
     * AST 총 노드 수 한도 초과 (default 10000, ADR 0047 §2-1-2).
     */
    class DslNodeCountExceededException(
        count: Int,
        limit: Int,
    ) : StrategyException("AST node count $count > limit $limit")

    /**
     * IndicatorRef 호출 수 한도 초과 (default 50, ADR 0047 §2-1-3).
     */
    class DslIndicatorCallExceededException(
        count: Int,
        limit: Int,
    ) : StrategyException("Indicator calls $count > limit $limit")

    /**
     * 화이트리스트에 없는 식별자 (ADR 0047 §2-1-A).
     */
    class UnknownDslSymbolException(
        symbol: String,
    ) : StrategyException("Unknown DSL identifier: $symbol")

    /**
     * 평가 timeout (ADR 0047 §2-2-A · §2-2-D auto-disable trigger).
     */
    class StrategyTimeoutException(
        strategyId: Long,
        timeoutMs: Long,
    ) : StrategyException("Strategy $strategyId timeout ${timeoutMs}ms")

    /**
     * 평가에 필요한 캔들 부족 (Condition.requiredDays > candles.size).
     */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : StrategyException("Insufficient candles: required=$required, actual=$actual")
}
