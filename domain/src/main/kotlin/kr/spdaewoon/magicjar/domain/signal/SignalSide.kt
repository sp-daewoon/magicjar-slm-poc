package kr.spdaewoon.magicjar.domain.signal

/**
 * 시그널 방향 (ADR 0066 §5-2 채택).
 *
 * ADR 0023 Signal Engine 룰 매칭 only 정합 — AI 호출 0건.
 *
 * - [BUY]: 매수 시그널 (entry 조건 만족)
 * - [SELL]: 매도 시그널 (exit 조건 만족)
 * - [HOLD]: 보류 (조건 불만족 또는 데이터 부족)
 *
 * 기존 [kr.spdaewoon.magicjar.domain.strategy.Signal] enum과 의미 동일.
 * T-SIG 트랙은 신규 도메인 패키지 `domain.signal`로 분리 — ADR 0066 §5 명세 정합.
 *
 * spec 인용: ADR 0066 §5-2.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
enum class SignalSide {
    BUY,
    SELL,
    HOLD,
}
