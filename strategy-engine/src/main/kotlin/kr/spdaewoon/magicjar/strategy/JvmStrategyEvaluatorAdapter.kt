package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.springframework.stereotype.Component

/**
 * StrategyEvaluatorPort 구현체 — strategy-engine 모듈 (ADR 0056 §2-4-A + ADR 0004 정합).
 *
 * strategy-engine 모듈에서 domain port를 구현하여 api·consumer가 같은 evaluator Bean을 공유.
 * (api ↔ consumer 직접 의존 금지 — ADR 0004 모듈 경계 정합)
 *
 * ### 의무 사항 (ADR 0047 + ADR 0056)
 * - [JvmStrategyEvaluator.evaluateBacktest] 경유 — 직접 walkRoot 호출 절대 금지
 * - timeout 1000ms + auto-disable=false LOCK은 JvmStrategyEvaluator 내부에서 처리
 * - strategy-eval-pool 4 fixed thread 경유는 JvmStrategyEvaluator 내부에서 처리
 *
 * ### PR-39a ConsecutiveDirectionCalculator 패턴 정합
 * JvmStrategyEvaluator는 pattern-engine PatternRecognizer port를 통해 패턴 평가를 위임.
 */
@Component("strategyEvaluatorPortImpl")
class JvmStrategyEvaluatorAdapter(
    private val evaluator: JvmStrategyEvaluator,
) : StrategyEvaluatorPort {
    /**
     * 백테스트 평가를 JvmStrategyEvaluator.evaluateBacktest에 위임한다.
     */
    override fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        slice: List<Candle>,
    ): StrategyEvalResult = evaluator.evaluateBacktest(strategyId, ast, slice)

    /**
     * 실시간 평가를 JvmStrategyEvaluator.evaluate에 위임한다.
     *
     * timeout 100ms sandbox + auto-disable 정책은 JvmStrategyEvaluator 내부 책임.
     */
    override fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult = evaluator.evaluate(strategyId, ast, candles)
}
