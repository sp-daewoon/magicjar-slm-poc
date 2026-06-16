package kr.spdaewoon.magicjar.api.application.screener

import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.screener.ScreenerCriteria
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import kr.spdaewoon.magicjar.domain.screener.SkipReason
import kr.spdaewoon.magicjar.domain.screener.port.InstrumentLookupPort
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerEngine
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerRankerPort
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Screener engine 구현체 (ADR 0071 §2-1 A-1 + spec §2-2).
 *
 * api 모듈에 위치 — ADR 0004 모듈 경계 정합.
 * ScreenerController·ScreenerService → ScreenerEngine port → 본 구현체 (api JVM 내 동기 inline 평가).
 *
 * ### 의존성 역전 (ADR 0004 헥사고날 정합)
 * - DSL 평가: StrategyEvaluatorPort.evaluate() 경유 (strategy-engine JvmStrategyEvaluatorAdapter 구현체)
 * - 테스트 슬라이스 환경: HoldStrategyEvaluatorStub (HOLD 반환 — @ConditionalOnMissingBean)
 *
 * Phase 1 책임:
 * 1. Strategy 조회 (StrategyRepositoryPort.findById)
 * 2. universe 조회 (InstrumentLookupPort.findActive)
 * 3. universe iterate — 각 instrument 별 candle 조회 + DSL 평가 + skip 매트릭스 누적
 * 4. BUY 시그널 매칭만 raw match 수집 → ranker 호출 → 랭킹 + limit 적용
 * 5. ScreenerRunResult 반환 (cacheHit=false, cacheKey="" — Phase 2가 wrap)
 *
 * ### Phase 2 의존 (graceful fallback — 헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * - StrategyRepositoryPort: api 모듈 JpaAdapter 부재 시 → 빈 결과 LOCK
 * - InstrumentLookupPort: api 모듈 InstrumentLookupAdapter 부재 시 → universe size=0
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 engine은 LLM provider import 0 — grep 검증 의무.
 *
 * ### KIS endpoint 호출 0 LOCK (ADR 0071 §2-10 + §14)
 * universe = instrument 영속 + candle = 영속 reuse — KIS REST adapter 0 import 의무.
 */
