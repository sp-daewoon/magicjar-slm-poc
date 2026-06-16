package kr.spdaewoon.magicjar.api.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisPerformanceCacheAdapter
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaRepository
import kr.spdaewoon.magicjar.api.service.performance.PerformanceCalculatorImpl
import kr.spdaewoon.magicjar.api.service.performance.PerformanceService
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * PerformanceServiceTest — 5건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. cache HIT → 즉시 반환 (cacheHit=true)
 * 2. cache MISS → inline aggregate + Redis SET
 * 3. pnl_daily fallback (데이터 있는 경우)
 * 4. userId 격리 — 다른 userId 데이터 미노출
 * 5. today period (DAY) → inline aggregate
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class PerformanceServiceTest {
    private val performanceRepository: PerformanceRepository = mock()
    private val performanceCalculator = PerformanceCalculatorImpl()
    private val cache: RedisPerformanceCacheAdapter = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val orderExecutionJpaRepository: OrderExecutionJpaRepository = mock()
    private val orderRequestJpaRepository: OrderRequestJpaRepository = mock()
    private val instrumentJpaRepository: InstrumentJpaRepository = mock()

    private val sut =
        PerformanceService(
            performanceRepository,
            performanceCalculator,
            cache,
            objectMapper,
            orderExecutionJpaRepository,
            orderRequestJpaRepository,
            instrumentJpaRepository,
        )

    private val userId = "user_test_001"
    private val kst = ZoneId.of("Asia/Seoul")

    private fun makePnlDaily(
        date: LocalDate,
        pnl: BigDecimal = BigDecimal("100000"),
    ) = PnlDaily(
        userId = userId,
        tradeDate = date,
        realizedPnl = pnl,
        winCount = 1,
        lossCount = 0,
        tradeCount = 1,
        avgProfit = pnl,
        avgLoss = null,
        equityAtClose = null,
    )

    @Test
    fun `cache HIT — 캐시 반환 cacheHit=true`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)
        val cachedDto =
            PerformanceSummaryDto(
                period = "MONTH",
                periodStart = from,
                periodEnd = to,
                totalPnl = BigDecimal("500000"),
                mddPct = BigDecimal("5.0000"),
                winRatePct = BigDecimal("70.00"),
                profitLossRatio = BigDecimal("2.0000"),
                tradeCount = 10,
                computedAt = OffsetDateTime.now(kst),
                cacheHit = false, // 캐시에서 읽을 때 false 저장 — service에서 true로 교체
            )
        val json = objectMapper.writeValueAsString(cachedDto)

        whenever(cache.getSummaryJson(eq(userId), eq("MONTH"), any(), any())).thenReturn(json)

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.cacheHit).isTrue()
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("500000"))
    }

    @Test
    fun `cache MISS — inline aggregate 후 Redis SET 호출`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), eq(from), eq(to)))
            .thenReturn(listOf(makePnlDaily(LocalDate.of(2026, 5, 5))))

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.cacheHit).isFalse()
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("100000"))
        verify(cache).putSummary(eq(userId), eq("MONTH"), eq(from), eq(to), any())
    }

    @Test
    fun `pnl_daily 데이터 있는 경우 — 실제 집계`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)
        val pnlList =
            listOf(
                makePnlDaily(LocalDate.of(2026, 5, 5), BigDecimal("300000")),
                makePnlDaily(LocalDate.of(2026, 5, 8), BigDecimal("200000")),
            )

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), any(), any())).thenReturn(pnlList)

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("500000"))
        assertThat(result.tradeCount).isEqualTo(2)
        assertThat(result.winRatePct).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `userId 격리 — 다른 userId 데이터 미노출`() {
        val userId2 = "user_test_002"
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)

        whenever(cache.getSummaryJson(eq(userId), any(), any(), any())).thenReturn(null)
        whenever(cache.getSummaryJson(eq(userId2), any(), any(), any())).thenReturn(null)
        whenever(
            performanceRepository.findPnlDailyRange(eq(userId), any(), any()),
        ).thenReturn(listOf(makePnlDaily(LocalDate.of(2026, 5, 5))))
        whenever(performanceRepository.findPnlDailyRange(eq(userId2), any(), any())).thenReturn(emptyList())

        val resultUser1 = sut.getSummary(userId, "MONTH", from, to)
        val resultUser2 = sut.getSummary(userId2, "MONTH", from, to)

        assertThat(resultUser1.totalPnl).isGreaterThan(BigDecimal.ZERO)
        assertThat(resultUser2.totalPnl).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `DAY period — today inline aggregate`() {
        val today = LocalDate.now(kst)

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), eq(today), eq(today)))
            .thenReturn(listOf(makePnlDaily(today, BigDecimal("80000"))))

        val result = sut.getSummary(userId, "DAY", null, null)

        assertThat(result.period).isEqualTo("DAY")
        assertThat(result.periodStart).isEqualTo(today)
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("80000"))
    }

    @Test
    fun `trade breakdown — 평균단가 replay로 매도 체결별 손익 반환`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 22)
        val buyOrder =
            makeOrderRequest(
                id = 1L,
                side = OrderSide.BUY,
            )
        val sellOrder =
            makeOrderRequest(
                id = 2L,
                side = OrderSide.SELL,
            )
        val executions =
            listOf(
                makeExecution(
                    id = 1L,
                    orderId = 1L,
                    date = LocalDate.of(2026, 5, 20),
                    qty = BigDecimal("2"),
                    price = BigDecimal("10000"),
                ),
                makeExecution(
                    id = 2L,
                    orderId = 2L,
                    date = LocalDate.of(2026, 5, 21),
                    qty = BigDecimal.ONE,
                    price = BigDecimal("12000"),
                ),
            )

        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any()))
            .thenReturn(executions)
        whenever(orderRequestJpaRepository.findAllById(listOf(1L, 2L))).thenReturn(listOf(buyOrder, sellOrder))
        whenever(instrumentJpaRepository.findAllById(listOf("005930"))).thenReturn(emptyList())

        val result = sut.getTradeBreakdown(userId, "MONTH", from, to)

        assertThat(result.totalRealizedPnl).isEqualByComparingTo(BigDecimal("2000.00"))
        assertThat(result.tradeCount).isEqualTo(1)
        assertThat(result.items.first().code).isEqualTo("005930")
        assertThat(result.items.first().avgBuyPrice).isEqualByComparingTo(BigDecimal("10000"))
        assertThat(result.items.first().realizedPnl).isEqualByComparingTo(BigDecimal("2000.00"))
    }

    @Test
    fun `mode 필터 — 실전과 모의 성과를 분리해서 계산한다`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 22)
        val mockBuyOrder = makeOrderRequest(id = 1L, side = OrderSide.BUY, mode = TradingMode.MOCK)
        val mockSellOrder = makeOrderRequest(id = 2L, side = OrderSide.SELL, mode = TradingMode.MOCK)
        val realBuyOrder = makeOrderRequest(id = 3L, side = OrderSide.BUY, mode = TradingMode.REAL)
        val realSellOrder = makeOrderRequest(id = 4L, side = OrderSide.SELL, mode = TradingMode.REAL)
        val executions =
            listOf(
                makeExecution(1L, 1L, LocalDate.of(2026, 5, 20), BigDecimal.ONE, BigDecimal("10000")),
                makeExecution(2L, 2L, LocalDate.of(2026, 5, 21), BigDecimal.ONE, BigDecimal("12000")),
                makeExecution(3L, 3L, LocalDate.of(2026, 5, 20), BigDecimal.ONE, BigDecimal("20000")),
                makeExecution(4L, 4L, LocalDate.of(2026, 5, 21), BigDecimal.ONE, BigDecimal("25000")),
            )

        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any()))
            .thenReturn(executions)
        whenever(orderRequestJpaRepository.findAllById(listOf(1L, 2L, 3L, 4L)))
            .thenReturn(listOf(mockBuyOrder, mockSellOrder, realBuyOrder, realSellOrder))
        whenever(instrumentJpaRepository.findAllById(listOf("005930"))).thenReturn(emptyList())

        val mockSummary = sut.getSummary(userId, "MONTH", from, to, TradingMode.MOCK)
        val realSummary = sut.getSummary(userId, "MONTH", from, to, TradingMode.REAL)
        val mockTrades = sut.getTradeBreakdown(userId, "MONTH", from, to, TradingMode.MOCK)

        assertThat(mockSummary.totalPnl).isEqualByComparingTo(BigDecimal("2000.00"))
        assertThat(realSummary.totalPnl).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(mockTrades.items).allSatisfy { item -> assertThat(item.mode).isEqualTo("MOCK") }
    }

    private fun makeOrderRequest(
        id: Long,
        side: OrderSide,
        mode: TradingMode = TradingMode.MOCK,
    ): OrderRequestJpaEntity =
        OrderRequestJpaEntity(
            id = id,
            signalId = 0L,
            strategyId = 0L,
            userId = userId,
            code = "005930",
            side = side,
            qty = BigDecimal.ONE,
            priceType = OrderPriceType.MARKET,
            status = OrderStatus.FILLED,
            mode = mode,
            idempotencyKey = "test-$id",
            requestedAt = LocalDate.of(2026, 5, 20).atStartOfDay(kst).toInstant(),
            source = OrderSource.MANUAL,
        )

    private fun makeExecution(
        id: Long,
        orderId: Long,
        date: LocalDate,
        qty: BigDecimal,
        price: BigDecimal,
    ): OrderExecutionJpaEntity =
        OrderExecutionJpaEntity(
            id = id,
            orderId = orderId,
            executedQty = qty,
            executedPrice = price,
            executedAt = date.atTime(10, 0).atZone(kst).toInstant(),
            fee = BigDecimal.ZERO,
            tax = BigDecimal.ZERO,
        )
}
