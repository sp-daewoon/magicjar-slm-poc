package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.position.StrategyPosition

/**
 * 전략별 포지션 귀속 영속 port (ADR 0110, 분할매수 spec §3-1·§7).
 *
 * 기존 [PositionRepository](ADR 0042, KIS 잔고 합산 권위)와 분리된 전략 lot ledger port —
 * [RiskPositionPort] 패턴 정합(헌법 §6 abstract fun만, body 0건 — Spring Data query inference 함정 회피).
 *
 * - [findByUserStrategyCode] — 분할 매수/청산 평가 시 그 전략의 평단·진행도 조회(귀속 키 user+strategy+code).
 * - [findByUserCode] — 정합 검증용. user+code 합산이 Position.quantity와 일치하는지 drift 탐지(spec §3-1 불변식).
 * - [upsert] — 체결 listener가 BUY/SELL 체결 시 평단·수량·filledLots·realizedPnl·executedExitStages 갱신(R2).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 port (ADR 0023 §2-4 정합)
 */
interface StrategyPositionPort {
    fun findByUserStrategyCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPosition?

    fun findByUserCode(
        userId: String,
        code: String,
    ): List<StrategyPosition>

    fun upsert(strategyPosition: StrategyPosition): StrategyPosition
}
