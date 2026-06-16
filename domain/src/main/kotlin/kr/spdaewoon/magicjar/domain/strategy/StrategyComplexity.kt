package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 식 정적 분석 산출물 (ADR 0047 §2-1-B).
 *
 * - [astDepth]: AST 트리 최대 depth.
 * - [nodeCount]: AST 총 노드 수 (모든 sealed class 인스턴스 포함).
 * - [indicatorCallCount]: IndicatorRef 노드 수.
 * - [estimatedEvalTimeMs]: 휴리스틱 estimate (indicatorCallCount * 4ms).
 * - [complexityClass]: 4단 분류 (LOW·MEDIUM·HIGH·CRITICAL).
 *
 * spec §6-2 LOCK.
 */
data class StrategyComplexity(
    val astDepth: Int,
    val nodeCount: Int,
    val indicatorCallCount: Int,
    val estimatedEvalTimeMs: Int,
    val complexityClass: ComplexityClass,
)
