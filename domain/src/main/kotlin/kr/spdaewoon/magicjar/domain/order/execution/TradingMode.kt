package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 거래 모드 (ADR 0068 §14-1 enum LOCK + 의제 A-1 정합).
 *
 * 3중 게이트 결과:
 *   MOCK = env MAGICJAR_MODE != "REAL" 시 안전 default
 *   REAL = env REAL + MAGICJAR_ALLOW_REAL=true + 사용자 동의 토글 모두 true
 *
 * KIS endpoint 분기:
 *   MOCK → https://openapivts.koreainvestment.com:29443 (VPS)
 *   REAL → https://openapi.koreainvestment.com:9443     (PROD)
 *
 * tr_id 분기:
 *   BUY  MOCK → VTTC0012U · BUY  REAL → TTTC0012U
 *   SELL MOCK → VTTC0011U · SELL REAL → TTTC0011U
 * (raw 출처: domestic_stock_functions.py L10097~L10213 def order_cash)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class TradingMode {
    MOCK,
    REAL,
}
