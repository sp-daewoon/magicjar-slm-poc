package kr.spdaewoon.magicjar.domain.trend

/**
 * MA cross signal 분류 (ADR 0100 §2-2-D 표준 정의 LOCK).
 *
 * 직전 봉 ↔ 현재 봉 cross 명세:
 * - `GOLDEN` — `shortMa[t-1] ≤ longMa[t-1] AND shortMa[t] > longMa[t]` (상향 돌파)
 * - `DEAD` — `shortMa[t-1] ≥ longMa[t-1] AND shortMa[t] < longMa[t]` (하향 돌파)
 * - `NEUTRAL` — 그 외 (cross 0) · 데이터 부족 시 default
 */
enum class CrossSignal {
    GOLDEN,
    DEAD,
    NEUTRAL,
}

/**
 * 추세 라벨 (ADR 0100 §2-2-D 표준 정의 LOCK).
 *
 * longMa 기준 · 직전 5 거래일 변화율 비교:
 * - `UP` — 종가 > longMa[t] AND `(longMa[t] − longMa[t-5]) / longMa[t-5] ≥ +0.005` (우상향 0.5% 이상)
 * - `DOWN` — 종가 < longMa[t] AND `(longMa[t] − longMa[t-5]) / longMa[t-5] ≤ -0.005` (우하향 -0.5% 이하)
 * - `SIDEWAYS` — 그 외 · 데이터 부족 시 default
 */
enum class TrendLabel {
    UP,
    SIDEWAYS,
    DOWN,
}
