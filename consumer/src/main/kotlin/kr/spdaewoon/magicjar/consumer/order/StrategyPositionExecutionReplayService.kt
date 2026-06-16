package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionReplayLockPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 이미 저장된 order_execution을 strategy_position으로 재생하는 누락 복구 서비스.
 *
 * WS_PREINSERT race 경로는 처음 체결을 strategyId=0 sentinel 주문에 붙인 뒤, ACK 시점에 원 주문으로
 * order_execution을 재배정한다. 그 첫 저장 시점에는 전략 귀속이 skip되므로, 재배정 후 이 서비스가
 * 원 주문(strategyId 포함) 기준으로 execution을 한 번 재생한다.
 *
 * ## TOCTOU 직렬화 (over-count 0 보강)
 * `findByUserStrategyCode`(존재확인) → `applyExecution`(quantity 가산) 은 read-modify-write 구간이다.
 * ACK 병합 경로와 REST backfill 경로가 같은 주문에 동시 진입하면 둘 다 `existing == null` 을 통과해
 * 같은 execution 이 이중 가산(over-count → 유령 물량 → 실손실)될 수 있다. 이를 닫기 위해 본 메서드 전체를
 * 단일 `@Transactional` 로 묶고, 시작에서 [StrategyPositionReplayLockPort] 로 (user, strategyId, code) 키에
 * advisory xact lock 을 선취한다. lock 선취 **이후** 존재확인을 수행하므로, 먼저 행을 만든 트랜잭션이
 * commit(lock 해제)된 뒤에야 두 번째가 읽어 `existing != null` 로 skip 한다(멱등).
 *
 * lock 을 잡지 못하면([LockResult.SKIPPED] — 트랜잭션 부재·DB 오류) replay 자체를 우회한다.
 * 누락은 2차 ledger·KIS 권위·drift WARN 으로 회복하므로 안전하지만, over-count 위험을 안고 진행하지는 않는다.
 *
 * ## under-count 명세 고정 (의도된 동작 — 수정 대상 아님)
 * 같은 전략·종목이 **다른 주문**으로 이미 보유 중이면 `findByUserStrategyCode != null` 이라 본 replay 는 skip 한다.
 * 그 결과 sentinel 에 붙었던 누락 execution 이 strategy_position 에 반영되지 않을 수 있다(under-count).
 * 이는 의도된 보수적 선택이다 — replay 는 행 단위 존재만 보고 execution 단위 멱등을 보장하지 않으므로,
 * 이미 행이 있는 상태에서 가산하면 오히려 이중계상(over-count) 위험이 생긴다. under-count 는
 * `position`(KIS 잔고 권위) 합산과의 drift 로 탐지되어 WARN(또는 ADR 0115 auto-correct)으로 회복되며,
 * 자본 흐름상 유령 물량을 만들지 않으므로 over-count 보다 안전하다.
 */
@Component
class StrategyPositionExecutionReplayService(
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val strategyPositionUpdatePortProvider: ObjectProvider<ExecutionStrategyPositionUpdatePort>,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
    private val replayLockPortProvider: ObjectProvider<StrategyPositionReplayLockPort>,
) {
    private val log = LoggerFactory.getLogger(StrategyPositionExecutionReplayService::class.java)

    @Transactional
    fun replayIfMissing(
        request: OrderRequest,
        reason: String,
    ): Int {
        val orderId = request.id ?: return 0
        if (request.strategyId == 0L) return 0

        val strategyPositionPort = strategyPositionPortProvider.getIfAvailable() ?: return 0
        val updatePort = strategyPositionUpdatePortProvider.getIfAvailable() ?: return 0
        val executionRepository = orderExecutionRepositoryProvider.getIfAvailable() ?: return 0

        // TOCTOU 직렬화 — 존재확인 전에 (user, strategyId, code) advisory xact lock 선취.
        // lock 미획득(트랜잭션 부재·DB 오류)이면 over-count 회피를 위해 replay skip.
        val lockPort = replayLockPortProvider.getIfAvailable() ?: return 0
        val lockResult = lockPort.lockForReplay(request.userId, request.strategyId, request.code)
        if (lockResult != StrategyPositionReplayLockPort.LockResult.ACQUIRED) {
            log.warn(
                "strategy_position replay skip — advisory lock 미획득(이중계상 회피 우선): orderId={} strategyId={} code={} reason={} lock={}",
                orderId,
                request.strategyId,
                request.code,
                reason,
                lockResult,
            )
            return 0
        }

        val existing =
            try {
                strategyPositionPort.findByUserStrategyCode(request.userId, request.strategyId, request.code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position replay 기존 row 조회 실패 — skip: orderId={} strategyId={} code={} reason={} err={}",
                    orderId,
                    request.strategyId,
                    request.code,
                    reason,
                    e.message,
                )
                return 0
            }
        if (existing != null) return 0

        val executions =
            try {
                executionRepository.findByOrderId(orderId)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position replay execution 조회 실패 — skip: orderId={} strategyId={} code={} reason={} err={}",
                    orderId,
                    request.strategyId,
                    request.code,
                    reason,
                    e.message,
                )
                return 0
            }
        if (executions.isEmpty()) return 0

        var replayed = 0
        for (execution in executions) {
            runCatching { updatePort.applyExecution(request, execution) }
                .onSuccess { replayed++ }
                .onFailure { e ->
                    log.warn(
                        "strategy_position replay 적용 실패: orderId={} executionId={} strategyId={} code={} reason={} err={}",
                        orderId,
                        execution.id,
                        request.strategyId,
                        request.code,
                        reason,
                        e.message,
                    )
                }
        }

        if (replayed > 0) {
            log.warn(
                "strategy_position replay 완료: orderId={} strategyId={} code={} executions={} replayed={} reason={}",
                orderId,
                request.strategyId,
                request.code,
                executions.size,
                replayed,
                reason,
            )
        }
        return replayed
    }
}
