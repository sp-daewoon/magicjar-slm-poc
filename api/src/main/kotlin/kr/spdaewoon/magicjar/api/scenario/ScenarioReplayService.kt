package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.service.backtest.BacktestEvaluatorBridge
import kr.spdaewoon.magicjar.domain.backtest.LookaheadGuard
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.session.PerInstantMarketSessionResolver
import kr.spdaewoon.magicjar.domain.market.session.TradingHoursGate
import kr.spdaewoon.magicjar.domain.market.session.port.MarketCalendarRepository
import kr.spdaewoon.magicjar.domain.scenario.ScenarioCandle
import kr.spdaewoon.magicjar.domain.scenario.ScenarioFault
import kr.spdaewoon.magicjar.domain.scenario.ScenarioFaultType
import kr.spdaewoon.magicjar.domain.scenario.ScenarioMarketHoursGate
import kr.spdaewoon.magicjar.domain.scenario.ScenarioNotSimulatedGate
import kr.spdaewoon.magicjar.domain.scenario.ScenarioPositionSeed
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayEngine
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayRequest
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskContext
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskDecision
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskEvaluator
import kr.spdaewoon.magicjar.domain.scenario.ScenarioSignalDecision
import kr.spdaewoon.magicjar.domain.scenario.ScenarioSignalSide
import kr.spdaewoon.magicjar.domain.scenario.ScenarioStrategyEvaluator
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitDecision
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitSimulation
import kr.spdaewoon.magicjar.strategy.scalein.VirtualStrategyPosition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ScenarioReplayService(
    private val strategyRepository: StrategyRepositoryPort,
    private val candleRepository: CandleJpaRepository,
    private val evaluatorBridge: BacktestEvaluatorBridge,
    private val persister: ScenarioReplayPersister,
    private val queryService: ScenarioReplayQueryService,
    private val objectMapper: ObjectMapper,
    private val marketCalendarRepository: MarketCalendarRepository,
    @Value("\${magicjar.scenario-replay.max-candles-per-run:5000}") private val maxCandlesPerRun: Int,
    @Value("\${magicjar.scenario-replay.max-events-per-run:20000}") private val maxEventsPerRun: Int,
    @Value("\${magicjar.signal.market-hours-gate.enabled:true}") private val marketHoursGateEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(ScenarioReplayService::class.java)

    fun runAndPersist(requestDto: ScenarioReplayRunRequestDto): ScenarioRunDetailDto {
        val request = requestDto.toDomainRequest()
        val strategy = loadStrategy(request)
        val replayRequest = request.copy(strategyName = strategy.displayName())
        val buyAst =
            strategy.definition.dsl.buyCondition
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 buyCondition이 없어 리플레이를 실행할 수 없습니다.")
        val sellAst = strategy.definition.dsl.sellCondition
        val candles = loadScenarioCandles(replayRequest)

        val engine =
            ScenarioReplayEngine(
                strategyEvaluator = buildStrategyEvaluator(strategy, buyAst, sellAst),
                riskEvaluator = buildRiskEvaluator(strategy),
                marketHoursGate = buildMarketHoursGate(),
                notSimulatedGates = NOT_SIMULATED_GATES,
            )
        val result = engine.run(replayRequest, candles)

        if (result.events.size > maxEventsPerRun) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "리플레이 이벤트 수가 한도를 초과했습니다: ${result.events.size} > $maxEventsPerRun",
            )
        }

        val savedRun = persister.persist(result)
        val runId = requireNotNull(savedRun.id)
        log.info("Scenario Replay 저장 완료: runId={} userId={} events={}", runId, request.userId, result.events.size)

        val events =
            queryService
                .listEvents(runId, request.userId, page = 0, size = maxEventsPerRun.coerceIn(1, 500))
                .content
                .map { ScenarioReplayMapper.toEvent(it, objectMapper) }
        val positions = queryService.listPositions(runId, request.userId).map(ScenarioReplayMapper::toPosition)

        return ScenarioRunDetailDto(
            run = ScenarioReplayMapper.toSummary(savedRun),
            events = events,
            positions = positions,
        )
    }

    private fun loadStrategy(request: ScenarioReplayRequest): Strategy {
        val strategy =
            strategyRepository.findById(request.strategyId)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략을 찾을 수 없습니다: strategyId=${request.strategyId}")
        if (strategy.userId.toString() != request.userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시나리오 리플레이 대상 전략을 찾을 수 없습니다: strategyId=${request.strategyId}")
        }
        return strategy
    }

    private fun Strategy.displayName(): String =
        definition.dsl.nameKo
            ?.takeIf { it.isNotBlank() }
            ?: definition.dsl.name

    private fun loadScenarioCandles(request: ScenarioReplayRequest): List<ScenarioCandle> {
        val entities =
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = request.code,
                interval = CandleInterval.fromCode(request.interval).code,
                fromTs = request.from.toJavaInstant(),
                toTs = request.to.toJavaInstant(),
            )
        if (entities.size > maxCandlesPerRun) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "리플레이 candle 수가 한도를 초과했습니다: ${entities.size} > $maxCandlesPerRun",
            )
        }
        return entities.mapNotNull(::toScenarioCandle)
    }

    private fun buildStrategyEvaluator(
        strategy: Strategy,
        buyAst: DslNode,
        sellAst: DslNode?,
    ): ScenarioStrategyEvaluator =
        ScenarioStrategyEvaluator { context ->
            val strategyId = requireNotNull(strategy.id)
            val snapshot = context.ledger.positions.firstOrNull { it.code == context.request.code }
            val holdingQty = snapshot?.quantity ?: 0L
            val domainCandles = context.candlesUntilNow.map { it.toStrategyCandle() }
            LookaheadGuard.safeSlice(domainCandles, domainCandles.lastIndex)

            // scale-in/exit 활성 전략(spec §6-3) — live·백테스트와 동일하게 ScaleInExitCalculator 단일 함수 경유.
            if (ScaleInExitSimulation.isActive(strategy.definition.scaleIn, strategy.definition.partialExits)) {
                return@ScenarioStrategyEvaluator evaluateScaleInExit(strategy, strategyId, buyAst, domainCandles, snapshot, context)
            }

            // ── 현행 단일 진입/전량 청산 경로 (zero-regression — scale-in/partialExits 미설정) ──
            if (holdingQty > 0 && sellAst != null) {
                val sellResult = evaluatorBridge.evaluateBacktest(strategyId, sellAst, domainCandles)
                // DSL evaluator returns BUY when the provided condition is true; sellAst truth maps to a replay SELL.
                if (sellResult.signal == Signal.BUY) {
                    return@ScenarioStrategyEvaluator ScenarioSignalDecision.sell(
                        quantity = 1,
                        reason = reasonText("SELL", sellResult.reason),
                    )
                }
                return@ScenarioStrategyEvaluator ScenarioSignalDecision.hold(reasonText("SELL_HOLD", sellResult.reason))
            }

            val buyResult = evaluatorBridge.evaluateBacktest(strategyId, buyAst, domainCandles)
            if (buyResult.signal == Signal.BUY) {
                ScenarioSignalDecision.buy(
                    quantity = 1,
                    reason = reasonText("BUY", buyResult.reason),
                )
            } else {
                ScenarioSignalDecision.hold(reasonText("BUY_HOLD", buyResult.reason))
            }
        }

    /**
     * scale-in/exit 활성 전략의 리플레이 가상 lot 평가 (분할매수 R3 · spec §6-3 LOCK).
     *
     * 가상 [ScenarioPositionSnapshot](평단·filledLots·executedExitStages)을 [VirtualStrategyPosition]으로 감싸
     * **live·백테스트와 동일한 [ScaleInExitSimulation.evaluate]→[ScaleInExitCalculator]** 를 호출하고, sealed 결정을
     * 리플레이 [ScenarioSignalDecision]으로 매핑한다. 회차 수량은 가상 ledger가 budget으로 산정하므로 여기선
     * `quantity=1` 단위(엔진 defaultQuantity·가용 수량 가드와 정합)로 발행하되, PartialTakeProfit는 보유분×weight%
     * 수량을 명시하고 단계 인덱스를 함께 실어 엔진이 체결 후 `recordExitStage`로 중복 발화를 차단한다.
     */
    private fun evaluateScaleInExit(
        strategy: Strategy,
        strategyId: Long,
        buyAst: DslNode,
        domainCandles: List<Candle>,
        snapshot: kr.spdaewoon.magicjar.domain.scenario.ScenarioPositionSnapshot?,
        context: kr.spdaewoon.magicjar.domain.scenario.ScenarioEvaluationContext,
    ): ScenarioSignalDecision {
        val buyResult = evaluatorBridge.evaluateBacktest(strategyId, buyAst, domainCandles)
        val buyFired = buyResult.signal == Signal.BUY
        val virtual =
            snapshot?.takeIf { it.quantity > 0 }?.let {
                VirtualStrategyPosition(
                    code = it.code,
                    quantity = it.quantity.toInt(),
                    avgPrice = it.averagePrice,
                    filledLots = it.filledLots,
                    executedExitStages = it.executedExitStages,
                )
            }
        val decision =
            ScaleInExitSimulation.evaluate(
                position = virtual,
                currentPrice = context.candle.close,
                scaleIn = strategy.definition.scaleIn,
                fixedStopLossPct = strategy.definition.fixedStopLoss,
                takeProfitPct = strategy.definition.takeProfitPct,
                partialExits = strategy.definition.partialExits,
                buySignalFired = buyFired,
            )
        return when (decision) {
            is ScaleInExitDecision.EnterSlice ->
                ScenarioSignalDecision.buy(
                    quantity = 1,
                    reason = reasonText("SCALE_IN_SLICE#${decision.sliceIndex + 1}", buyResult.reason),
                )

            is ScaleInExitDecision.StopLoss ->
                ScenarioSignalDecision.sell(
                    quantity = decision.quantity.toLong().coerceAtLeast(1),
                    reason = "리플레이 전략 평가 STOP_LOSS: 평단 기준 전량 손절",
                )

            is ScaleInExitDecision.PartialTakeProfit ->
                ScenarioSignalDecision(
                    side = ScenarioSignalSide.SELL,
                    quantity = decision.quantity.toLong().coerceAtLeast(1),
                    reason = "리플레이 전략 평가 PARTIAL_TAKE_PROFIT#${decision.stageIndex}: 보유분 ${decision.weightPct}% 청산",
                    partialExitStageIndex = decision.stageIndex,
                )

            is ScaleInExitDecision.TakeProfit ->
                ScenarioSignalDecision.sell(
                    quantity = decision.quantity.toLong().coerceAtLeast(1),
                    reason = "리플레이 전략 평가 TAKE_PROFIT: 평단 기준 잔량 전량 익절",
                )

            ScaleInExitDecision.Hold -> ScenarioSignalDecision.hold(reasonText("SCALE_IN_HOLD", buyResult.reason))
        }
    }

    /**
     * 리플레이 정규장 게이트 (readiness P1) — 라이브와 동일한 [TradingHoursGate] 로직 재사용.
     *
     * 라이브 [kr.spdaewoon.magicjar.api.market.session.KrxMarketSessionResolver]는 Redis로 "현재" 세션을
     * 캐싱하므로 과거 boundary 판정에 부적합하다. 대신 동일한 KST FSM을 Redis 없이 적용하는
     * [PerInstantMarketSessionResolver]에 market_calendar 영업일 판정을 위임해 결정론적으로 판정한다.
     *
     * `magicjar.signal.market-hours-gate.enabled=false`(라이브와 동일 플래그)면 게이트를 끈다 — 라이브 정합.
     */
    private fun buildMarketHoursGate(): ScenarioMarketHoursGate {
        if (!marketHoursGateEnabled) {
            return ScenarioMarketHoursGate { true }
        }
        val resolver = PerInstantMarketSessionResolver(isBusinessDay = ::isBusinessDay)
        val gate = TradingHoursGate(sessionResolver = resolver)
        return ScenarioMarketHoursGate { boundaryTs -> gate.isWithinTradingHours(boundaryTs) }
    }

    private fun isBusinessDay(date: java.time.LocalDate): Boolean {
        val entry =
            runCatching { marketCalendarRepository.findByDate(date) }
                .getOrElse { e ->
                    log.warn("Scenario Replay market_calendar 조회 실패 — 주말 fallback: date={} err={}", date, e.message)
                    null
                }
        if (entry != null) return entry.isBusinessDay
        val dow = date.dayOfWeek
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY
    }

    private fun buildRiskEvaluator(strategy: Strategy): ScenarioRiskEvaluator =
        ScenarioRiskEvaluator { context ->
            when {
                !strategy.status.isEvaluable() -> blockedByStatus(strategy.status, context)
                strategy.status == StrategyStatus.BUY_BLOCKED && context.signal.side == ScenarioSignalSide.BUY ->
                    ScenarioRiskDecision.Blocked(
                        reasonCode = "STRATEGY_BUY_BLOCKED",
                        message = "전략 상태가 BUY_BLOCKED라 리플레이 가상 BUY 주문을 만들지 않았습니다.",
                        payload = mapOf("strategyStatus" to strategy.status.name),
                    )
                else -> ScenarioRiskDecision.Allowed
            }
        }

    private fun blockedByStatus(
        status: StrategyStatus,
        context: ScenarioRiskContext,
    ): ScenarioRiskDecision =
        ScenarioRiskDecision.Blocked(
            reasonCode = "STRATEGY_NOT_EVALUABLE",
            message = "전략 상태가 ${status.name}라 리플레이 가상 ${context.signal.side} 주문을 만들지 않았습니다.",
            payload = mapOf("strategyStatus" to status.name),
        )

    private fun ScenarioReplayRunRequestDto.toDomainRequest(): ScenarioReplayRequest {
        val from = parseInstant(from, "from")
        val to = parseInstant(to, "to")
        return ScenarioReplayRequest(
            userId = userId,
            strategyId = strategyId,
            strategyName = null,
            code = code,
            interval = interval,
            from = from,
            to = to,
            initialCash = initialCash,
            initialPositions = initialPositions.map { it.toDomainSeed() },
            faults = faults.map { it.toDomainFault() },
        )
    }

    private fun ScenarioPositionSeedDto.toDomainSeed(): ScenarioPositionSeed =
        ScenarioPositionSeed(
            code = code,
            quantity = quantity,
            averagePrice = averagePrice,
        )

    private fun ScenarioFaultDto.toDomainFault(): ScenarioFault =
        ScenarioFault(
            at = parseInstant(at, "fault.at"),
            type =
                runCatching { ScenarioFaultType.valueOf(type.uppercase()) }
                    .getOrElse {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 scenario fault type입니다: $type")
                    },
            message = message,
        )

    private fun parseInstant(
        value: String,
        fieldName: String,
    ): kotlinx.datetime.Instant =
        runCatching { kotlinx.datetime.Instant.parse(value) }
            .getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName 시각 형식이 올바르지 않습니다: $value")
            }

    private fun toScenarioCandle(entity: CandleJpaEntity): ScenarioCandle? {
        val open = entity.openAdj ?: entity.openRaw ?: return null
        val high = entity.highAdj ?: entity.highRaw ?: return null
        val low = entity.lowAdj ?: entity.lowRaw ?: return null
        val close = entity.closeAdj ?: entity.closeRaw ?: return null
        val ts = entity.ts.toKotlinInstant()
        return ScenarioCandle(
            code = entity.code,
            interval = entity.interval,
            openedAt = ts,
            closedAt = ts,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = entity.volume ?: 0L,
        )
    }

    private fun ScenarioCandle.toStrategyCandle(): Candle =
        Candle(
            id = null,
            code = code,
            interval = CandleInterval.fromCode(interval),
            ts = closedAt,
            openRaw = open,
            highRaw = high,
            lowRaw = low,
            closeRaw = close,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = volume,
            source = "SCENARIO_REPLAY",
        )

    private fun reasonText(
        prefix: String,
        reason: Map<String, Any>,
    ): String =
        runCatching { "리플레이 전략 평가 $prefix: ${objectMapper.writeValueAsString(reason)}" }
            .getOrDefault("리플레이 전략 평가 $prefix")

    companion object {
        /**
         * 라이브 Risk Gateway(ADR 0067) 룰 중 리플레이 가상 상태로 재현 불가능한 게이트 (readiness P1).
         * 조용히 통과시키지 않고 run 시작 시 GATE_NOT_SIMULATED 이벤트로 정직 표기해 라이브 차이를 설명한다.
         *
         * 재현 가능 룰(리플레이에서 동일 적용)은 표기 대상 아님:
         *  - BUY_BLOCKED·전략 상태 게이트 → 리플레이 risk evaluator가 동일 적용
         *  - POSITION_LIMIT 예산·MAX_CONCURRENT·SELL_WITHOUT_POSITION → 가상 ledger로 재현(현금/포지션 검사)
         *  - 정규장 게이트 → TradingHoursGate 재사용(GATE_SKIPPED)
         *  - RISK_PLAN_REQUIRED(ADR 0106) → 전략 정의 stopLoss/takeProfit는 정적이라 재현 가능 (향후 보강 여지)
         */
        private val NOT_SIMULATED_GATES =
            listOf(
                ScenarioNotSimulatedGate(
                    rule = "KILL_SWITCH_ON",
                    note = "킬스위치는 실시간 사용자/운영 상태라 리플레이로 재현하지 않습니다. 라이브는 킬스위치 ON이면 모든 주문이 차단됩니다.",
                ),
                ScenarioNotSimulatedGate(
                    rule = "DAILY_LOSS_LIMIT",
                    note = "일일 실현손익은 실계좌 체결 이력에 의존해 리플레이 가상 상태로 재현하지 않습니다. 라이브는 일일 손실 한도 도달 시 신규 주문이 차단됩니다.",
                ),
                ScenarioNotSimulatedGate(
                    rule = "TRADE_FREQUENCY_MINUTE",
                    note = "분당 거래 빈도는 실계좌 주문 카운트에 의존해 리플레이로 재현하지 않습니다. 라이브는 분당 한도 초과 시 차단됩니다.",
                ),
                ScenarioNotSimulatedGate(
                    rule = "TRADE_FREQUENCY_DAY",
                    note = "일별 거래 빈도는 실계좌 주문 카운트에 의존해 리플레이로 재현하지 않습니다. 라이브는 일별 한도 초과 시 차단됩니다.",
                ),
            )
    }
}
