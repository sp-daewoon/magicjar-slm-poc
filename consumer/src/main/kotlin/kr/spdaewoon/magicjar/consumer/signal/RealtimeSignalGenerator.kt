package kr.spdaewoon.magicjar.consumer.signal

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessService
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.market.session.TradingHoursGate
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalContext
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator
import kr.spdaewoon.magicjar.strategy.MinHoldGuard
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitCalculator
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitDecision
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 실시간 시그널 생성기 (ADR 0066 §7-1 RealtimeSignalGenerator + §7-2 평가 흐름 본체).
 *
 * Phase 1 본체 — BoundaryClosingKafkaListener가 boundary Kafka 토픽 수신 시 호출.
 *
 * ### 흐름 (ADR 0066 §7-2 1:1 매핑)
 * 1. `StrategyLookupPort.findActiveByCodeAndInterval(code, interval)` — 평가 가능 strategy 조회
 *    (Phase 2에서 Redis cache 적용 — 본 단계는 단순 stub 가능)
 * 2. 평가 가능 strategy 별:
 *    a. `SignalDedupeGuard.tryAcquire(...)` — Redis SETNX 30s (ADR 0066 §2-5 E-1)
 *       └─ 이미 처리됨 → skip
 *    b. `JvmStrategyEvaluator.evaluate(...)` — sandbox 평가 (ADR 0047 §2-2)
 *       └─ timeout 100ms · CPU budget 50ms · auto-disable
 *    c. signal 발생 시(BUY/SELL — HOLD는 영속 0):
 *       - `SignalRepository.save(signal)` — DB INSERT (UQ 위반 시 skip)
 *       - `SignalKafkaProducer.publish(signal)` — Kafka `signal.generated.v1`
 *
 * ### HOLD 영속 정책 (ADR 0066 §9-2 정합)
 * BUY/SELL만 publish — HOLD는 noise (0.012 msg/s 1,000/day 기준 BUY/SELL only).
 *
 * ### Phase 2 의존
 * - SignalRepository — Phase 2 backend-core JpaAdapter 구현
 * - StrategyLookupPort — Phase 2 backend-core Redis cache + JpaAdapter 구현
 *   Phase 1은 ObjectProvider 패턴으로 옵셔널 의존 — bean 부재 시 빈 list 반환 stub 사용.
 *
 * spec 인용: ADR 0066 §7-1 + §7-2 + §11-1 Phase 1 (3).
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class RealtimeSignalGenerator(
    private val objectMapper: ObjectMapper,
    private val strategyLookupProvider: ObjectProvider<StrategyLookupPort>,
    private val signalRepositoryProvider: ObjectProvider<SignalRepository>,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    private val dataReadinessServiceProvider: ObjectProvider<DataReadinessService>,
    private val riskPositionPortProvider: ObjectProvider<RiskPositionPort>,
    private val strategyPositionPortProvider: ObjectProvider<StrategyPositionPort>,
    private val auditTrailRepositoryProvider: ObjectProvider<ConsumerAuditTrailJpaRepository>,
    private val jvmStrategyEvaluator: JvmStrategyEvaluator,
    private val signalDedupeGuard: SignalDedupeGuard,
    private val signalKafkaProducer: SignalKafkaProducer,
    private val realtimeFallbackBuyPolicy: RealtimeFallbackBuyPolicy,
    private val marketSessionResolver: MarketSessionResolver,
    private val businessDayCalendarProvider: ObjectProvider<BusinessDayCalendar>,
    @Value("\${magicjar.signal.eval-lookback-candles:500}") private val evalLookbackCandles: Int = 500,
    @Value("\${magicjar.signal.market-hours-gate.enabled:true}") private val marketHoursGateEnabled: Boolean = true,
    // ADR 0114 §4-4 P4 — market-hours gate SELL(청산) 분리 env. default true(현행 보존 — NXT 유동성 보수).
    // false면 NXT 시간대(08~09시·15:30~20시) 보유 포지션 SELL 평가는 시간대 무관 진행(BUY는 항상 게이트).
    @Value("\${magicjar.signal.market-hours-gate.sell-enabled:true}") private val marketHoursSellEnabled: Boolean = true,
    @Value("\${magicjar.strategy.scale-in.enabled:false}") private val scaleInEnabled: Boolean = false,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(RealtimeSignalGenerator::class.java)

    /**
     * Kafka boundary closed payload 수신 시 호출.
     *
     * @param code 종목 코드
     * @param interval 평가 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h 중 하나)
     * @param boundaryTs boundary closing ts (deterministic — ADR 0064 §4-2)
     * @param candle 마감된 boundary 캔들 (payload에서 변환)
     */
    fun onBoundaryClosed(
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        candle: Candle,
    ) {
        val strategyLookup = strategyLookupProvider.ifAvailable
        if (strategyLookup == null) {
            // Phase 2 미머지 시 — bean 부재 → 빈 list로 graceful no-op
            log.debug(
                "RealtimeSignalGenerator StrategyLookupPort bean 부재 (Phase 2 의존) — skip: code={} interval={} ts={}",
                code,
                interval.code,
                boundaryTs,
            )
            return
        }

        // ADR 0114 §4-2 P2 — 청산(SELL) 평가는 ACTIVE/BUY_BLOCKED ∪ DISABLED-with-position(position-driven 폴백).
        //   BUY는 평가기에서 status로 suppress(DISABLED·BUY_BLOCKED 신규 진입 차단). "포지션이 있으면 청산"으로 불변식 전환.
        val activeStrategies =
            try {
                strategyLookup.findSellEvalByCodeAndInterval(code, interval)
            } catch (e: Exception) {
                log.error(
                    "RealtimeSignalGenerator StrategyLookup 실패: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                return
            }

        if (activeStrategies.isEmpty()) {
            log.trace(
                "RealtimeSignalGenerator 활성 strategy 0건 — skip: code={} interval={}",
                code,
                interval.code,
            )
            return
        }

        // ADR 0114 §4-1·§4-4 — 게이트를 진입(BUY)/청산(SELL) 분화.
        //   fail-closed("신규 자본 투입 차단")는 BUY에만, 이미 투입된 자본의 회수(SELL=손절/익절)는 막지 않는다.
        //   gate 결과로 BUY suppress 여부를 산정하고, 양쪽 다 막히면(=SELL도 불가) 조기 return.
        val gate = resolveEvalGate(code, interval, boundaryTs, activeStrategies)
        if (gate.buySuppressed && !gate.sellAllowed) {
            // BUY·SELL 모두 불가 — 평가 자체 skip (현행 fail-closed 동등 경로).
            return
        }

        log.debug(
            "RealtimeSignalGenerator 평가 시작: code={} interval={} ts={} activeCount={} buySuppressed={} sellAllowed={} gateReason={}",
            code,
            interval.code,
            boundaryTs,
            activeStrategies.size,
            gate.buySuppressed,
            gate.sellAllowed,
            gate.reason,
        )

        for (strategy in activeStrategies) {
            try {
                processStrategy(strategy, code, interval, boundaryTs, candle, gate)
            } catch (e: Exception) {
                log.error(
                    "RealtimeSignalGenerator strategy 평가 실패: strategyId={} code={} interval={} err={}",
                    strategy.id,
                    code,
                    interval.code,
                    e.message,
                )
            }
        }
    }

    /**
     * 진입/청산 평가 허용 게이트 (ADR 0114 §4-1 P1 + §4-4 P4).
     *
     * @param buySuppressed true면 BUY(신규 자본 투입) 평가 결과를 발행하지 않는다(audit만).
     * @param sellAllowed   true면 SELL(보유 청산 — 손절/익절) 평가를 진행한다.
     * @param reason        skip/분화 사유(로그·audit 추적용).
     */
    private data class EvalGate(
        val buySuppressed: Boolean,
        val sellAllowed: Boolean,
        val reason: String,
    ) {
        companion object {
            /** 양쪽 모두 허용 — 정규장·READY 정상 경로. */
            val ALLOW_ALL = EvalGate(buySuppressed = false, sellAllowed = true, reason = "ALLOW_ALL")
        }
    }

    /**
     * market-hours 게이트(§4-4 P4) + data readiness 게이트(§4-1 P1)를 진입/청산으로 분화 산정한다.
     *
     * 핵심 불변식(§4-1): **fail-closed는 "신규 자본 투입(BUY) 차단"에만 적용한다. 이미 투입된 자본의 회수(SELL)는 막지 않는다.**
     * 청산 트리거에 쓰는 현재가는 boundary 캔들 close(확정값)로 항상 가용하므로, readiness 미충족(RECONCILING/STALE/FAILED)이어도
     * 손절/익절 평가는 진행한다(현행 fail-closed가 손절까지 막던 P1 Critical 해소).
     */
    private fun resolveEvalGate(
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        activeStrategies: List<Strategy>,
    ): EvalGate {
        var buySuppressed = false
        var sellAllowed = true
        val reasons = mutableListOf<String>()

        // === P4 — market-hours gate (BUY 한정 + sell-enabled env 분리) ===
        if (marketHoursGateEnabled && !isWithinTradingHours(boundaryTs)) {
            buySuppressed = true
            reasons += "MARKET_HOURS_BUY_SUPPRESSED"
            // sell-enabled=false 면 NXT 시간대 SELL 평가 허용(시간대 무관). default true → 현행대로 SELL도 정규장만.
            if (!marketHoursSellEnabled) {
                reasons += "MARKET_HOURS_SELL_ALLOWED_NXT"
            } else {
                sellAllowed = false
                reasons += "MARKET_HOURS_SELL_SUPPRESSED"
            }
            log.info(
                "RealtimeSignalGenerator market-hours-gate: boundaryTs={} interval={} buySuppressed=true sellAllowed={} reason=NOT_REGULAR_SESSION_OR_HOLIDAY",
                boundaryTs,
                interval.code,
                sellAllowed,
            )
        }

        // === P1 — data readiness gate (BUY fail-closed / SELL 진행) ===
        val readinessService = dataReadinessServiceProvider.ifAvailable
        if (readinessService != null) {
            val readiness = readinessService.check(code, interval, boundaryTs)
            if (!readiness.ready) {
                // BUY: 신규 자본 투입은 readiness READY 만 허용(현행 fail-closed 유지).
                buySuppressed = true
                reasons += "READINESS_${readiness.status}_BUY_SUPPRESSED"
                // SELL: 보유 청산 평가는 진행(현재가=boundary candle close 가용). FAILED 도 현재가 가용 시 진행(§4-1).
                reasons += "READINESS_${readiness.status}_SELL_PROCEED"
                log.warn(
                    "RealtimeSignalGenerator data readiness BUY suppress / SELL proceed: code={} interval={} ts={} status={} reason={} sellAllowed={} strategies={}",
                    code,
                    interval.code,
                    boundaryTs,
                    readiness.status,
                    readiness.reason,
                    sellAllowed,
                    activeStrategies.mapNotNull { it.id },
                )
                // audit: 진입(BUY) skip 만 기록 — SELL 은 진행하므로 미발행(기존 auditSkip 의미 보존).
                readinessService.auditSkip(readiness, activeStrategies.mapNotNull { it.id })
            }
        }

        return if (!buySuppressed) {
            EvalGate.ALLOW_ALL
        } else {
            EvalGate(buySuppressed = true, sellAllowed = sellAllowed, reason = reasons.joinToString(","))
        }
    }

    private fun processStrategy(
        strategy: Strategy,
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        candle: Candle,
        gate: EvalGate,
    ) {
        val strategyId =
            strategy.id ?: run {
                log.warn(
                    "RealtimeSignalGenerator strategy.id null — skip: userId={} code={}",
                    strategy.userId,
                    code,
                )
                return
            }

        // 1. dedupe guard — Redis SETNX 30s (ADR 0066 §2-5 E-1)
        if (!signalDedupeGuard.tryAcquire(strategyId, code, interval, boundaryTs)) {
            return
        }

        // 2. context 구성
        val context =
            SignalContext(
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
                nowKst = clock.now(),
            )

        val candles = loadEvaluationCandles(code, interval, boundaryTs, candle)

        // 3. JvmStrategyEvaluator 평가 (ADR 0047 sandbox)
        //    buyCondition 우선 평가 후, BUY가 아니면 sellCondition을 SELL 신호로 매핑한다.
        val buyAst =
            strategy.definition.dsl.buyCondition ?: run {
                log.trace(
                    "RealtimeSignalGenerator buyCondition 부재 — skip: strategyId={} code={}",
                    strategyId,
                    code,
                )
                return
            }
        val decision =
            evaluateDecision(
                strategy = strategy,
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
                buyAst = buyAst,
                sellAst = strategy.definition.dsl.sellCondition,
                candles = candles,
                currentPrice = candle.closeRaw,
            ) ?: return
        val side = decision.side
        val evalResult = decision.result
        // ADR 0114 §3-3·§5 — SELL 청산 가격 유형/LIMIT를 contextJson에 실어 listener(SignalGeneratedKafkaListener)가 라우팅.
        // + 사후 audit 보강(2026-06-15) — 평가값(trigger·lhs/rhs·lhsValue/rhsValue·익절 profitPct 등)을 BUY/SELL 모두 실어
        //   "왜 이 신호가 났나"를 캔들 추정 없이 검증한다. exitPriceType/exitLimitPrice 키는 그대로 보존(listener 라우팅 무영향).
        val exitContextJson = buildSignalContextJson(decision)

        // 4. HOLD는 영속 0 (ADR 0066 §9-2 noise 회피)
        if (side == SignalSide.HOLD) {
            log.trace(
                "RealtimeSignalGenerator HOLD skip: strategyId={} code={} interval={} ts={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }
        // ADR 0114 §4-1·§4-4 — 게이트 분화 적용.
        //   BUY(신규 자본 투입): readiness 미충족·NXT 시간대면 발행 차단(fail-closed).
        //   SELL(보유 청산): sell-enabled=false 분기로만 차단 — 손절/익절은 막지 않는 것이 본 ADR 핵심.
        if (side == SignalSide.BUY && gate.buySuppressed) {
            log.info(
                "RealtimeSignalGenerator BUY gate suppress (자본 투입 차단): strategyId={} code={} interval={} ts={} reason={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
                gate.reason,
            )
            return
        }
        if (side == SignalSide.SELL && !gate.sellAllowed) {
            log.info(
                "RealtimeSignalGenerator SELL gate suppress: strategyId={} code={} interval={} ts={} reason={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
                gate.reason,
            )
            return
        }
        // ADR 0114 §4-2 P2 — BUY는 ACTIVE 전략만. BUY_BLOCKED·DISABLED(position-driven SELL 폴백으로 들어온)의 신규 BUY는 suppress.
        //   SELL(청산)은 위 게이트만 통과하면 status 무관 진행(보유분 손절/익절 — 자본 회수는 막지 않음).
        if (side == SignalSide.BUY && strategy.status != StrategyStatus.ACTIVE) {
            log.warn(
                "RealtimeSignalGenerator 비-ACTIVE 신규 BUY skip(status={}): strategyId={} code={} interval={} ts={}",
                strategy.status,
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }
        if (side == SignalSide.BUY && !realtimeFallbackBuyPolicy.allowsBuy(candle)) {
            log.warn(
                "RealtimeSignalGenerator fallback candle BUY suppress: strategyId={} code={} interval={} ts={} source={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
                candle.source,
            )
            return
        }

        // 5. Signal 생성
        val strength = (evalResult.strength.multiply(BigDecimal(100))).toInt().coerceIn(0, 100)
        val reason = buildReason(evalResult)
        val signal =
            Signal(
                strategyId = strategyId,
                userId = strategy.userId.toString(),
                code = code,
                interval = interval,
                ts = boundaryTs,
                side = side,
                strength = strength,
                reason = reason,
                priceAtSignal = candle.closeRaw,
                contextJson = exitContextJson,
                createdAt = clock.now(),
            )

        // 6. 영속 — Phase 2 의존 (ObjectProvider 패턴)
        val signalRepository = signalRepositoryProvider.ifAvailable
        val persisted =
            if (signalRepository != null) {
                try {
                    signalRepository.save(signal)
                } catch (e: Exception) {
                    log.warn(
                        "RealtimeSignalGenerator save 실패 (UQ violation 또는 영속 오류): strategyId={} code={} err={}",
                        strategyId,
                        code,
                        e.message,
                    )
                    return
                }
            } else {
                log.debug(
                    "RealtimeSignalGenerator SignalRepository bean 부재 (Phase 2 의존) — id 없는 signal publish 차단: strategyId={} code={}",
                    strategyId,
                    code,
                )
                signal
            }

        if (persisted.id == null) {
            log.error(
                "RealtimeSignalGenerator signal id null — Kafka publish 차단: strategyId={} code={} interval={} ts={}",
                strategyId,
                code,
                interval.code,
                boundaryTs,
            )
            return
        }

        // 7. Kafka publish — Phase 2 STOMP fanout consumer가 listen
        signalKafkaProducer.publish(persisted)

        log.info(
            "RealtimeSignalGenerator 시그널 발행: strategyId={} userId={} code={} interval={} ts={} side={} strength={} reason={}",
            strategyId,
            signal.userId,
            code,
            interval.code,
            boundaryTs,
            side,
            strength,
            reason,
        )
    }

    /**
     * 기존 [kr.spdaewoon.magicjar.domain.strategy.Signal] enum → 신규 [SignalSide] 매핑.
     * (ADR 0066 §5-2 신규 도메인 패키지 분리 정합)
     */
    private fun mapToSignalSide(legacy: kr.spdaewoon.magicjar.domain.strategy.Signal): SignalSide =
        when (legacy) {
            kr.spdaewoon.magicjar.domain.strategy.Signal.BUY -> SignalSide.BUY
            kr.spdaewoon.magicjar.domain.strategy.Signal.SELL -> SignalSide.SELL
            kr.spdaewoon.magicjar.domain.strategy.Signal.HOLD -> SignalSide.HOLD
        }

    /**
     * @param exitPriceType SELL 청산 가격 유형(ADR 0114 §3-3 — 손절 MARKET / 익절 LIMIT). BUY·HOLD는 null.
     * @param exitLimitPrice 익절 LIMIT 가격(트리거 가격 = 평단×(1+triggerPct/100), R1 [EntryPricePolicy.takeProfitLimitPrice]). MARKET이면 null.
     */
    private data class SignalDecision(
        val side: SignalSide,
        val result: StrategyEvalResult,
        val exitPriceType: kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType? = null,
        val exitLimitPrice: BigDecimal? = null,
    )

    private fun evaluateDecision(
        strategy: Strategy,
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        buyAst: DslNode,
        sellAst: DslNode?,
        candles: List<Candle>,
        currentPrice: BigDecimal,
    ): SignalDecision? {
        val strategyId = strategy.id ?: return null
        val buyResult = evaluateAst(strategyId, code, "BUY", buyAst, candles) ?: return null
        val buyFired = mapToSignalSide(buyResult.signal) == SignalSide.BUY

        // scale-in/exit 활성 게이트 (spec §8-1) — toggle ON + scaleIn|partialExits 설정 전략만 신규 경로.
        // 그 외(toggle OFF · 미설정 전략)는 현행 단일 진입 + 전량 익절 경로(zero-regression LOCK).
        // ★ 손절·익절(StopLoss·TakeProfit·PartialTakeProfit) 경로는 minHoldBars와 무관하게 항상 먼저 평가/발행한다(안전망 우선).
        if (isScaleInExitActive(strategy)) {
            evaluateScaleInExit(strategy, code, currentPrice, buyResult, buyFired)?.let { return it }
            if (buyFired) {
                return SignalDecision(SignalSide.BUY, buyResult)
            }
        } else {
            evaluatePlainPositionExit(strategy, code, currentPrice, buyResult)?.let { return it }
            if (buyFired) {
                return SignalDecision(SignalSide.BUY, buyResult)
            }
        }

        if (sellAst != null) {
            val sellResult = evaluateAst(strategyId, code, "SELL", sellAst, candles) ?: return null
            if (mapToSignalSide(sellResult.signal) == SignalSide.BUY) {
                // 잔익절 방어 B안 — DSL 매도 시그널이 발화했어도 최소 보유 캔들(minHoldBars) 미충족이면 무시한다.
                //   기준 시점 = 최초 진입 ts(StrategyPosition.openedAt, scale-in 재진입에도 보존). 손절/익절은 위에서 이미 처리됨.
                if (shouldSkipDslSellForMinHold(strategy, code, interval, boundaryTs)) {
                    return SignalDecision(SignalSide.HOLD, buyResult)
                }
                return SignalDecision(
                    SignalSide.SELL,
                    sellResult.copy(
                        signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                        reason = sellResult.reason + ("condition" to "SELL"),
                    ),
                )
            }
        }

        return SignalDecision(SignalSide.HOLD, buyResult)
    }

    /**
     * 잔익절 방어 B안 — DSL 매도 시그널을 minHoldBars 보유 미충족으로 skip해야 하는지 판정 + audit.
     *
     * minHoldBars 미설정(null·0)이면 [MinHoldGuard]가 즉시 false(현행 — 즉시 매도). 설정 시 최초 진입 ts([StrategyPosition.openedAt])
     * 기준 경과 캔들이 N 미만이면 true. 진입 기록(StrategyPosition) 부재면 보유 미충족 판단 불가 → false(현행 매도 진행).
     *
     * skip 시 `SIGNAL_SKIPPED_MIN_HOLD` audit + INFO 로그(추적). StrategyPositionPort bean 부재 시 graceful false.
     */
    private fun shouldSkipDslSellForMinHold(
        strategy: Strategy,
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
    ): Boolean {
        val minHoldBars = strategy.definition.minHoldBars
        if (minHoldBars == null || minHoldBars <= 0) return false
        val strategyId = strategy.id ?: return false

        val positionPort = strategyPositionPortProvider.ifAvailable
        if (positionPort == null) {
            log.warn(
                "minHoldBars 설정 전략의 StrategyPositionPort bean 부재 — DSL 매도 게이트 미적용(현행 매도 진행): strategyId={} code={}",
                strategyId,
                code,
            )
            return false
        }

        val openedAt =
            try {
                positionPort
                    .findByUserStrategyCode(strategy.userId.toString(), strategyId, code)
                    ?.takeIf { it.quantity > 0 }
                    ?.openedAt
            } catch (e: Exception) {
                log.warn(
                    "minHoldBars StrategyPosition 조회 실패 — DSL 매도 게이트 미적용(현행 매도 진행): strategyId={} code={} err={}",
                    strategyId,
                    code,
                    e.message,
                )
                null
            } ?: return false

        val skip = MinHoldGuard.shouldSkipDslSell(minHoldBars, interval, openedAt, boundaryTs)
        if (skip) {
            log.info(
                "RealtimeSignalGenerator DSL 매도 minHold skip: strategyId={} code={} interval={} openedAt={} boundaryTs={} minHoldBars={}",
                strategyId,
                code,
                interval.code,
                openedAt,
                boundaryTs,
                minHoldBars,
            )
            auditMinHoldSkip(strategyId, code, interval, openedAt, boundaryTs, minHoldBars)
        }
        return skip
    }

    /** `SIGNAL_SKIPPED_MIN_HOLD` audit 저장(best-effort — 실패해도 매도 skip 자체는 유효). */
    private fun auditMinHoldSkip(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        openedAt: Instant,
        boundaryTs: Instant,
        minHoldBars: Int,
    ) {
        val auditRepo = auditTrailRepositoryProvider.ifAvailable ?: return
        try {
            auditRepo.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "system",
                    action = "SIGNAL_SKIPPED_MIN_HOLD",
                    entityType = "signal",
                    entityId = code,
                    detailJson =
                        objectMapper.writeValueAsString(
                            mapOf(
                                "strategyId" to strategyId,
                                "code" to code,
                                "interval" to interval.code,
                                "openedAt" to openedAt.toString(),
                                "boundaryTs" to boundaryTs.toString(),
                                "minHoldBars" to minHoldBars,
                            ),
                        ),
                ),
            )
        } catch (e: Exception) {
            log.warn("SIGNAL_SKIPPED_MIN_HOLD audit 저장 실패: strategyId={} code={} err={}", strategyId, code, e.message)
        }
    }

    private fun evaluatePlainPositionExit(
        strategy: Strategy,
        code: String,
        currentPrice: BigDecimal,
        baseResult: StrategyEvalResult,
    ): SignalDecision? {
        val fixedStopLossPct = strategy.definition.fixedStopLoss
        val takeProfitPct = strategy.definition.takeProfitPct
        if (fixedStopLossPct == null && takeProfitPct == null) return null

        val positionPort = riskPositionPortProvider.ifAvailable
        if (positionPort == null) {
            log.warn(
                "position exit 설정 전략의 position 조회 port 부재 — exit skip: strategyId={} code={}",
                strategy.id,
                code,
            )
            return null
        }
        val position =
            try {
                positionPort
                    .findCurrentByUser(strategy.userId.toString())
                    .firstOrNull { it.code == code && it.quantity > 0 }
            } catch (e: Exception) {
                log.warn(
                    "position exit 조회 실패 — exit skip: strategyId={} code={} err={}",
                    strategy.id,
                    code,
                    e.message,
                )
                null
            } ?: return null

        if (position.avgPrice <= BigDecimal.ZERO) return null

        if (fixedStopLossPct != null && fixedStopLossPct > BigDecimal.ZERO) {
            val stopPrice = PositionStopLossTickWatcher.stopPriceOf(position.avgPrice, fixedStopLossPct)
            if (stopPrice != null && currentPrice <= stopPrice) {
                val lossPct =
                    position.avgPrice
                        .subtract(currentPrice)
                        .divide(position.avgPrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal("100"))
                return sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "STOP_LOSS",
                            "condition" to "POSITION_AVG_STOP_LOSS",
                            "quantity" to position.quantity.toString(),
                            "lossPct" to lossPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "fixedStopLossPct" to fixedStopLossPct.toPlainString(),
                            "stopPrice" to stopPrice.toPlainString(),
                            "avgPrice" to position.avgPrice.toPlainString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ),
                    // ADR 0114 §3-3 — 손절은 MARKET(체결 확실성 우선).
                    exitPriceType = OrderPriceType.MARKET,
                    exitLimitPrice = null,
                )
            }
        }

        takeProfitPct ?: return null
        val profitPct =
            currentPrice
                .subtract(position.avgPrice)
                .divide(position.avgPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))

        if (profitPct < takeProfitPct) return null

        // ADR 0114 §3-3 — 익절은 LIMIT(트리거 가격 = 평단×(1+takeProfitPct/100), R1 단일 출처).
        val exitLimit = EntryPricePolicy.takeProfitLimitPrice(position.avgPrice, takeProfitPct)
        return SignalDecision(
            SignalSide.SELL,
            baseResult.copy(
                signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                strength = BigDecimal.ONE,
                reason =
                    baseResult.reason +
                        mapOf(
                            "trigger" to "TAKE_PROFIT",
                            "condition" to "POSITION_PROFIT_PCT",
                            "profitPct" to profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "takeProfitPct" to takeProfitPct.toPlainString(),
                            "avgPrice" to position.avgPrice.toPlainString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ),
            ),
            exitPriceType = OrderPriceType.LIMIT,
            exitLimitPrice = exitLimit,
        )
    }

    /**
     * scale-in/exit 신규 경로 활성 여부 (spec §8-1 ★ feature toggle 게이트).
     *
     * `magicjar.strategy.scale-in.enabled`(default false) ON **AND** 전략이 `scaleIn` 또는 `partialExits`를
     * 설정한 경우에만 [ScaleInExitCalculator] 경로를 탄다. toggle OFF거나 미설정 전략은 현행 단일 경로(zero-regression).
     *
     * 라이브 자본 경로 진입은 추가로 StrategyService ACTIVE 전이 게이트(`SCALE_IN_NOT_ENABLED`)가 막는다 —
     * 본 게이트는 그 위의 평가 단(엔진은 들어왔으나 toggle OFF면 평가 자체를 현행 경로로).
     */
    private fun isScaleInExitActive(strategy: Strategy): Boolean {
        if (!scaleInEnabled) return false
        val def = strategy.definition
        return def.scaleIn != null || !def.partialExits.isNullOrEmpty()
    }

    /**
     * scale-in 회차 진입 + 평단 기준 stop-loss/분할 익절/전량 익절 단일 평가 (spec §5 · ADR 0111 §2-4).
     *
     * [ScaleInExitCalculator] 순수 함수를 호출하고 그 sealed 결정을 시그널 [SignalDecision]으로 매핑한다.
     * 모든 leg는 **시그널 경로**로만 발행 — OrderIntent 직접 생성 0(spec §5-3 LOCK). 회차 수량·체결가 기반
     * idempotency suffix·StrategyPosition upsert는 R2-2 backend-core(어댑터·체결 listener) 책임.
     *
     * StrategyPositionPort bean 부재(R2-2 어댑터 머지 전)면 graceful no-op — 신규 경로 평가를 건너뛰고
     * null을 반환해 호출부가 BUY/HOLD 현행 경로로 이어간다.
     */
    private fun evaluateScaleInExit(
        strategy: Strategy,
        code: String,
        currentPrice: BigDecimal,
        baseResult: StrategyEvalResult,
        buyFired: Boolean,
    ): SignalDecision? {
        val strategyId = strategy.id ?: return null
        val positionPort = strategyPositionPortProvider.ifAvailable
        if (positionPort == null) {
            log.warn(
                "scale-in/exit 활성 전략의 StrategyPositionPort bean 부재 (R2-2 어댑터 의존) — 신규 경로 skip: strategyId={} code={}",
                strategyId,
                code,
            )
            return null
        }

        val position: StrategyPosition? =
            try {
                positionPort.findByUserStrategyCode(strategy.userId.toString(), strategyId, code)
            } catch (e: Exception) {
                log.warn(
                    "scale-in/exit StrategyPosition 조회 실패 — 신규 경로 skip: strategyId={} code={} err={}",
                    strategyId,
                    code,
                    e.message,
                )
                return null
            }

        val input =
            ScaleInExitInput(
                position = position,
                currentPrice = currentPrice,
                scaleIn = strategy.definition.scaleIn,
                fixedStopLossPct = strategy.definition.fixedStopLoss,
                takeProfitPct = strategy.definition.takeProfitPct,
                partialExits = strategy.definition.partialExits,
                buySignalFired = buyFired,
                // 회차 수량(budget 산정)·직전 체결가·회차 epoch 은 R2-2 어댑터(체결 listener)가 채운다.
                // R2-1 시그널 단계에서는 진입 여부·청산 판정만 수행하고 수량은 0(어댑터가 budget으로 확정).
                sliceBudgetQuantity = 0,
            )

        return when (val decision = ScaleInExitCalculator.evaluate(input)) {
            is ScaleInExitDecision.StopLoss ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "STOP_LOSS",
                            "condition" to "POSITION_AVG_STOP_LOSS",
                            "quantity" to decision.quantity.toString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                    // ADR 0114 §3-3 — 손절은 MARKET(체결 확실성 우선).
                    exitPriceType = OrderPriceType.MARKET,
                    exitLimitPrice = null,
                )

            is ScaleInExitDecision.PartialTakeProfit ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "PARTIAL_TAKE_PROFIT#${decision.stageIndex}",
                            "condition" to "POSITION_PARTIAL_TP",
                            "stageIndex" to decision.stageIndex.toString(),
                            "exitWeightPct" to decision.weightPct.toPlainString(),
                            "quantity" to decision.quantity.toString(),
                            "profitPct" to decision.profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                    // ADR 0114 §3-3 — 분할 익절도 LIMIT. 트리거 가격 = 평단×(1+stage triggerProfitPct/100).
                    exitPriceType = OrderPriceType.LIMIT,
                    exitLimitPrice = partialExitLimitPrice(strategy, position, decision.stageIndex),
                )

            is ScaleInExitDecision.TakeProfit ->
                sellSignal(
                    baseResult = baseResult,
                    reasonExtras =
                        mapOf(
                            "trigger" to "TAKE_PROFIT",
                            "condition" to "POSITION_PROFIT_PCT",
                            "quantity" to decision.quantity.toString(),
                            "profitPct" to decision.profitPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "takeProfitPct" to (strategy.definition.takeProfitPct?.toPlainString() ?: ""),
                            "currentPrice" to currentPrice.toPlainString(),
                        ) + avgPriceReason(position),
                    // ADR 0114 §3-3 — 전량 익절 LIMIT.
                    exitPriceType = OrderPriceType.LIMIT,
                    exitLimitPrice =
                        strategy.definition.takeProfitPct?.let { pct ->
                            position?.avgPrice?.takeIf { it > BigDecimal.ZERO }?.let { EntryPricePolicy.takeProfitLimitPrice(it, pct) }
                        },
                )

            is ScaleInExitDecision.EnterSlice ->
                SignalDecision(
                    SignalSide.BUY,
                    baseResult.copy(
                        signal = kr.spdaewoon.magicjar.domain.strategy.Signal.BUY,
                        reason =
                            baseResult.reason +
                                mapOf(
                                    "trigger" to "SCALE_IN_SLICE#${decision.sliceIndex + 1}:${decision.trigger.name}",
                                    "condition" to "SCALE_IN",
                                    "sliceIndex" to decision.sliceIndex.toString(),
                                    "sliceWeightPct" to decision.weightPct.toPlainString(),
                                    "reentryTrigger" to decision.trigger.name,
                                ),
                    ),
                )

            ScaleInExitDecision.Hold -> null
        }
    }

    private fun avgPriceReason(position: StrategyPosition?): Map<String, Any> =
        position?.let { mapOf("avgPrice" to it.avgPrice.toPlainString()) } ?: emptyMap()

    /**
     * 시그널 contextJson 직렬화 — ① SELL 청산 가격 라우팅(ADR 0114 §3-3·§5) + ② 평가값 audit 보강(2026-06-15).
     *
     * ### ① 청산 라우팅 (기존 계약 LOCK)
     * listener([SignalGeneratedKafkaListener.handleAllowed])가 `exitPriceType`(MARKET 손절 / LIMIT 익절) + `exitLimitPrice`를
     * 읽어 OrderIntent 가격 유형을 분기한다. 계산은 [EntryPricePolicy.takeProfitLimitPrice](R1 단일 출처)에서 끝났고 여기선 전달만 한다.
     *
     * ### ② audit 보강 (신규 — #86 사후 검증 공백 해소)
     * 기존엔 SELL 청산(exitPriceType 有)만 contextJson을 채워, DSL SELL·BUY 신호는 `context_json`이 비어 "왜 이 신호가 났나"를
     * 캔들로 추정할 수밖에 없었다(운영 #86 분석 교훈). 본 보강은 [StrategyEvalResult.reason](trigger·lhs/rhs·lhsValue/rhsValue·
     * profitPct·avgPrice·currentPrice 등 평가 당시 값)을 **BUY/SELL 모두** `eval.*` 네임스페이스로 직렬화한다.
     * BigDecimal·열거형은 `toString` 평탄화(JSON 직렬화 안전). exitPriceType/exitLimitPrice 키는 충돌 없이 공존하므로
     * listener 라우팅은 **무영향**(추가 키 무시) — 하위호환 LOCK.
     *
     * exit 정보·audit 둘 다 비면 null(현행 MARKET 경로 + 빈 context 보존).
     */
    private fun buildSignalContextJson(decision: SignalDecision): String? {
        val auditEval = flattenReasonForAudit(decision.result.reason)
        val hasExitRouting = decision.side == SignalSide.SELL && decision.exitPriceType != null
        if (!hasExitRouting && auditEval.isEmpty()) return null
        return runCatching {
            objectMapper.writeValueAsString(
                buildMap<String, Any> {
                    if (hasExitRouting) {
                        put("exitPriceType", decision.exitPriceType!!.name)
                        decision.exitLimitPrice?.let { put("exitLimitPrice", it.toPlainString()) }
                    }
                    if (auditEval.isNotEmpty()) {
                        put("eval", auditEval)
                    }
                },
            )
        }.onFailure { e -> log.warn("signal contextJson 직렬화 실패 — null fallback(MARKET 경로): err={}", e.message) }
            .getOrNull()
    }

    /**
     * 평가 reason Map을 audit-safe(JSON 직렬화 가능) 평탄 Map으로 변환.
     *
     * [StrategyEvalResult.reason]은 BigDecimal·중첩 Map(COMPOSITE의 left/right) 등 임의 값을 담는다. audit 기록은
     * 사람이 읽는 검증 용도이므로, 스칼라는 그대로·BigDecimal/열거형/기타 객체는 `toString` 평탄화한다(중첩 Map은 재귀 평탄화).
     * 빈 reason(또는 변환 실패)이면 빈 Map — 호출부가 null context로 처리.
     */
    private fun flattenReasonForAudit(reason: Map<String, Any>): Map<String, Any> =
        runCatching {
            reason.mapValues { (_, v) -> flattenValueForAudit(v) }
        }.getOrElse { emptyMap() }

    private fun flattenValueForAudit(v: Any?): Any =
        when (v) {
            null -> ""
            is String, is Int, is Long, is Boolean, is Double -> v
            is BigDecimal -> v.toPlainString()
            is Map<*, *> -> v.entries.associate { (k, vv) -> k.toString() to flattenValueForAudit(vv) }
            is Collection<*> -> v.map { flattenValueForAudit(it) }
            else -> v.toString()
        }

    private fun sellSignal(
        baseResult: StrategyEvalResult,
        reasonExtras: Map<String, Any>,
        exitPriceType: kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType? = null,
        exitLimitPrice: BigDecimal? = null,
    ): SignalDecision =
        SignalDecision(
            SignalSide.SELL,
            baseResult.copy(
                signal = kr.spdaewoon.magicjar.domain.strategy.Signal.SELL,
                strength = BigDecimal.ONE,
                reason = baseResult.reason + reasonExtras,
            ),
            exitPriceType = exitPriceType,
            exitLimitPrice = exitLimitPrice,
        )

    /**
     * 분할 익절 LIMIT 가격 = 평단 × (1 + stage triggerProfitPct/100) → R1 [EntryPricePolicy.takeProfitLimitPrice] (트리거 가격 기준).
     * stage trigger pct는 [StrategyDefinition.partialExits]\[stageIndex] 에서 읽는다(계산은 R1 단일 출처 — 중복 0).
     */
    private fun partialExitLimitPrice(
        strategy: Strategy,
        position: StrategyPosition?,
        stageIndex: Int,
    ): BigDecimal? {
        val avgPrice = position?.avgPrice?.takeIf { it > BigDecimal.ZERO } ?: return null
        val triggerPct =
            strategy.definition.partialExits
                ?.getOrNull(stageIndex)
                ?.triggerProfitPct ?: return null
        return EntryPricePolicy.takeProfitLimitPrice(avgPrice, triggerPct)
    }

    private fun evaluateAst(
        strategyId: Long,
        code: String,
        label: String,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult? =
        try {
            jvmStrategyEvaluator.evaluate(strategyId, ast, candles)
        } catch (e: Exception) {
            log.warn(
                "RealtimeSignalGenerator {} evaluate 실패: strategyId={} code={} candles={} err={}",
                label,
                strategyId,
                code,
                candles.size,
                e.message,
            )
            null
        }

    private fun loadEvaluationCandles(
        code: String,
        interval: CandleInterval,
        boundaryTs: Instant,
        boundaryCandle: Candle,
    ): List<Candle> {
        val repo = candleRepositoryProvider.ifAvailable
        if (repo == null) {
            log.warn(
                "RealtimeSignalGenerator CandleRepository bean 부재 — boundary candle 1건으로 평가: code={} interval={}",
                code,
                interval.code,
            )
            return listOf(boundaryCandle)
        }

        val lookback = evalLookbackCandles.coerceAtLeast(1)
        val historyLimit = (lookback - 1).coerceAtLeast(0)
        val persisted =
            try {
                if (historyLimit == 0) {
                    emptyList()
                } else {
                    repo.findLatestBeforeByCodeAndInterval(code, interval, boundaryTs.toJavaInstant(), historyLimit)
                }
            } catch (e: Exception) {
                log.warn(
                    "RealtimeSignalGenerator candle history 조회 실패 — boundary candle 1건으로 평가: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                return listOf(boundaryCandle)
            }

        return (persisted + boundaryCandle)
            .sortedBy { it.ts }
            .takeLast(lookback)
    }

    /**
     * boundaryTs(캔들 마감 UTC Instant)가 영업일 + 정규장(09:00~15:30 KST) 안인지 판정.
     *
     * - 영업일 판정: [BusinessDayCalendar] (market_calendar DB 권위) 우선, bean 부재 시 resolver 주말 가드 fallback.
     * - 정규장 판정: [MarketSessionResolver.resolve] 가 [MarketSession.Regular] 반환 시.
     *   resolver는 KST 변환 후 09:00~15:29(분 단위) 을 Regular로 본다.
     *   09:00 정각 boundary(=08:59 1m 캔들 마감) 는 Regular 진입, 15:30 boundary(=15:29 캔들 마감) 는 경계.
     */
    private fun isWithinTradingHours(boundaryTs: Instant): Boolean =
        TradingHoursGate(
            sessionResolver = marketSessionResolver,
            businessDayCalendar = businessDayCalendarProvider.ifAvailable,
            onCalendarError = { date, e ->
                log.warn(
                    "RealtimeSignalGenerator BusinessDayCalendar 조회 실패 — resolver 영업일 판정 fallback: date={} err={}",
                    date,
                    e.message,
                )
            },
        ).isWithinTradingHours(boundaryTs)

    /** StrategyEvalResult.reason Map → 한 줄 reason string. */
    private fun buildReason(result: StrategyEvalResult): String {
        val trigger = result.reason["trigger"]?.toString() ?: "RULE_MATCH"
        val lhs = result.reason["lhs"]?.toString()
        val rhs = result.reason["rhs"]?.toString()
        return if (lhs != null && rhs != null) {
            "$trigger: $lhs vs $rhs"
        } else {
            trigger
        }
    }
}
