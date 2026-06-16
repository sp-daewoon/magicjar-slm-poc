package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest

/**
 * 체결 반영 직후 내부 position을 갱신하는 포트.
 *
 * OrderExecution은 WS와 REST backfill 양 경로에서 들어오므로, 포지션 갱신도 동일 체결 row 기준으로
 * 멱등 경계(event_id dedup) 뒤에서 한 번만 수행한다.
 */
interface ExecutionPositionUpdatePort {
    fun applyExecution(
        request: OrderRequest,
        execution: OrderExecution,
    )
}
