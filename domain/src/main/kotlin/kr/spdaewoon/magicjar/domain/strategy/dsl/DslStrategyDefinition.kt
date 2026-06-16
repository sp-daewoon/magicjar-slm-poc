package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL StrategyDefinition → [DslStrategyDefinition] rename.
 *
 * Source: parser.py:L555 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * `.kis.yaml` 컨테이너 — buyCondition · sellCondition은 텍스트 1줄 → [DslNode] AST.
 *
 * params는 initial_capital · position_size 등 KIS 파라미터 (Map<String, Any>) — magicJar 확장 wrapper [StrategyDefinition]에서 별도 키로 흡수 가능.
 */
data class DslStrategyDefinition(
    val name: String,
    val nameKo: String? = null,
    val buyCondition: DslNode? = null,
    val sellCondition: DslNode? = null,
    val buyConditionText: String? = null,
    val sellConditionText: String? = null,
    val params: Map<String, Any> = emptyMap(),
)
