package kr.spdaewoon.magicjar.batch.performance

import kr.spdaewoon.magicjar.batch.job.performance.DailyPerformanceJob
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderRequestJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderRequestJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPerformanceSummaryJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * DailyPerformanceJobTest — 3건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. 정상 cron 1 사용자 + N 거래 → pnl_daily INSERT 1 row + summary 5종 UPSERT
 * 2. empty user (활성 사용자 없음) → 0 skip
 * 3. 기존 row 재계산 UPSERT — 중복 실행 멱등성
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class DailyPerformanceJobTest {
    private val orderRequestJpaRepository: BatchOrderRequestJpaRepository = mock()
    private val orderExecutionJpaRepository: BatchOrderExecutionJpaRepository = mock()
    private val pnlDailyJpaRepository: BatchPnlDailyJpaRepository = mock()
    private val summaryJpaRepository: BatchPerformanceSummaryJpaRepository = mock()
    private val redis: StringRedisTemplate = mock()

    private val sut =
        DailyPerformanceJob(
            orderRequestJpaRepository,
            orderExecutionJpaRepository,
            pnlDailyJpaRepository,
            summaryJpaRepository,
            redis,
        )

    private val userId = "user_test_001"
    private val kst = ZoneId.of("Asia/Seoul")
    private val targetDate = LocalDate.of(2026, 5, 13)

    private fun makeOrderRequest(
        id: Long = 1L,
        code: String = "005930",
        side: String = "BUY",
    ) = BatchOrderRequestJpaEntity(id = id, userId = userId, code = code, side = side)

    private fun makeExecution(
        id: Long = 1L,
        orderRequest: BatchOrderRequestJpaEntity,
        tradeDate: LocalDate = targetDate,
        qty: BigDecimal = BigDecimal.ONE,
        price: BigDecimal,
    ): BatchOrderExecutionJpaEntity =
        BatchOrderExecutionJpaEntity(
            id = id,
            orderRequest = orderRequest,
            executedQty = qty,
            executedPrice = price,
            executedAt = tradeDate.atTime(10, 0).atZone(kst).toInstant(),
            fee = BigDecimal.ZERO,
            tax = BigDecimal.ZERO,
        )

    @Test
    fun `정상 1 사용자 N 거래 — 체결 이력 replay로 pnl_daily INSERT + summary 5종 UPSERT`() {
        val buyRequest = makeOrderRequest(id = 1L, side = "BUY")
        val sellRequest = makeOrderRequest(id = 2L, side = "SELL")
        val executions =
            listOf(
                makeExecution(
                    id = 1L,
                    orderRequest = buyRequest,
                    tradeDate = targetDate.minusDays(1),
                    qty = BigDecimal("2"),
                    price = BigDecimal("10000"),
                ),
                makeExecution(id = 2L, orderRequest = sellRequest, price = BigDecimal("12000")),
                makeExecution(id = 3L, orderRequest = sellRequest, price = BigDecimal("9000")),
            )

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any<Instant>()))
            .thenReturn(executions)
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(null)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(eq(userId), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        sut.run(targetDate)

        val pnlCaptor = argumentCaptor<kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaEntity>()
        verify(pnlDailyJpaRepository).save(pnlCaptor.capture())

        val saved = pnlCaptor.firstValue
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.tradeDate).isEqualTo(targetDate)
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(saved.winCount).isEqualTo(1)
        assertThat(saved.lossCount).isEqualTo(1)
        assertThat(saved.tradeCount).isEqualTo(2)
    }

    @Test
    fun `empty user — 활성 사용자 없음 skip`() {
        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(emptyList())

        sut.run(targetDate)

        verify(orderExecutionJpaRepository, never()).findByUserIdAndExecutedAtBefore(any(), any())
        verify(pnlDailyJpaRepository, never()).save(any())
    }

    @Test
    fun `기존 row 재계산 UPSERT — 멱등성 DELETE + INSERT`() {
        val existingPnl = mock<kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaEntity>()
        val buyRequest = makeOrderRequest(id = 1L, side = "BUY")
        val sellRequest = makeOrderRequest(id = 2L, side = "SELL")
        val executions =
            listOf(
                makeExecution(
                    id = 1L,
                    orderRequest = buyRequest,
                    tradeDate = targetDate.minusDays(1),
                    price = BigDecimal("10000"),
                ),
                makeExecution(id = 2L, orderRequest = sellRequest, price = BigDecimal("11000")),
            )

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(any(), any())).thenReturn(executions)
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(existingPnl)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(any(), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        sut.run(targetDate)

        // 기존 row DELETE 확인
        verify(pnlDailyJpaRepository).delete(existingPnl)
        verify(pnlDailyJpaRepository).flush()
        // 재삽입 확인
        verify(pnlDailyJpaRepository).save(any())
    }
}
