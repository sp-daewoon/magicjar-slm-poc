package kr.spdaewoon.magicjar.api.strategyresearch

import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategyresearch.CandidateEntryEvaluator
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandle
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 리서치 후보 진입 DSL 평가 어댑터 (research-candidates-db).
 *
 * domain [CandidateEntryEvaluator] 포트를 strategy-engine [KisYamlDslParser] + [StrategyEvaluatorPort]로 구현한다.
 * (domain은 strategy-engine에 의존할 수 없으므로 api가 어댑터를 주입 — ADR 0004 / ADR 0092 정합.)
 *
 * ### 동작
 * 1. `entryDsl` → AST 파싱(식별자 결과 캐시 — 같은 식 반복 평가 비용 절감).
 * 2. `candles[0..index]` 슬라이스를 [Candle]로 변환.
 * 3. [StrategyEvaluatorPort.evaluateBacktest]로 평가(timeout 1000ms · auto-disable=false 정책 내부 처리).
 * 4. 결과 signal == BUY 이면 진입.
 *
 * ### 안전 실패
 * 파싱·평가 예외는 false(미진입)로 흡수한다 — 리서치는 매수 추천이 아니라 후보 평가이므로,
 * DSL 오류가 전체 리서치를 깨지 않도록 한다(파싱 실패는 1회만 warn 로깅 후 캐시).
 */
@Component
class CandidateEntryEvaluatorAdapter(
    private val parser: KisYamlDslParser,
    private val evaluatorPort: StrategyEvaluatorPort,
) : CandidateEntryEvaluator {
    private val log = LoggerFactory.getLogger(CandidateEntryEvaluatorAdapter::class.java)

    /** entryDsl → 파싱 결과 캐시. null 값 = 파싱 실패(미진입 고정). */
    private val astCache: MutableMap<String, ParseOutcome> = ConcurrentHashMap()

    override fun evaluateEntry(
        entryDsl: String,
        candles: List<ResearchCandle>,
        index: Int,
    ): Boolean {
        if (index < 0 || index >= candles.size) return false
        val outcome = astCache.getOrPut(entryDsl) { parse(entryDsl) }
        val ast = outcome.ast ?: return false

        val slice = candles.subList(0, index + 1).map { it.toCandle() }
        return try {
            // strategyId = -1L: 리서치 후보 평가용 sentinel (저장된 전략 아님 — auto-disable 미적용 경로).
            evaluatorPort.evaluateBacktest(RESEARCH_STRATEGY_ID, ast, slice).signal == Signal.BUY
        } catch (e: Exception) {
            // 캔들 부족·지표 예외 등은 미진입으로 흡수(리서치 안전 실패 원칙).
            false
        }
    }

    private fun parse(entryDsl: String): ParseOutcome =
        try {
            ParseOutcome(parser.parse(entryDsl))
        } catch (e: Exception) {
            log.warn("리서치 후보 entryDsl 파싱 실패 — 미진입 처리: dsl='{}' error={}", entryDsl, e.message)
            ParseOutcome(null)
        }

    private fun ResearchCandle.toCandle(): Candle =
        Candle(
            code = code,
            interval = runCatching { CandleInterval.fromCode(interval) }.getOrDefault(CandleInterval.ONE_DAY),
            ts = ts,
            openRaw = open,
            highRaw = high,
            lowRaw = low,
            closeRaw = close,
            openAdj = open,
            highAdj = high,
            lowAdj = low,
            closeAdj = close,
            volume = volume,
            source = "RESEARCH",
        )

    private data class ParseOutcome(
        val ast: DslNode?,
    )

    private companion object {
        const val RESEARCH_STRATEGY_ID = -1L
    }
}
