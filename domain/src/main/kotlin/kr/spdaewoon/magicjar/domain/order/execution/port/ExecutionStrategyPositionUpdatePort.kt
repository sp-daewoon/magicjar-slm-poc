package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest

/**
 * 체결 반영 직후 전략별 포지션 귀속(`strategy_position`)을 갱신하는 포트 (ADR 0110, 분할매수 spec §3-1·§5, R2-2).
 *
 * 기존 [ExecutionPositionUpdatePort](user+code 합산 position, KIS 권위)와 **대칭** — 같은 체결 row 기준으로
 * 멱등 경계(event_id dedup) 뒤에서 한 번만 호출된다(order.execution 흐름 LOCK).
 * position 갱신과 동일 transaction boundary에서 strategy_position lot ledger를 동반 upsert해
 * "전략이 산 물량"을 분리 추적한다(다전략 교차 청산 방지 — ADR 0110 §2).
 *
 * ## 갱신 규칙 (spec §3-1 불변식 — 구현은 어댑터 책임)
 * - **BUY 체결**: 평단 가중평균 갱신 + `quantity`·`filledLots` 증가.
 * - **SELL 체결**: `realizedPnl += (fillPrice - avgPrice) * sellQty`, `quantity -= sellQty`.
 *   시그널 reason 이 `PARTIAL_TAKE_PROFIT#{k}` 면 체결 확정 시 `executedExitStages += k`(발행 아닌 체결 시점 마킹).
 *   `quantity == 0` 이면 `avgPrice`·`filledLots`·`executedExitStages` reset(행 유지 — 재진입 새 사이클).
 * - **MANUAL sentinel(`strategyId == 0`)** 또는 체결 수량 ≤ 0 등 귀속 불가 체결은 skip(전략 귀속 없음).
 *
 * ## drift 검증 (spec §3-1 · ADR 0110 §2-2)
 * upsert 후 `sum(strategy_position.quantity WHERE user+code) != position.quantity` 면
 * `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) 발사 — 보정은 하지 않는다(KIS 잔고 = position 이 상위 권위).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 port (ADR 0023 §2-4 정합)
 */
interface ExecutionStrategyPositionUpdatePort {
    fun applyExecution(
        request: OrderRequest,
        execution: OrderExecution,
    )
}
