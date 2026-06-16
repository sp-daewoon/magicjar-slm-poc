package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 식 복잡도 4단 (ADR 0047 §2-1-B).
 *
 * - LOW: AST depth ≤ 4 + 노드 ≤ 20 + indicator ≤ 5
 * - MEDIUM: AST depth ≤ 8 + 노드 ≤ 100 + indicator ≤ 15
 * - HIGH: AST depth ≤ 16 + 노드 ≤ 500 + indicator ≤ 30
 * - CRITICAL: 그 외 (depth/노드/indicator 한도 직전 — frontend 모달 경고 의무)
 *
 * spec §6-2 LOCK.
 */
enum class ComplexityClass {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}
