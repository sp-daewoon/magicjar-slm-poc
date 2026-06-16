package kr.spdaewoon.magicjar.domain.position

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 전략별 포지션 귀속 도메인 — ownership 토대 (ADR 0110, 분할매수 spec §3-1).
 *
 * 기존 [Position](user+code 합산, KIS 잔고 권위)은 유지하고, 그 아래에 전략별 lot ledger를 둔다.
 * 동일 종목을 2전략이 동시 운용해도 "이 전략이 산 물량"만 추적·청산하기 위한 1급 도메인.
 *
 * ## 불변식 (R1은 KDoc 명세만 — 갱신/청산 로직 구현은 R2 체결 listener 책임)
 *
 * - **정합**: `sum(StrategyPosition.quantity WHERE user+code) == Position.quantity`.
 *   drift 시 KIS 잔고(Position)가 상위 권위(ADR 0106 §6) — `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) 후 보수적으로 Position 우선.
 * - **평단 갱신(BUY 체결)**: `avgPrice = (avgPrice*quantity + fillPrice*fillQty) / (quantity+fillQty)`,
 *   `quantity += fillQty`, `filledLots += 1`.
 * - **부분/전량 청산(SELL 체결)**: `realizedPnl += (fillPrice - avgPrice)*sellQty`, `quantity -= sellQty`.
 * - **전량 청산 reset**: `quantity == 0`(STOP_LOSS·최종 TAKE_PROFIT 전량 청산)이면 `avgPrice`·`filledLots`·
 *   `executedExitStages` 모두 reset(행은 유지 — 재진입 시 새 사이클). 부분 청산만으로는 reset 안 함(단계 단조 진행 보존).
 * - **부분 익절 중복 발화 방지**: PartialExit 각 단계는 1회만 발화. `evaluatePartialTakeProfit`는
 *   `stageIndex !in executedExitStages` 일 때만 SELL을 발행하고, **체결 확정 시** `executedExitStages += stageIndex`
 *   (발행 시점이 아니라 체결 시점 마킹 — 미체결 reject 시 재시도 가능). [filledLots](진입 단조 카운터)와 대칭 구조.
 *
 * ## 귀속 키
 *
 * OrderIntent·order_execution은 이미 `strategyId`를 보유 → 체결 listener가 strategyId로 StrategyPosition upsert.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 도메인 (ADR 0023 §2-4 정합)
 */
data class StrategyPosition(
    val id: Long? = null,
    val userId: String,
    val strategyId: Long,
    val code: String,
    /** 이 전략이 현재 보유한 수량 (분할 누적 - 청산). 0이면 청산(행 유지). */
    val quantity: Int,
    /** 이 전략의 평단 (분할 매수로 가중평균 갱신). quantity=0이면 reset 대상. */
    val avgPrice: BigDecimal,
    /** scale-in 진행도 — 체결된 분할 회차 수. totalSlices 도달 시 추가 진입 무시(평단 오염 방지). */
    val filledLots: Int = 0,
    /** 이 전략의 누적 실현 손익 (부분/전량 청산 누적). */
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    /** 이미 발화·체결된 PartialExit 단계 인덱스 집합 — 부분 익절 중복 발화 방지. filledLots와 대칭. */
    val executedExitStages: Set<Int> = emptySet(),
    val openedAt: Instant,
    val updatedAt: Instant,
) {
    /** 전량 청산 여부 (Position.isClosed 패턴 정합). */
    val isClosed: Boolean get() = quantity == 0
}
