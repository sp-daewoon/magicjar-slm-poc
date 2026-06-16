package kr.spdaewoon.magicjar.api.service.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityCurveDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityPointDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeBreakdownDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisPerformanceCacheAdapter
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceCalculator
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Performance REST 서비스 — ADR 0072 §9-3 Phase 2 산출물.
 *
 * 캐시 흐름 (ADR 0072 §5):
 * 1. Redis HIT → cacheHit=true 즉시 반환
 * 2. Redis MISS → pnl_daily aggregate + inline 계산 → Redis SET + 반환
 *
 * period 분기 (ADR 0072 §2-3 C-1 LOCK):
 * - DAY: 오늘 (pnl_daily 부재 시 inline aggregate)
 * - WEEK: 이번 주 월~오늘
 * - MONTH: 이번 달 1일~오늘
 * - YEAR: 올해 1월 1일~오늘
 * - ALL: 최초 거래일~오늘
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 모든 데이터 영속 reuse (ADR 0072 §1-4 LOCK)
 */
@Service
class PerformanceService(
    private val performanceRepository: PerformanceRepository,
    private val performanceCalculator: PerformanceCalculator,
    private val cache: RedisPerformanceCacheAdapter,
    private val objectMapper: ObjectMapper,
    private val orderExecutionJpaRepository: OrderExecutionJpaRepository,
    private val orderRequestJpaRepository: OrderRequestJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
) {
    private val log = LoggerFactory.getLogger(PerformanceService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 성과 통계 요약 조회.
     * Redis HIT → 즉시 반환. MISS → pnl_daily 집계 + 계산.
     */
    fun getSummary(
        userId: String,
        period: String,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
        mode: TradingMode? = null,
    ): PerformanceSummaryDto {
        val validPeriod = validatePeriod(period)
        val (from, to) = resolvePeriodRange(userId, validPeriod, periodStart, periodEnd)

        if (mode != null) {
            val pnlDailyList = buildPnlDailyFromTrades(userId, from, to, mode)
            return buildSummaryDto(validPeriod, from, to, pnlDailyList, cacheHit = false)
        }

        // Redis HIT check
        val cached = cache.getSummaryJson(userId, validPeriod, from, to)
        if (cached != null) {
            log.debug("perf:summary 캐시 HIT userId={} period={} [{},{}]", userId, validPeriod, from, to)
            val dto = objectMapper.readValue(cached, PerformanceSummaryDto::class.java)
            return dto.copy(cacheHit = true)
        }

        // MISS → 계산
        val pnlDailyList = performanceRepository.findPnlDailyRange(userId, from, to)
        val dto = buildSummaryDto(validPeriod, from, to, pnlDailyList, cacheHit = false)

        // Redis SET
        cache.putSummary(userId, validPeriod, from, to, dto)
        return dto
    }

    /**
     * Equity Curve 시계열 조회.
     * Redis HIT → 즉시 반환. MISS → pnl_daily aggregate.
     * default: 최근 30일.
     */
    fun getEquityCurve(
        userId: String,
        from: LocalDate?,
        to: LocalDate?,
        mode: TradingMode? = null,
    ): EquityCurveDto {
        val today = LocalDate.now(kst)
        val resolvedTo = to ?: today
        val resolvedFrom = from ?: resolvedTo.minusDays(30)

        if (mode != null) {
            val pnlDailyList = buildPnlDailyFromTrades(userId, resolvedFrom, resolvedTo, mode)
            return buildEquityCurveDto(resolvedFrom, resolvedTo, pnlDailyList)
        }

        // Redis HIT check
        val cached = cache.getEquityCurveJson(userId, resolvedFrom, resolvedTo)
        if (cached != null) {
            log.debug("perf:equity 캐시 HIT userId={} [{},{}]", userId, resolvedFrom, resolvedTo)
            return objectMapper.readValue(cached, EquityCurveDto::class.java)
        }

        // MISS → 집계
        val pnlDailyList = performanceRepository.findPnlDailyRange(userId, resolvedFrom, resolvedTo)
        val dto = buildEquityCurveDto(resolvedFrom, resolvedTo, pnlDailyList)

        // Redis SET
        cache.putEquityCurve(userId, resolvedFrom, resolvedTo, dto)
        return dto
    }

    /**
     * 실현손익 상세 조회.
     *
     * pnl_daily 요약의 근거를 매도 체결 단위로 재생산한다. 과거 매수 평균단가가 필요하므로
     * periodStart 이전 체결도 periodEnd 종료 시점까지 함께 replay한다.
     */
    fun getTradeBreakdown(
        userId: String,
        period: String,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
        mode: TradingMode? = null,
    ): PerformanceTradeBreakdownDto {
        val validPeriod = validatePeriod(period)
        val (from, to) = resolvePeriodRange(userId, validPeriod, periodStart, periodEnd)
        val items = loadTradeItems(userId, from, to, mode)
        val totalRealizedPnl = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.realizedPnl }.setScale(2, RoundingMode.HALF_UP)

        return PerformanceTradeBreakdownDto(
            period = validPeriod,
            periodStart = from,
            periodEnd = to,
            totalRealizedPnl = totalRealizedPnl,
            tradeCount = items.size,
            items = items.sortedWith(compareByDescending<PerformanceTradeDto> { it.executedAt }.thenByDescending { it.executionId }),
        )
    }

    // --- private helpers ---

    private data class PositionState(
        var qty: BigDecimal = BigDecimal.ZERO,
        var avgPrice: BigDecimal = BigDecimal.ZERO,
    )

    private fun buildSummaryDto(
        period: String,
        from: LocalDate,
        to: LocalDate,
        pnlDailyList: List<PnlDaily>,
        cacheHit: Boolean,
    ): PerformanceSummaryDto {
        val equityCurve = performanceCalculator.buildEquityCurve(pnlDailyList, from, to)
        return PerformanceSummaryDto(
            period = period,
            periodStart = from,
            periodEnd = to,
            totalPnl = performanceCalculator.totalPnl(pnlDailyList),
            mddPct = performanceCalculator.maxDrawdown(equityCurve),
            winRatePct = performanceCalculator.winRate(pnlDailyList),
            profitLossRatio = performanceCalculator.profitLossRatio(pnlDailyList),
            tradeCount = pnlDailyList.sumOf { it.tradeCount },
            computedAt = OffsetDateTime.ofInstant(Instant.now(), kst),
            cacheHit = cacheHit,
        )
    }

    private fun buildEquityCurveDto(
        from: LocalDate,
        to: LocalDate,
        pnlDailyList: List<PnlDaily>,
    ): EquityCurveDto {
        val equityPoints = performanceCalculator.buildEquityCurve(pnlDailyList, from, to)
        return EquityCurveDto(
            from = from,
            to = to,
            points =
                equityPoints.map { point ->
                    EquityPointDto(
                        tradeDate = point.tradeDate,
                        dailyPnl = point.dailyPnl,
                        cumulativePnl = point.cumulativePnl,
                        tradeCount = point.tradeCount,
                    )
                },
        )
    }

    private fun buildPnlDailyFromTrades(
        userId: String,
        from: LocalDate,
        to: LocalDate,
        mode: TradingMode,
    ): List<PnlDaily> =
        loadTradeItems(userId, from, to, mode)
            .groupBy { it.tradeDate }
            .entries
            .sortedBy { it.key }
            .map { (tradeDate, items) ->
                val wins = items.filter { it.realizedPnl > BigDecimal.ZERO }
                val losses = items.filter { it.realizedPnl < BigDecimal.ZERO }
                PnlDaily(
                    userId = userId,
                    tradeDate = tradeDate,
                    realizedPnl = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.realizedPnl }.setScale(2, RoundingMode.HALF_UP),
                    winCount = wins.size,
                    lossCount = losses.size,
                    tradeCount = items.size,
                    avgProfit = averagePnl(wins),
                    avgLoss = averagePnl(losses),
                    equityAtClose = null,
                )
            }

    private fun averagePnl(items: List<PerformanceTradeDto>): BigDecimal? {
        if (items.isEmpty()) return null
        return items
            .fold(BigDecimal.ZERO) { acc, item -> acc + item.realizedPnl }
            .divide(BigDecimal(items.size), 2, RoundingMode.HALF_UP)
    }

    private fun loadTradeItems(
        userId: String,
        from: LocalDate,
        to: LocalDate,
        mode: TradingMode?,
    ): List<PerformanceTradeDto> {
        val toInstant = to.plusDays(1).atStartOfDay(kst).toInstant()
        val executions = orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(userId, toInstant)
        if (executions.isEmpty()) return emptyList()

        val requestById =
            orderRequestJpaRepository
                .findAllById(executions.map { it.orderId }.distinct())
                .associateBy { it.id }
        val filteredExecutions =
            if (mode == null) {
                executions
            } else {
                executions.filter { execution -> requestById[execution.orderId]?.mode == mode }
            }
        if (filteredExecutions.isEmpty()) return emptyList()

        val namesByCode =
            instrumentJpaRepository
                .findAllById(filteredExecutions.mapNotNull { requestById[it.orderId]?.code }.distinct())
                .asSequence()
                .filter { it.market != "UNKNOWN" }
                .filter { it.name.isNotBlank() && it.name != it.code }
                .associate { it.code to it.name }

        return replayTradeBreakdown(filteredExecutions, requestById, namesByCode, from, to)
    }

    private fun replayTradeBreakdown(
        executions: List<OrderExecutionJpaEntity>,
        requestById: Map<Long?, OrderRequestJpaEntity>,
        namesByCode: Map<String, String>,
        from: LocalDate,
        to: LocalDate,
    ): List<PerformanceTradeDto> {
        val positions = mutableMapOf<String, PositionState>()
        val items = mutableListOf<PerformanceTradeDto>()

        for (execution in executions) {
            val request = requestById[execution.orderId] ?: continue
            val state = positions.getOrPut(request.code) { PositionState() }

            when (request.side) {
                OrderSide.BUY -> applyBuy(state, execution)
                OrderSide.SELL -> {
                    val item = applySell(state, execution, request, namesByCode[request.code], from, to)
                    if (item != null) items += item
                }
            }
        }

        return items
    }

    private fun applyBuy(
        state: PositionState,
        execution: OrderExecutionJpaEntity,
    ) {
        if (execution.executedQty <= BigDecimal.ZERO) return

        val currentCost = state.qty.multiply(state.avgPrice)
        val addedCost =
            execution.executedQty
                .multiply(execution.executedPrice)
                .add(execution.fee)
                .add(execution.tax)
        val nextQty = state.qty.add(execution.executedQty)

        state.qty = nextQty
        state.avgPrice =
            if (nextQty.signum() > 0) {
                currentCost.add(addedCost).divide(nextQty, 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
    }

    private fun applySell(
        state: PositionState,
        execution: OrderExecutionJpaEntity,
        request: OrderRequestJpaEntity,
        name: String?,
        from: LocalDate,
        to: LocalDate,
    ): PerformanceTradeDto? {
        if (execution.executedQty <= BigDecimal.ZERO || state.qty <= BigDecimal.ZERO) {
            log.warn(
                "성과 상세 replay skip — 보유 수량 없는 매도 체결 orderId={} executionId={} qty={}",
                execution.orderId,
                execution.id,
                execution.executedQty,
            )
            return null
        }

        val closedQty = minOf(execution.executedQty, state.qty)
        val avgBuyPrice = state.avgPrice
        val expenseRatio = closedQty.divide(execution.executedQty, 8, RoundingMode.HALF_UP)
        val fee = execution.fee.multiply(expenseRatio).setScale(4, RoundingMode.HALF_UP)
        val tax = execution.tax.multiply(expenseRatio).setScale(4, RoundingMode.HALF_UP)
        val realizedPnl =
            execution.executedPrice
                .subtract(avgBuyPrice)
                .multiply(closedQty)
                .subtract(fee)
                .subtract(tax)
                .setScale(2, RoundingMode.HALF_UP)

        state.qty = state.qty.subtract(closedQty)
        if (state.qty <= BigDecimal.ZERO) {
            state.qty = BigDecimal.ZERO
            state.avgPrice = BigDecimal.ZERO
        }

        val tradeDate = execution.executedAt.atZone(kst).toLocalDate()
        if (tradeDate < from || tradeDate > to) return null

        return PerformanceTradeDto(
            executionId = execution.id ?: 0L,
            orderId = execution.orderId,
            tradeDate = tradeDate,
            executedAt = OffsetDateTime.ofInstant(execution.executedAt, kst),
            code = request.code,
            name = name,
            mode = request.mode.name,
            source = request.source.name,
            executedQty = closedQty.stripTrailingZeros(),
            executedPrice = execution.executedPrice.stripTrailingZeros(),
            avgBuyPrice = avgBuyPrice.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros(),
            fee = fee.stripTrailingZeros(),
            tax = tax.stripTrailingZeros(),
            realizedPnl = realizedPnl,
        )
    }

    private fun validatePeriod(period: String): String {
        val valid = setOf("DAY", "WEEK", "MONTH", "YEAR", "ALL")
        val upper = period.uppercase()
        if (upper !in valid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 period입니다: $period. 허용값: $valid")
        }
        return upper
    }

    private fun resolvePeriodRange(
        userId: String,
        period: String,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
    ): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(kst)
        // 명시적 from·to 우선
        if (periodStart != null && periodEnd != null) return Pair(periodStart, periodEnd)

        return when (period) {
            "DAY" -> Pair(today, today)
            "WEEK" -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Pair(monday, today)
            }
            "MONTH" -> Pair(today.withDayOfMonth(1), today)
            "YEAR" -> Pair(today.withDayOfYear(1), today)
            "ALL" -> {
                val earliest = performanceRepository.findEarliestTradeDate(userId) ?: today
                Pair(earliest, today)
            }
            else -> Pair(today, today)
        }
    }
}
