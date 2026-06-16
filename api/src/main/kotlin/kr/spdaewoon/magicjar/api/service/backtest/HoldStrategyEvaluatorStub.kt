package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * HOLD 반환 stub 구현 — api 모듈 단독 테스트 환경용 (ADR 0056 §2-4-A).
 *
 * strategy-engine 모듈의 JvmStrategyEvaluatorAdapter Bean이 없을 때 폴백으로 등록됨.
 * 항상 HOLD 신호를 반환하므로 매매 기록 0건 — 성능 테스트·슬라이스 테스트에서만 사용.
 *
 * @ConditionalOnMissingBean — strategy-engine Bean이 있으면 이 stub은 등록되지 않음.
 */
@Component
@ConditionalOnMissingBean(name = ["strategyEvaluatorPortImpl"])
class HoldStrategyEvaluatorStub : StrategyEvaluatorPort {
    private val log = LoggerFactory.getLogger(HoldStrategyEvaluatorStub::class.java)

    override fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        slice: List<Candle>,
    ): StrategyEvalResult {
        log.debug("HoldStrategyEvaluatorStub — strategyId={}, sliceSize={}", strategyId, slice.size)
        return StrategyEvalResult(
            signal = Signal.HOLD,
            strength = BigDecimal.ZERO,
            reason = mapOf("trigger" to "STUB_HOLD", "note" to "strategy-engine evaluator bean 없음 — 테스트 fallback"),
            evaluatedAt = Clock.System.now(),
            evalTimeMs = 0L,
        )
    }

    override fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult {
        log.debug("HoldStrategyEvaluatorStub.evaluate — strategyId={}, candleSize={}", strategyId, candles.size)
        return StrategyEvalResult(
            signal = Signal.HOLD,
            strength = BigDecimal.ZERO,
            reason = mapOf("trigger" to "STUB_HOLD", "note" to "strategy-engine evaluator bean 없음 — 테스트 fallback"),
            evaluatedAt = Clock.System.now(),
            evalTimeMs = 0L,
        )
    }
}
