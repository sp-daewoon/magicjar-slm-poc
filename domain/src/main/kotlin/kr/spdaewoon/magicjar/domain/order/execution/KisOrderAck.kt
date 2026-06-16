package kr.spdaewoon.magicjar.domain.order.execution

/**
 * KIS REST 주문 응답 도메인 (ADR 0068 §14-1 LOCK + §14-5 KisOrderHttpAdapter 정합).
 *
 * KIS 응답 매핑 (raw 출처: domestic_stock_functions.py L10097~L10213):
 *   kisOrderNo     ← output.ODNO              (주문번호)
 *   kisFwdgOrgno   ← output.KRX_FWDG_ORD_ORGNO (한국거래소 전송조직번호 — 정정/취소 동반)
 *   rtCd           ← rt_cd                    ("0" = 정상)
 *   msg            ← msg1                     (메시지)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
data class KisOrderAck(
    val kisOrderNo: String,
    val kisFwdgOrgno: String,
    val rtCd: String,
    val msg: String,
)