@Component
class ScreenerEngineImpl(
    private val strategyLookupProvider: ObjectProvider<StrategyRepositoryPort>,
    private val instrumentLookupProvider: ObjectProvider<InstrumentLookupPort>,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    private val strategyEvaluatorPort: StrategyEvaluatorPort,
    private val ranker: ScreenerRankerPort,
    @Value("\${magicjar.screener.candle-lookback-days:90}") private val candleLookbackDays: Int,
    @Value("\${magicjar.screener.min-candles:20}") private val minCandles: Int,
) : ScreenerEngine {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun screen(criteria: ScreenerCriteria): ScreenerRunResult {
        val started = System.currentTimeMillis()

        // ───────────────────────────────────────────────────────────────
        // Phase 2 의존 graceful fallback
        // ───────────────────────────────────────────────────────────────
        val strategyRepository = strategyLookupProvider.ifAvailable
        if (strategyRepository == null) {
            log.debug("StrategyRepositoryPort bean 부재 (Phase 2 의존) — empty result fallback")
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }
        val instrumentLookup = instrumentLookupProvider.ifAvailable
        if (instrumentLookup == null) {
            log.debug("InstrumentLookupPort bean 부재 (Phase 2 의존) — empty result fallback")
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }
        val candleRepository = candleRepositoryProvider.ifAvailable
        if (candleRepository == null) {
            log.debug("CandleRepository bean 부재 (Phase 2 의존) — empty result fallback")
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }

        // ───────────────────────────────────────────────────────────────
        // Step 1: Strategy 조회 + userId 격리 검증
        // ───────────────────────────────────────────────────────────────
        val strategy = strategyRepository.findById(criteria.strategyId)
        if (strategy == null) {
            log.info("Strategy not found: strategyId={}", criteria.strategyId)
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }
        if (strategy.userId != criteria.userId) {
            log.warn(
                "Strategy userId mismatch — multi-tenant 격리 위반 차단: criteria.userId={} strategy.userId={}",
                criteria.userId,
                strategy.userId,
            )
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }
        if (!strategy.isEnabled()) {
            log.info("Strategy disabled: strategyId={} reason={}", strategy.id, strategy.disabledReason)
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }

        // ───────────────────────────────────────────────────────────────
        // Step 2: universe 조회
        // ───────────────────────────────────────────────────────────────
        val universe = instrumentLookup.findActive(criteria.filter)
        if (universe.isEmpty()) {
            log.info(
                "Universe empty: filter.markets={} filter.types={}",
                criteria.filter.markets.map { it.name },
                criteria.filter.types.map { it.name },
            )
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }

        // ───────────────────────────────────────────────────────────────
        // Step 3: universe iterate — DSL 평가 + skip 매트릭스 누적
        // ───────────────────────────────────────────────────────────────
        val skipCounts: MutableMap<String, Int> = LinkedHashMap()
        val rawMatches: MutableList<ScreenerMatch> = ArrayList()
        var evaluatedCount = 0

        val ast = strategy.definition.dsl.buyCondition
        if (ast == null) {
            log.info(
                "Strategy buyCondition missing — universe screening 의제 invalid: strategyId={}",
                strategy.id,
            )
            return emptyResult(elapsed = System.currentTimeMillis() - started)
        }

        val candleFetchLimit = maxOf(candleLookbackDays, minCandles).coerceAtLeast(1)
        for (instrument in universe) {
            val candles = candleRepository.findLatestByCodeAndInterval(instrument.code, criteria.interval, candleFetchLimit)
            // step 3a: candle 결손 graceful skip
            if (candles.isEmpty()) {
                incrementSkip(skipCounts, SkipReason.CandleMissing.label)
                continue
            }
            if (candles.size < minCandles) {
                incrementSkip(
                    skipCounts,
                    SkipReason.CandleInsufficient(need = minCandles, have = candles.size).label,
                )
                continue
            }

            // step 3b: candle lookback window 적용 (마지막 N일)
            val slice = candles.takeLast(candleLookbackDays)

            // step 3c: DSL 평가 (StrategyEvaluatorPort.evaluate — ADR 0004 domain port 경유)
            val strategyId = strategy.id ?: 0L
            evaluatedCount++

            val evalResult =
                try {
                    strategyEvaluatorPort.evaluate(strategyId, ast, slice)
                } catch (e: StrategyException.StrategyTimeoutException) {
                    log.debug("Screener DSL eval timeout: strategyId={} err={}", strategyId, e.message)
                    incrementSkip(skipCounts, SkipReason.EvalTimeout.label)
                    continue
                } catch (e: StrategyException) {
                    log.debug("Screener DSL eval error: strategyId={} err={}", strategyId, e.message)
                    incrementSkip(skipCounts, SkipReason.EvalError(e.message ?: e.javaClass.simpleName).label)
                    continue
                } catch (e: Exception) {
                    log.warn("Screener DSL eval unexpected error: strategyId={} err={}", strategyId, e.message)
                    incrementSkip(skipCounts, SkipReason.EvalError(e.message ?: e.javaClass.simpleName).label)
                    continue
                }

            // BUY 시그널만 매칭 — HOLD/SELL은 매칭 미발생 (universe screening = 매수 후보 발굴)
            if (evalResult.signal == Signal.BUY) {
                val match =
                    buildMatch(strategy, instrument, slice, evalResult.strength, evalResult.reason)
                rawMatches.add(match)
            }
        }

        // ───────────────────────────────────────────────────────────────
        // Step 4: ranker 호출 + limit 적용
        // ───────────────────────────────────────────────────────────────
        val ranked = ranker.rank(rawMatches, criteria.limit)

        val elapsed = System.currentTimeMillis() - started
        val skippedCount = skipCounts.values.sum()

        log.info(
            "Screener completed: userId={} strategyId={} universeSize={} evaluatedCount={} matchedCount={} skippedCount={} elapsedMs={}",
            criteria.userId,
            criteria.strategyId,
            universe.size,
            evaluatedCount,
            ranked.size,
            skippedCount,
            elapsed,
        )

        return ScreenerRunResult(
            universeSize = universe.size,
            evaluatedCount = evaluatedCount,
            matchedCount = ranked.size,
            skippedCount = skippedCount,
            skippedReasons = skipCounts.toMap(),
            matches = ranked,
            elapsedMs = elapsed,
            cacheHit = false,
            cacheKey = "",
        )
    }

    private fun emptyResult(elapsed: Long): ScreenerRunResult =
        ScreenerRunResult(
            universeSize = 0,
            evaluatedCount = 0,
            matchedCount = 0,
            skippedCount = 0,
            skippedReasons = emptyMap(),
            matches = emptyList(),
            elapsedMs = elapsed,
            cacheHit = false,
            cacheKey = "",
        )

    private fun incrementSkip(
        counts: MutableMap<String, Int>,
        label: String,
    ) {
        counts.merge(label, 1) { a, b -> a + b }
    }

    /**
     * BUY 매칭 → ScreenerMatch 빌드.
     *
     * Phase 1 normalize 정책 (단순화 LOCK — 외부 마켓캡·ATR·avgVolume 의존 0):
     * - signal: DSL evalResult.strength (BUY 강도 0~1)
     * - volume: 마지막 candle close * volume 정규화 (`min(value / 1e10, 1.0)`) — 1조 원/일 cap
     * - atr: 단순 (high-low)/close ratio 마지막 candle — sweet spot 0.01~0.08 (1=peak at 0.04)
     * - cap: 1.0 fixed (Phase 2 backend-core가 instrument.marketCap 합류 시 정밀화 — 본 사이클 LOCK)
     *
     * Phase 2 후속: instrument.marketCap·5일 평균 거래대금 합류 시 정밀화 (post-MVP backlog §54 사용자 custom 가중치와 함께).
     *
     * @param reason DSL evalResult.reason JSON Map → 단순 toString 직렬화 (Phase 2 frontend는 trigger/operator/lhs/rhs로 파싱)
     */
    private fun buildMatch(
        strategy: Strategy,
        instrument: Instrument,
        slice: List<Candle>,
        strength: BigDecimal,
        reason: Map<String, Any>,
    ): ScreenerMatch {
        val last = slice.last()
        val close = (last.closeAdj ?: last.closeRaw).max(BigDecimal.ONE)
        val high = last.highAdj ?: last.highRaw
        val low = last.lowAdj ?: last.lowRaw
        val volume = BigDecimal.valueOf(last.volume.coerceAtLeast(0L))

        // signal 0~1 clamp
        val signalScore = strength.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

        // volume normalize: log10(close * volume / 1e6) / log10(1e7) → 0~1
        val turnover = close.multiply(volume) // 원
        val volumeScore = normalizeLog(turnover, MIN_TURNOVER, MAX_TURNOVER)

        // atr proxy = (high-low)/close — sweet spot 0.04 ± 0.04
        val atrRatio =
            if (close.compareTo(BigDecimal.ZERO) > 0) {
                high.subtract(low).divide(close, SCALE, RoundingMode.HALF_EVEN)
            } else {
                BigDecimal.ZERO
            }
        val atrScore = atrSweetSpot(atrRatio)

        // cap fixed 1.0 (Phase 2 instrument.marketCap 합류 시 정밀화)
        val capScore = BigDecimal.ONE

        val breakdown =
            ScreenerScoreBreakdown(
                signal = signalScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                volume = volumeScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                atr = atrScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                cap = capScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                total = BigDecimal.ZERO, // ranker가 weighted sum 채움
            )

        return ScreenerMatch(
            instrumentCode = instrument.code,
            instrumentName = instrument.name,
            rank = 0, // ranker가 1-base 부여
            score = BigDecimal.ZERO, // ranker가 채움
            breakdown = breakdown,
            reason = reason.toString(),
        )
    }

    /**
     * log normalize — log10(value) → (log10(value) - log10(min)) / (log10(max) - log10(min)) clamp 0~1.
     */
    private fun normalizeLog(
        value: BigDecimal,
        min: BigDecimal,
        max: BigDecimal,
    ): BigDecimal {
        val safeValue = value.max(min)
        val logValue = Math.log10(safeValue.toDouble())
        val logMin = Math.log10(min.toDouble())
        val logMax = Math.log10(max.toDouble())
        val ratio = (logValue - logMin) / (logMax - logMin)
        val clamped = ratio.coerceIn(0.0, 1.0)
        return BigDecimal.valueOf(clamped)
    }

    /**
     * ATR ratio sweet spot — 1=peak at 0.04, 0=outside [0.005, 0.20].
     *
     * triangular shape:
     * - 0.005 ~ 0.04: 0 → 1 ramp up
     * - 0.04 ~ 0.20: 1 → 0 ramp down
     * - outside: 0
     */
    private fun atrSweetSpot(atrRatio: BigDecimal): BigDecimal {
        val r = atrRatio.toDouble()
        val score =
            when {
                r < ATR_MIN -> 0.0
                r > ATR_MAX -> 0.0
                r < ATR_PEAK -> (r - ATR_MIN) / (ATR_PEAK - ATR_MIN)
                else -> (ATR_MAX - r) / (ATR_MAX - ATR_PEAK)
            }
        return BigDecimal.valueOf(score.coerceIn(0.0, 1.0))
    }

    companion object {
        const val SCALE: Int = 6
        val MIN_TURNOVER: BigDecimal = BigDecimal("1000000") // 100만 원/일
        val MAX_TURNOVER: BigDecimal = BigDecimal("1000000000000") // 1조 원/일
        const val ATR_MIN: Double = 0.005
        const val ATR_PEAK: Double = 0.04
        const val ATR_MAX: Double = 0.20
    }
}
