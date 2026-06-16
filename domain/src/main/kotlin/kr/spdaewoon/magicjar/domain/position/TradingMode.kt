package kr.spdaewoon.magicjar.domain.position

/**
 * 거래 모드 (ADR 0067 §5-4 + ADR 0011 정합).
 *
 * - [MOCK] — 모의계좌 (개발·dogfooding)
 * - [REAL] — 실계좌 (`MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 이중 게이트)
 *
 * spec 인용: ADR 0067 §5-4.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
enum class TradingMode {
    MOCK,
    REAL,
}
