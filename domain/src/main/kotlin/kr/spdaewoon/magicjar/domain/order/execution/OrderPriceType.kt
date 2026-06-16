package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 가격 유형 (ADR 0068 §14-1 enum LOCK + 의제 C-1).
 *
 * KIS ORD_DVSN 매핑:
 *   MARKET → "01" (시장가, ORD_UNPR="0")
 *   LIMIT  → "00" (지정가, ORD_UNPR=limitPrice)
 * (raw 출처: domestic_stock_functions.py L10097~L10213 def order_cash)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderPriceType(
    val ordDvsn: String,
) {
    MARKET("01"),
    LIMIT("00"),
}
