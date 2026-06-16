package kr.spdaewoon.magicjar.domain.market.order

/**
 * 주문 거부 사유 sealed class (ADR 0015 §2-1 정합).
 * order_reject_audit.reject_reason 컬럼 값과 1:1 대응.
 */
sealed class RejectReason(
    val code: String,
) {
    /** 킬 스위치 활성 */
    object KillSwitchActive : RejectReason("KILL_SWITCH")

    /** 장 세션 닫힘 (비영업 시간) */
    object MarketSessionClosed : RejectReason("SESSION_CLOSED")

    /** 영업일 아님 (공휴일·주말) */
    object MarketNotBusinessDay : RejectReason("NOT_BUSINESS_DAY")

    /** 일일 주문 한도 초과 */
    object DailyOrderLimitExceeded : RejectReason("DAILY_LIMIT")

    /** 포지션 한도 초과 */
    object PositionLimitExceeded : RejectReason("POSITION_LIMIT")

    /** 드로우다운 스탑 발동 */
    object DrawdownStopTriggered : RejectReason("DRAWDOWN_STOP")

    /** 가격 정합성 실패 */
    object PriceSanityFailed : RejectReason("PRICE_SANITY")

    /** KIS API 오류 응답 */
    data class KisApiError(
        val rtCd: String,
        val msg: String,
    ) : RejectReason("KIS_API:$rtCd")

    /** 내부 오류 */
    data class Internal(
        val message: String,
    ) : RejectReason("INTERNAL")
}
