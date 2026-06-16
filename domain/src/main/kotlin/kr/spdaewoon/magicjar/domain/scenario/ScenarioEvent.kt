package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant

enum class ScenarioEventType {
    CANDLE,
    SIGNAL_BUY,
    SIGNAL_SELL,
    SIGNAL_HOLD,
    RISK_ALLOWED,
    RISK_BLOCKED,
    ORDER_REQUESTED,
    ORDER_HELD,
    ORDER_REJECTED,
    ORDER_FILLED,
    POSITION_UPDATED,
    DATA_NOT_READY,
    STRATEGY_ERROR,
    FAULT_INJECTED,

    /**
     * 라이브 게이트가 boundary 평가를 skip한 사실을 리플레이에 정직 표기 (readiness P1).
     * 예: 정규장 게이트(GATE_SKIPPED:MARKET_HOURS) — 장외/휴장 boundary는 라이브에서도 시그널 발화 0.
     */
    GATE_SKIPPED,

    /**
     * 라이브에는 존재하지만 리플레이 가상 상태로 재현 불가능한 게이트를 조용히 통과시키지 않고 명시 (readiness P1).
     * 예: 실계좌 잔고/실현손익/거래빈도 기반 룰(KILL_SWITCH·DAILY_LOSS_LIMIT·TRADE_FREQUENCY).
     */
    GATE_NOT_SIMULATED,
}

enum class ScenarioSeverity {
    INFO,
    WARN,
    ERROR,
}

data class ScenarioEvent(
    val virtualTime: Instant,
    val type: ScenarioEventType,
    val severity: ScenarioSeverity,
    val title: String,
    val message: String,
    val strategyId: Long?,
    val strategyName: String?,
    val code: String?,
    val payload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(message.isNotBlank()) { "message must be non-blank" }
    }
}
