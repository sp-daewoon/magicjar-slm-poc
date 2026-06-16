package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 평가 결과 시그널 (ADR 0022 §2-4).
 *
 * - BUY: entry 조건 만족
 * - SELL: exit 조건 만족
 * - HOLD: 조건 불만족 또는 데이터 부족
 */
enum class Signal {
    BUY,
    SELL,
    HOLD,
}
