package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 같은 종목 open SELL(주로 `auto-tp:` 익절 LIMIT 선주문) 취소 공용 컴포넌트
 * (DRY — [PositionStopLossTickWatcher] 손절 안전망 + [kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener] DSL 매도 시그널 공유).
 *
 * ## 왜 필요한가 (자본 경로 hotfix — "먼저 오는 쪽이 이긴다" · ADR 0114 §5 보강)
 * KIS 국내주식은 보유 수량을 미체결 SELL 주문(`auto-tp:` 익절 LIMIT 선주문)이 **잠근다**. 그 상태에서 매도 시그널/손절이
 * 그대로 SELL을 발행하면 KIS가 "매도가능수량 부족"으로 거부 → 매도 의도가 무력화된다. 매도 시그널·손절은
 * **먼저 와서 청산을 의도한 쪽**이므로, resting 익절 선주문을 **선취소**해 수량을 풀어준 뒤 발행해야 한다.
 * (과거 [SignalGeneratedKafkaListener] 주석의 "잔고 가드로 자연 수렴"은 잘못된 가정 — 실제로는 발행 거부.)
 *
 * ## fail-closed 가드 (사용자 수동 주문 침범 금지)
 * 같은 종목 open SELL을 분류해 처리한다:
 * - `AUTO` + 같은 전략 SELL(익절 TP·손절 등) → KIS 취소 시도. 취소 성공해야 청산 진행(이중 SELL 회피).
 * - 수동([OrderSource.MANUAL])/타전략 SELL → **취소하지 않고 [Result.AbortedForeignSell]** (우리가 모르는 SELL 의도 침범 금지).
 * - KIS ACK 미확정(kisOrderNo/kisFwdgOrgno 없음) → **[Result.HeldPendingAck]**(다음 기회 재평가 — 취소 API에 ODNO 필수).
 * - 취소 API 실패 → **[Result.CancelFailed]**(다음 기회 재시도).
 * - 같은 종목 open SELL 없음 → **[Result.Cleared]**(즉시 발행 가능).
 *
 * ## caller별 추가 dedup (본 컴포넌트 밖 — 의도 격리)
 * - 손절 watcher: 자신의 기존 `auto-sl:` open SELL이면 [excludeIdempotencyPrefix]로 제외 후 중복 발행 skip.
 * - 매도 시그널 listener: 자기 자신이 익절 LIMIT 재발행이면 동일가·동일수량 `auto-tp:` 정합 시 발행 skip(caller 판정).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class RestingSellOrderCanceller(
    private val kisOrderPort: KisOrderPort,
    private val orderCancelledKafkaProducer: OrderCancelledKafkaProducer,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
) {
    private val log = LoggerFactory.getLogger(RestingSellOrderCanceller::class.java)

    /**
     * 같은 (userId·code·strategyId) open SELL을 취소하고 청산 진행 가능 여부를 반환.
     *
     * @param orderRepo open SELL 조회/취소 영속 포트(caller가 미합류 시 fail-closed 처리 후 호출).
     * @param excludeIdempotencyPrefix 자신이 발행한 동일 종류 SELL을 분류에서 제외(예: 손절 watcher의 `auto-sl`).
     *        제외된 prefix open SELL이 하나라도 있으면 caller가 중복 판정하도록 [Result.OwnDuplicate] 반환.
     */
    fun cancelRestingSells(
        orderRepo: OrderRequestRepository,
        userId: String,
        strategyId: Long,
        code: String,
        excludeIdempotencyPrefix: String? = null,
    ): Result {
        val openSells =
            orderRepo
                .findOpenByUserId(userId)
                .filter { it.code == code && it.side == OrderSide.SELL && it.status in OPEN_STATUSES }
        if (openSells.isEmpty()) return Result.Cleared

        // caller 자신의 동일 종류 open SELL(예: auto-sl) → 중복 발행 금지(caller가 skip 판정).
        if (excludeIdempotencyPrefix != null &&
            openSells.any { it.idempotencyKey.startsWith("$excludeIdempotencyPrefix:") }
        ) {
            log.info(
                "resting SELL skip: 이미 open '{}' SELL 존재 userId={} strategyId={} code={}",
                excludeIdempotencyPrefix,
                userId,
                strategyId,
                code,
            )
            return Result.OwnDuplicate
        }

        for (order in openSells) {
            if (order.source != OrderSource.AUTO || order.strategyId != strategyId) {
                log.error(
                    "resting SELL fail-closed: cancel 대상 아닌 open SELL 존재(수동/타전략) — 청산 중단 " +
                        "userId={} code={} orderId={} source={} strategyId={}",
                    userId,
                    code,
                    order.id,
                    order.source,
                    order.strategyId,
                )
                return Result.AbortedForeignSell(order)
            }
            if (order.kisOrderNo.isNullOrBlank() || order.kisFwdgOrgno.isNullOrBlank()) {
                log.warn(
                    "resting SELL 보류: KIS ACK 미확정 open SELL 존재(다음 기회 재평가) userId={} code={} orderId={} status={}",
                    userId,
                    code,
                    order.id,
                    order.status,
                )
                return Result.HeldPendingAck(order)
            }
            when (val cancelResult = kisOrderPort.cancel(order, order.mode)) {
                is OrderResult.Right -> publishCancelled(orderRepo, order)
                is OrderResult.Left -> {
                    log.warn(
                        "resting SELL 보류: open SELL 취소 실패(다음 기회 재시도) userId={} code={} orderId={} reason={}",
                        userId,
                        code,
                        order.id,
                        cancelResult.value,
                    )
                    return Result.CancelFailed(order)
                }
            }
        }
        log.info(
            "resting SELL 취소 완료: cancelled={} userId={} strategyId={} code={}",
            openSells.size,
            userId,
            strategyId,
            code,
        )
        return Result.Cancelled(openSells)
    }

    private fun publishCancelled(
        orderRepo: OrderRequestRepository,
        order: OrderRequest,
    ) {
        val orderId = order.id
        val marked =
            if (orderId != null) {
                runCatching { orderRepo.markCompleted(orderId, Instant.now(), OrderStatus.CANCELLED) }
                    .onFailure { e -> log.error("resting SELL cancel markCompleted 실패: orderId={} err={}", orderId, e.message) }
                    .getOrNull()
            } else {
                null
            }
        val cancelled = marked ?: order.copy(status = OrderStatus.CANCELLED, completedAt = Instant.now())
        orderCancelledKafkaProducer.publish(cancelled)
        accountSnapshotCoordinatorProvider
            ?.getIfAvailable()
            ?.invalidate(
                userId = cancelled.userId,
                mode = cancelled.mode,
                reason = "RESTING_SELL_CANCELLED",
                triggerKisOrderNo = cancelled.kisOrderNo,
            )
    }

    /**
     * 청산 진행 가능 여부 sealed 결과 — caller(when 분기에서 컴파일 타임 안전).
     * [Cleared]·[Cancelled]만 발행 진행 가능(`canProceed`). 나머지는 보류/중단.
     */
    sealed interface Result {
        /** 같은 종목 open SELL 없음 → 즉시 발행 가능. */
        data object Cleared : Result

        /** AUTO 같은 전략 open SELL 모두 취소 성공 → 발행 가능. */
        data class Cancelled(
            val cancelledOrders: List<OrderRequest>,
        ) : Result

        /** caller 자신의 동일 종류 open SELL이 이미 존재(예: auto-sl) → caller가 중복 발행 skip. */
        data object OwnDuplicate : Result

        /** 수동/타전략 open SELL 존재 → 침범 금지로 청산 중단. */
        data class AbortedForeignSell(
            val order: OrderRequest,
        ) : Result

        /** KIS ACK 미확정 open SELL 존재 → 취소 불가, 다음 기회 재평가 보류. */
        data class HeldPendingAck(
            val order: OrderRequest,
        ) : Result

        /** KIS 취소 API 실패 → 다음 기회 재시도 보류. */
        data class CancelFailed(
            val order: OrderRequest,
        ) : Result

        val canProceed: Boolean
            get() = this is Cleared || this is Cancelled
    }

    companion object {
        private val OPEN_STATUSES =
            setOf(OrderStatus.REQUESTED, OrderStatus.ACK, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_RECONCILE)
    }
}
