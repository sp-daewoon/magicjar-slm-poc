package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL AST sealed root.
 *
 * Source: parser.py 6 dataclass — Indicator·Value·ArithmeticExpr·Condition·CompositeCondition
 *  + parser.py:L301-L530 재귀 하강 결과로 만들어지는 트리.
 * License: MIT
 *
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * ### Kotlin rename 규약 (KIS raw → Kotlin) — spec §3-1 LOCK
 * - `Indicator` → [IndicatorRef] (ADR 0021 도메인 Indicator 충돌 회피)
 * - `Value` → [LiteralValue] (Kotlin Value 키워드 충돌 회피)
 * - `StrategyDefinition` → [DslStrategyDefinition] (Kotlin Definition 충돌 회피)
 *
 * 외 [ArithmeticExpr] · [Condition] · [CompositeCondition] 은 KIS와 동일.
 */
sealed class DslNode
