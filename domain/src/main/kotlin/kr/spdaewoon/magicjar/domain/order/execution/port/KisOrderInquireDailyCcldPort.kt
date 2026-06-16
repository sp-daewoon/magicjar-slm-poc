package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.time.LocalDate

/**
 * KIS 주식일별주문체결조회 어댑터 포트 (ADR 0084 §2-2 P0-2 — 외부 코드 리뷰 결함 #3).
 *
 * Phase 1 consumer [kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisOrderInquireDailyCcldHttpAdapter]가 구현.
 *
 * ### raw 출처 (헌법 §3 KIS-first — 2026-05-18 WebFetch verbatim LOCK)
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
 *
 * ### API URL (raw L21)
 * - `/uapi/domestic-stock/v1/trading/inquire-daily-ccld`
 *
 * ### tr_id 매트릭스 (raw L106~L121 verbatim)
 * - env=real + pd=inner (3개월 이내) → `TTTC0081R`
 * - env=demo + pd=inner (3개월 이내) → `VTTC0081R`
 * - env=real + pd=before (3개월 이전) → `CTSC9215R`
 * - env=demo + pd=before (3개월 이전) → `VTSC9215R`
 *
 * ### 사용처 (ADR 0084 P0-2)
 * - [kr.spdaewoon.magicjar.consumer.order.OrderReconciliationWorker] cron 1분 — PENDING_RECONCILE 상태 row를
 *   ODNO 또는 (date range + idempotency 매핑)으로 KIS 실제 상태 검증.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface KisOrderInquireDailyCcldPort {
    /**
     * 일별 주문체결 조회 — ODNO 단건 검색 (PENDING_RECONCILE 검증용 주 경로).
     *
     * raw L141~L155 (params verbatim):
     * - CANO·ACNT_PRDT_CD: 계좌번호·상품코드
     * - INQR_STRT_DT·INQR_END_DT: 조회 시작·종료 일자 (YYYYMMDD)
     * - SLL_BUY_DVSN_CD: "00" 전체
     * - CCLD_DVSN: "00" 전체 (체결+미체결 모두)
     * - INQR_DVSN: "00" 역순 (최근 우선)
     * - INQR_DVSN_3: "00" 전체
     * - ODNO: 조회할 주문번호 (단건 검색)
     *
     * 반환 분기:
     * - [OrderInquireResult.Found]: KIS에 주문번호 존재 — ACK 결정 + 체결 상태 동반 반환
     * - [OrderInquireResult.NotFound]: KIS 응답 정상 but 해당 ODNO 미존재 — KIS 미접수 (REJECTED 확정)
     * - [OrderInquireResult.Failed]: KIS 응답 실패 (5xx·timeout·parse 실패) — 재시도 후 EXPIRED 가능
     */
    fun inquireByOrderNo(
        userId: String,
        mode: TradingMode,
        kisOrderNo: String,
        inquireStartDate: LocalDate,
        inquireEndDate: LocalDate,
    ): OrderInquireResult
}

/**
 * KIS inquire-daily-ccld 응답 분기 (ADR 0084 P0-2 — OrderReconciliationWorker 분기점).
 */
sealed class OrderInquireResult {
    /**
     * KIS에 주문 발견 — 실제 접수됐던 케이스. PENDING_RECONCILE → ACK 또는 FILLED 전이.
     *
     * @property odno KIS 주문번호 (ODNO)
     * @property krxFwdgOrdOrgno KIS 정정/취소 의무 동반 헤더
     * @property filledQty 체결 수량 (TOT_CCLD_QTY) — 0이면 ACK only
     * @property orderQty 주문 수량 (ORD_QTY)
     * @property ccldStatus KIS 체결 상태 코드 (raw output1.ccld_dvsn 등 — 본 baseline은 분기 판정용 metadata)
     */
    data class Found(
        val odno: String,
        val krxFwdgOrdOrgno: String,
        val filledQty: Int,
        val orderQty: Int,
        val ccldStatus: String,
    ) : OrderInquireResult()

    /**
     * KIS 응답 정상 but 해당 ODNO 미존재 — 즉 KIS 미접수 확정. PENDING_RECONCILE → REJECTED 전이.
     */
    data object NotFound : OrderInquireResult()

    /**
     * KIS 응답 자체 실패 (5xx·timeout·parse 결함) — 다음 cron tick에 재시도. 15분 timeout 시 EXPIRED.
     *
     * @property cause 실패 사유 (HTTP_5XX·TIMEOUT·PARSE_ERROR 등)
     */
    data class Failed(
        val cause: String,
    ) : OrderInquireResult()
}
