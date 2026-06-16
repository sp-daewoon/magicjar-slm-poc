package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 거절 사유 (ADR 0068 §14-1 sealed class LOCK + ADR 0084 P0-2 KisTimeoutPendingReconcile 합류).
 *
 * 매트릭스:
 *   RealGateDenied(cond) — 3중 게이트 (§14-3) 한 조건 미충족
 *     cond ∈ {"MAGICJAR_ALLOW_REAL=false", "USER_NOT_FOUND", "CONSENT_NOT_GIVEN", "CREDENTIAL_NOT_FOUND"}
 *   IdempotencyDuplicate — Redis SETNX false (§14-2 step 2)
 *   KillSwitchOn         — KillSwitchPort.isOn() true (§14-2 step 4)
 *   KisRejected(rt, msg) — KIS REST rt_cd != "0" (§14-2 step 7)
 *   KisException(cause)  — KIS 응답 4xx 또는 parse 실패 (REJECTED 확정)
 *   KisTimeoutPendingReconcile(cause) — KIS 5xx 재시도 후 실패 또는 timeout (ADR 0084 P0-2)
 *     → caller는 REJECTED 대신 PENDING_RECONCILE 마킹 + OrderReconciliationWorker가 inquire-daily-ccld 검증
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
sealed class DenyReason {
    data class RealGateDenied(
        val condition: String,
    ) : DenyReason()

    data object IdempotencyDuplicate : DenyReason()

    data object KillSwitchOn : DenyReason()

    data class KisRejected(
        val rtCd: String,
        val msg: String,
    ) : DenyReason()

    data class KisException(
        val cause: String,
    ) : DenyReason()

    /**
     * ADR 0084 P0-2 — KIS 5xx 재시도 후 최종 실패 또는 socket timeout 발생.
     *
     * 본 분기는 클라이언트 ACK 미수신이나 KIS는 주문을 실제 접수했을 수 있는 ambiguous 상태.
     * caller([kr.spdaewoon.magicjar.consumer.order.OrderRouterKafkaListener])는 REJECTED 마킹 대신
     * PENDING_RECONCILE 상태로 보류 + [kr.spdaewoon.magicjar.consumer.order.OrderReconciliationWorker]가
     * 1분 cron으로 KIS inquire-daily-ccld 검증.
     */
    data class KisTimeoutPendingReconcile(
        val cause: String,
    ) : DenyReason()
}
