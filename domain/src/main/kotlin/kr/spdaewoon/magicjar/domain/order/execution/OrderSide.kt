package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 매수/매도 구분 (ADR 0068 §14-1 enum LOCK).
 *
 * KIS WS H0STCNI0 SELN_BYOV_CLS 매핑:
 *   "01" = SELL · "02" = BUY
 * (raw 출처: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderSide {
    BUY,
    SELL,
}
