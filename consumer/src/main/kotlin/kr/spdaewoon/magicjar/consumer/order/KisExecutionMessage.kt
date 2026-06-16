package kr.spdaewoon.magicjar.consumer.order

import java.math.BigDecimal

/**
 * KIS WS H0STCNI0/H0STCNI9 체결 통보 메시지 (ADR 0068 §14-6 26 컬럼 매핑 LOCK).
 *
 * raw 출처: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (def ccnl_notice)
 *
 * tr_id:
 *   H0STCNI0 = 실전 체결 통보
 *   H0STCNI9 = 모의 체결 통보
 *
 * KIS WS body는 `^` (caret) delimiter — [parseFromCaretDelimited] 사용.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
data class KisExecutionMessage(
    /** idx 0 — CUST_ID 사용자 ID (HTS 사용자 ID) */
    val custId: String,
    /** idx 1 — ACNT_NO 계좌번호 */
    val acntNo: String,
    /** idx 2 — ODER_NO 주문번호 (kis_order_no lookup key) */
    val odno: String,
    /** idx 3 — OODER_NO 원주문번호 (정정·취소 cross-ref) */
    val originalOdno: String,
    /** idx 4 — SELN_BYOV_CLS 매도매수구분 ("01"=매도, "02"=매수) */
    val sellBuyCls: String,
    /** idx 5 — RCTF_CLS 정정구분 */
    val rctfCls: String,
    /** idx 6 — ODER_KIND 주문종류 (시장가/지정가) */
    val odKind: String,
    /** idx 7 — ODER_COND 주문조건 */
    val odCond: String,
    /** idx 8 — STCK_SHRN_ISCD 종목코드 */
    val code: String,
    /** idx 9 — CNTG_QTY 체결수량 */
    val cntgQty: Int,
    /** idx 10 — CNTG_UNPR 체결단가 */
    val cntgUnpr: BigDecimal,
    /** idx 11 — STCK_CNTG_HOUR 체결시간 HHMMSS */
    val stckCntgHour: String,
    /** idx 12 — RFUS_YN 거부여부 ("Y"=거부) */
    val rfusYn: String,
    /** idx 13 — CNTG_YN 체결구분 ("1"=주문/정정/취소/거부, "2"=체결) — 흐름 분기 핵심 */
    val cntgYn: String,
    /** idx 14 — ACPT_YN 접수여부 ("1"=주문접수, "2"=확인, "3"=취소) */
    val acptYn: String,
    /** idx 15 — BRNC_NO 지점번호 */
    val brncNo: String,
    /** idx 16 — ODER_QTY 주문수량 (cross-ref) */
    val orderQty: Int,
    /** idx 17 — ACNT_NAME 계좌명 */
    val acntName: String,
    /** idx 18 — CNTG_ISNM 체결종목명 */
    val cntgIsnm: String,
    /** idx 19 — CRDT_CLS 신용구분 */
    val crdtCls: String,
    /** idx 20 — CRDT_LOAN_DATE 신용대출일자 */
    val crdtLoanDate: String,
    /** idx 21 — CNTG_ISNM40 체결종목명40 */
    val cntgIsnm40: String,
    /** idx 22 — ODER_PRC 주문가격 */
    val ordPrc: String,
) {
    companion object {
        /**
         * KIS WS body 파싱 — `^` delimiter (raw L389~L446 정합).
         *
         * @throws IllegalArgumentException 26 컬럼 미만 시
         */
        fun parseFromCaretDelimited(body: String): KisExecutionMessage {
            val parts = body.split("^")
            require(parts.size >= 23) {
                "KIS H0STCNI0/9 message expects >= 23 columns, got ${parts.size}"
            }
            return KisExecutionMessage(
                custId = parts[0].trim(),
                acntNo = parts[1].trim(),
                odno = parts[2].trim(),
                originalOdno = parts[3].trim(),
                sellBuyCls = parts[4].trim(),
                rctfCls = parts[5].trim(),
                odKind = parts[6].trim(),
                odCond = parts[7].trim(),
                code = parts[8].trim(),
                cntgQty = parts[9].trim().toIntOrNull() ?: 0,
                cntgUnpr = parts[10].trim().toBigDecimalOrNull() ?: BigDecimal.ZERO,
                stckCntgHour = parts[11].trim(),
                rfusYn = parts[12].trim(),
                cntgYn = parts[13].trim(),
                acptYn = parts[14].trim(),
                brncNo = parts[15].trim(),
                orderQty = parts[16].trim().toIntOrNull() ?: 0,
                acntName = parts[17].trim(),
                cntgIsnm = parts[18].trim(),
                crdtCls = parts[19].trim(),
                crdtLoanDate = parts[20].trim(),
                cntgIsnm40 = parts[21].trim(),
                ordPrc = parts[22].trim(),
            )
        }
    }
}
