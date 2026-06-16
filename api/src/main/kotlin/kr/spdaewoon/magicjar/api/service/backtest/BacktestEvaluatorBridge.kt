package kr.spdaewoon.magicjar.api.service.backtest

import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.springframework.stereotype.Component

/**
 * 백테스트 평가기 브릿지 — StrategyEvaluatorPort 경유 위임 (ADR 0056 §2-4-A + ADR 0004 모듈 경계).
 *
 * ### 의무 사항 (ADR 0047 + ADR 0056 + ADR 0004)
 * - ADR 0004: api ↔ consumer 직접 의존 금지 — domain port 인터페이스 경유 (헥사고날 정합)
 * - 반드시 [StrategyEvaluatorPort.evaluateBacktest] 경유 (직접 walkRoot 호출 절대 금지)
 * - timeout 1000ms 적용은 구현체(strategy-engine JvmStrategyEvaluatorAdapter) 내부에서 처리
 * - auto-disable=false LOCK은 구현체 책임
 * - strategy-eval-pool 4 fixed thread 경유는 구현체 책임
 *
 * ### 구현체
 * - strategy-engine 모듈: JvmStrategyEvaluatorAdapter (실제 평가)
 * - api 1차: HoldStrategyEvaluatorStub (Spring Boot Test + 단위 테스트용)
 *
 * 이 클래스는 단순 위임만 담당 — 비즈니스 로직 0.
 */
@Component
class BacktestEvaluatorBridge(
    private val strategyEvaluatorPort: StrategyEvaluatorPort,
) {
    /**
     * 백테스트 평가를 수행한다.
     *
     * @param strategyId 전략 ID
     * @param ast 평가할 DSL AST (buyCondition 또는 sellCondition)
     * @param slice 룩어헤드 가드를 통과한 캔들 슬라이스 (t 시점까지만)
     * @return StrategyEvalResult (signal=BUY·SELL·HOLD + reason)
     * @throws kr.spdaewoon.magicjar.domain.strategy.StrategyException.StrategyTimeoutException timeout 초과 시
     */
    fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        slice: List<Candle>,
    ): StrategyEvalResult = strategyEvaluatorPort.evaluateBacktest(strategyId, ast, slice)
}
