package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * ConsumerOrderExecutionRepositoryAdapter 단위 테스트
 * (T-O Phase 1 B-2 합류 evidence · spec `2026-05-18-to-main-track-entry-plan.md` §B-2).
 *
 * ### 케이스 매트릭스 (spec §B-2 의무 3건)
 *  (1) save — 도메인 OrderExecution → JPA entity 변환 + id 채워서 반환
 *      (분할 체결 H0STCNI0 CNTG_YN="2" 수신 시 INSERT path 시작점)
 *  (2) sumExecutedQuantityByOrder — 누적 합 (multiple executions → SUM)
 *      (PARTIALLY_FILLED → FILLED 전이 판정 — KisExecutionWsListener:282)
 *  (3) findByOrderId — executedAt ASC 시간순 정렬
 *      (OrderDetailPage ExecutionTable 분할 체결 시각화 path)
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class ConsumerOrderExecutionRepositoryAdapterTest {
    private val jpa = mockk<ConsumerOrderExecutionJpaRepository>()
    private val adapter = ConsumerOrderExecutionRepositoryAdapter(jpa)

    @Test
    fun `(1) save - 도메인 OrderExecution을 JPA entity로 변환 + id 채워서 반환`() {
        val now = Instant.parse("2026-05-18T03:02:55Z")
        val executedAt = Instant.parse("2026-05-18T05:30:12Z")
        val domain =
            OrderExecution(
                orderId = 42L,
                userId = "74683223",
                kisOrderNo = "ODNO123",
                executedQuantity = 5,
                executedPrice = BigDecimal("70500.0000"),
                executedAt = executedAt,
                createdAt = now,
            )
        val entitySlot = slot<ConsumerOrderExecutionJpaEntity>()
        every { jpa.save(capture(entitySlot)) } answers {
            val captured = entitySlot.captured
            ConsumerOrderExecutionJpaEntity(
                id = 7L,
                orderId = captured.orderId,
                executedQty = captured.executedQty,
                executedPrice = captured.executedPrice,
                executedAt = captured.executedAt,
                fee = captured.fee,
                tax = captured.tax,
                kisExecutionNo = captured.kisExecutionNo,
                createdAt = captured.createdAt,
            )
        }

        val result = adapter.save(domain)

        verify(exactly = 1) { jpa.save(any()) }
        assertThat(entitySlot.captured.orderId).isEqualTo(42L)
        assertThat(entitySlot.captured.executedQty).isEqualByComparingTo(BigDecimal("5"))
        assertThat(entitySlot.captured.executedPrice).isEqualByComparingTo(BigDecimal("70500.0000"))
        assertThat(entitySlot.captured.executedAt).isEqualTo(executedAt)
        assertThat(entitySlot.captured.fee).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(entitySlot.captured.tax).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(entitySlot.captured.kisExecutionNo).isEqualTo("ODNO123")
        assertThat(entitySlot.captured.createdAt).isEqualTo(now)
        assertThat(result.id).isEqualTo(7L)
        assertThat(result.orderId).isEqualTo(42L)
        assertThat(result.executedQuantity).isEqualTo(5)
    }

    @Test
    fun `(2) sumExecutedQuantityByOrder - 분할 체결 누적 합 SUM 반환 (PARTIAL → FILLED 전이 판정)`() {
        // 3·5·2 분할 체결 누적 = 10
        every { jpa.sumExecutedQtyByOrderId(42L) } returns BigDecimal("10")

        val result = adapter.sumExecutedQuantityByOrder(42L)

        verify(exactly = 1) { jpa.sumExecutedQtyByOrderId(42L) }
        assertThat(result).isEqualTo(10)
    }

    @Test
    fun `(3) findByOrderId - executedAt ASC 시간순 정렬 결과 변환`() {
        val t1 = Instant.parse("2026-05-18T05:30:12Z")
        val t2 = Instant.parse("2026-05-18T05:30:18Z")
        val t3 = Instant.parse("2026-05-18T05:30:25Z")
        every { jpa.findByOrderIdOrderByExecutedAtAsc(42L) } returns
            listOf(
                ConsumerOrderExecutionJpaEntity(
                    id = 1L,
                    orderId = 42L,
                    executedQty = BigDecimal("3"),
                    executedPrice = BigDecimal("70500.0000"),
                    executedAt = t1,
                    kisExecutionNo = "ODNO123",
                    createdAt = t1,
                ),
                ConsumerOrderExecutionJpaEntity(
                    id = 2L,
                    orderId = 42L,
                    executedQty = BigDecimal("5"),
                    executedPrice = BigDecimal("70510.0000"),
                    executedAt = t2,
                    kisExecutionNo = "ODNO123",
                    createdAt = t2,
                ),
                ConsumerOrderExecutionJpaEntity(
                    id = 3L,
                    orderId = 42L,
                    executedQty = BigDecimal("2"),
                    executedPrice = BigDecimal("70520.0000"),
                    executedAt = t3,
                    kisExecutionNo = "ODNO123",
                    createdAt = t3,
                ),
            )

        val result = adapter.findByOrderId(42L)

        verify(exactly = 1) { jpa.findByOrderIdOrderByExecutedAtAsc(42L) }
        assertThat(result).hasSize(3)
        assertThat(result.map { it.executedAt }).containsExactly(t1, t2, t3)
        assertThat(result.map { it.executedQuantity }).containsExactly(3, 5, 2)
        assertThat(result.map { it.executedPrice }).containsExactly(
            BigDecimal("70500.0000"),
            BigDecimal("70510.0000"),
            BigDecimal("70520.0000"),
        )
        // (cross-ref) sum == 10 정합 — 케이스 (2) 검증 정합
        assertThat(result.sumOf { it.executedQuantity }).isEqualTo(10)
    }
}
