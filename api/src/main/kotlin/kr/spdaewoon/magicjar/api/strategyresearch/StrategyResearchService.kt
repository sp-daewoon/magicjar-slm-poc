package kr.spdaewoon.magicjar.api.strategyresearch

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandle
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchCandidate
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchCandlePoint
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchEngine
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchMarketRegime
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchReport
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchRequest
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchStats
import kr.spdaewoon.magicjar.domain.strategyresearch.StrategyResearchTrade
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class StrategyResearchService(
    private val candleRepository: CandleJpaRepository,
    private val candidateProvider: kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider,
    private val entryEvaluator: kr.spdaewoon.magicjar.domain.strategyresearch.CandidateEntryEvaluator,
    @Value("\${magicjar.strategy-research.max-candles-per-run:20000}") private val maxCandlesPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchService::class.java)

    // 후보 목록은 DB(V74), 진입 평가는 strategy-engine DSL 평가기 (research-candidates-db).
    private val engine = StrategyResearchEngine(entryEvaluator, candidateProvider)

    fun propose(requestDto: StrategyResearchRequestDto): StrategyResearchReportDto {
        val request = requestDto.toDomainRequest()
        val intervalCode =
            runCatching { CandleInterval.fromCode(request.interval).code }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 candle interval입니다: ${request.interval}")
                }
        val fromTs = request.from.toJavaInstant()
        val toTs = request.to.toJavaInstant()
        val candleWindow = StrategyResearchCandleWindows.of(intervalCode, fromTs, toTs)
        val rangeEntities =
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = request.code,
                interval = candleWindow.interval,
                fromTs = candleWindow.from,
                toTs = candleWindow.to,
            )
        val entities = rangeEntities.withWarmupCandles(request.code, candleWindow.interval, candleWindow.from)
        if (entities.size > maxCandlesPerRun) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "전략 리서치 candle 수가 한도를 초과했습니다: ${entities.size} > $maxCandlesPerRun",
            )
        }

        val report = engine.research(request.copy(interval = intervalCode), entities.mapNotNull { it.toResearchCandle(intervalCode) })
        log.info(
            "StrategyResearch 후보 생성: userId={} code={} interval={} candles={} candidates={}",
            request.userId,
            request.code,
            intervalCode,
            report.candleCount,
            report.candidates.size,
        )
        return report.toDto()
    }

    private fun List<CandleJpaEntity>.withWarmupCandles(
        code: String,
        intervalCode: String,
        fromTs: java.time.Instant,
    ): List<CandleJpaEntity> {
        if (size >= MIN_RESEARCH_CANDLES) return this
        val missing = MIN_RESEARCH_CANDLES - size
        val previous =
            candleRepository
                .findByCodeAndIntervalAndTsBefore(
                    code = code,
                    interval = intervalCode,
                    before = fromTs,
                    pageable = PageRequest.of(0, missing),
                ).asReversed()
        return previous + this
    }

    private fun StrategyResearchRequestDto.toDomainRequest(): StrategyResearchRequest {
        val from = parseInstant(from, "from")
        val to = parseInstant(to, "to")
        if (from >= to) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 이전이어야 합니다.")
        }
        return StrategyResearchRequest(
            userId = userId,
            code = code,
            interval = interval,
            from = from,
            to = to,
            targetProfitPct = targetProfitPct,
            stopLossPct = stopLossPct,
            maxHoldBars = maxHoldBars,
        )
    }

    private fun parseInstant(
        value: String,
        fieldName: String,
    ): kotlinx.datetime.Instant =
        runCatching { kotlinx.datetime.Instant.parse(value) }
            .getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName 시각 형식이 올바르지 않습니다: $value")
            }

    private fun CandleJpaEntity.toResearchCandle(intervalCode: String): ResearchCandle? {
        val open = openAdj ?: openRaw ?: return null
        val high = highAdj ?: highRaw ?: return null
        val low = lowAdj ?: lowRaw ?: return null
        val close = closeAdj ?: closeRaw ?: return null
        return ResearchCandle(
            code = code,
            interval = intervalCode,
            ts = ts.toKotlinInstant(),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume ?: 0L,
        )
    }

    private fun StrategyResearchReport.toDto(): StrategyResearchReportDto =
        StrategyResearchReportDto(
            request =
                StrategyResearchRequestEchoDto(
                    userId = request.userId,
                    code = request.code,
                    interval = request.interval,
                    from = request.from.toString(),
                    to = request.to.toString(),
                    targetProfitPct = request.targetProfitPct,
                    stopLossPct = request.stopLossPct,
                    maxHoldBars = request.maxHoldBars,
                ),
            candleCount = candleCount,
            marketRegime = marketRegime.toDto(),
            warnings = warnings,
            candles = candles.map { it.toDto() },
            candidates = candidates.map { it.toDto() },
        )

    private fun StrategyResearchMarketRegime.toDto(): StrategyResearchMarketRegimeDto =
        StrategyResearchMarketRegimeDto(
            type = type,
            trendPct = trendPct,
            volatilityPct = volatilityPct,
            explanation = explanation,
        )

    private fun StrategyResearchCandlePoint.toDto(): StrategyResearchCandleDto =
        StrategyResearchCandleDto(
            ts = ts.toString(),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )

    private fun StrategyResearchCandidate.toDto(): StrategyResearchCandidateDto =
        StrategyResearchCandidateDto(
            key = key,
            title = title,
            summary = summary,
            marketFit = marketFit,
            entryRules = entryRules,
            exitRules = exitRules,
            riskRules = riskRules,
            entryDslDraft = entryDslDraft,
            exitDslDraft = exitDslDraft,
            stats = stats.toDto(),
            trades = trades.map { it.toDto() },
            score = score,
        )

    private fun StrategyResearchStats.toDto(): StrategyResearchStatsDto =
        StrategyResearchStatsDto(
            trades = trades,
            wins = wins,
            losses = losses,
            winRatePct = winRatePct,
            totalReturnPct = totalReturnPct,
            avgTradeReturnPct = avgTradeReturnPct,
            bestTradePct = bestTradePct,
            worstTradePct = worstTradePct,
            maxDrawdownPct = maxDrawdownPct,
            maxConsecutiveLosses = maxConsecutiveLosses,
            profitFactor = profitFactor,
        )

    private fun StrategyResearchTrade.toDto(): StrategyResearchTradeDto =
        StrategyResearchTradeDto(
            signalTime = signalTime.toString(),
            entryTime = entryTime.toString(),
            exitTime = exitTime.toString(),
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            returnPct = returnPct,
            exitReason = exitReason.name,
            barsHeld = barsHeld,
            reason = reason,
        )

    private companion object {
        const val MIN_RESEARCH_CANDLES = 40
    }
}
