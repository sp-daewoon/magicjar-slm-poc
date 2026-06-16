package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * ConsumerOrderRequestRepositoryAdapter 단위 테스트 (backlog §61 fix evidence).
 *
 * ### 케이스 매트릭스
 *  (1) save — REQUESTED 도메인 → JPA entity 변환 검증 (자본 path 시작점)
 *  (2) updateKisAck — id 존재 + ACK 상태 전이 검증 (자본 path KIS ACK)
 *  (3) updateStatus REJECTED — rejectReason 변환 검증 (REAL_GATE_DENIED 등)
 *  (4) findById null 분기 검증
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class ConsumerOrderRequestRepositoryAdapterTest {
    private val jpa = mockk<ConsumerOrderRequestJpaRepository>()
    private val adapter = ConsumerOrderRequestRepositoryAdapter(jpa)

    @Test
    fun `(1) save - REQUESTED 도메인을 JPA entity로 변환 + id 채워서 반환`() {
        val now = Instant.parse("2026-05-18T03:02:55Z")
        val domain =
            OrderRequest(
                userId = "74683223",
                signalId = 99001L,
                strategyId = 1L,
                code = "005930",
                side = OrderSide.BUY,
                priceType = OrderPriceType.MARKET,
                quantity = 1,
                limitPrice = null,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.MOCK,
                idempotencyKey = "99001:74683223",
                requestedAt = now,
            )
        val entitySlot = slot<ConsumerOrderRequestJpaEntity>()
        every { jpa.save(capture(entitySlot)) } answers {
            val captured = entitySlot.captured
            ConsumerOrderRequestJpaEntity(
                id = 42L,
                signalId = captured.signalId,
                userId = captured.userId,
                code = captured.code,
                side = captured.side,
                qty = captured.qty,
                priceType = captured.priceType,
                limitPrice = captured.limitPrice,
                status = captured.status,
                mode = captured.mode,
                idempotencyKey = captured.idempotencyKey,
                requestedAt = captured.requestedAt,
            )
        }

        val result = adapter.save(domain)

        verify(exactly = 1) { jpa.save(any()) }
        assertThat(entitySlot.captured.userId).isEqualTo("74683223")
        assertThat(entitySlot.captured.signalId).isEqualTo(99001L)
        assertThat(entitySlot.captured.code).isEqualTo("005930")
        assertThat(entitySlot.captured.side).isEqualTo(OrderSide.BUY)
        assertThat(entitySlot.captured.qty).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(entitySlot.captured.status).isEqualTo(OrderStatus.REQUESTED)
        assertThat(entitySlot.captured.mode).isEqualTo(TradingMode.MOCK)
        assertThat(entitySlot.captured.idempotencyKey).isEqualTo("99001:74683223")
        assertThat(result.id).isEqualTo(42L)
        assertThat(result.status).isEqualTo(OrderStatus.REQUESTED)
    }

    @Test
    fun `(2) updateKisAck - 기존 entity 발견 시 ODNO·FWDG·ACK 상태 전이`() {
        val now = Instant.parse("2026-05-18T03:02:56Z")
        val existing =
            ConsumerOrderRequestJpaEntity(
                id = 42L,
                signalId = 99001L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.MOCK,
                idempotencyKey = "99001:74683223",
                requestedAt = now.minusSeconds(1),
            )
        every { jpa.findById(42L) } returns Optional.of(existing)
        every { jpa.findAllByKisOrderNoOrderByIdAsc("ODNO123") } returns emptyList()
        every { jpa.save(any()) } answers { firstArg() }

        val result = adapter.updateKisAck(42L, "ODNO123", "ORG001", now)

        assertThat(result).isNotNull
        assertThat(result?.kisOrderNo).isEqualTo("ODNO123")
        assertThat(result?.kisFwdgOrgno).isEqualTo("ORG001")
        assertThat(result?.status).isEqualTo(OrderStatus.ACK)
        assertThat(result?.acknowledgedAt).isEqualTo(now)
    }

    @Test
    fun `(2-2) updateKisAck - WS_PREINSERT sentinel이 있으면 execution과 audit을 원 주문으로 병합`() {
        val now = Instant.parse("2026-05-18T03:02:56Z")
        val canonical =
            ConsumerOrderRequestJpaEntity(
                id = 42L,
                signalId = 99001L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.REAL,
                idempotencyKey = "99001:74683223",
                requestedAt = now.minusSeconds(1),
            )
        val sentinel =
            ConsumerOrderRequestJpaEntity(
                id = 100L,
                signalId = 0L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.ACK,
                mode = TradingMode.REAL,
                kisOrderNo = "ODNO123",
                idempotencyKey = "WS_PREINSERT:ODNO123",
                requestedAt = now.minusSeconds(1),
            )
        every { jpa.findById(42L) } returns Optional.of(canonical)
        every { jpa.findAllByKisOrderNoOrderByIdAsc("ODNO123") } returns listOf(sentinel)
        every { jpa.reassignExecutions(100L, 42L) } returns 1
        every { jpa.reassignAuditEvents(100L, 42L) } returns 1
        every { jpa.delete(sentinel) } returns Unit
        every { jpa.save(any()) } answers { firstArg() }

        val result = adapter.updateKisAck(42L, "ODNO123", "ORG001", now)

        assertThat(result?.kisOrderNo).isEqualTo("ODNO123")
        verify(exactly = 1) { jpa.reassignExecutions(100L, 42L) }
        verify(exactly = 1) { jpa.reassignAuditEvents(100L, 42L) }
        verify(exactly = 1) { jpa.delete(sentinel) }
    }

    @Test
    fun `(3) updateStatus REJECTED - rejectReason 영속`() {
        val existing =
            ConsumerOrderRequestJpaEntity(
                id = 99L,
                signalId = 99001L,
                userId = "74683223",
                code = "005930",
                side = OrderSide.BUY,
                qty = BigDecimal.ONE,
                priceType = OrderPriceType.MARKET,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.REAL,
                idempotencyKey = "k1",
                requestedAt = Instant.now(),
            )
        every { jpa.findById(99L) } returns Optional.of(existing)
        every { jpa.save(any()) } answers { firstArg() }

        val result = adapter.updateStatus(99L, OrderStatus.REJECTED, "CREDENTIAL_NOT_FOUND")

        assertThat(result?.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(result?.rejectReason).isEqualTo("CREDENTIAL_NOT_FOUND")
    }

    @Test
    fun `(4) findById null 분기 - JPA empty Optional → null 반환`() {
        every { jpa.findById(404L) } returns Optional.empty()

        val result = adapter.findById(404L)

        assertThat(result).isNull()
    }

    @Test
    fun `(5) D2 회귀 - save가 orderTrigger·triggerRefId를 entity로 영속하고 read가 도메인으로 복원`() {
        // 라이브 REAL 286(BUY·signalId=732) evidence: consumer entity·toEntity 매핑에 두 컬럼이 누락돼
        // OrderRouterKafkaListener 가 산정한 STRATEGY_SIGNAL+732 키가 DB 에 NULL 로 떨어졌다. 매핑 round-trip 검증.
        val now = Instant.parse("2026-06-11T04:31:00Z")
        val domain =
            OrderRequest(
                userId = "74683223",
                signalId = 732L,
                strategyId = 85L,
                code = "005930",
                side = OrderSide.BUY,
                priceType = OrderPriceType.MARKET,
                quantity = 1,
                limitPrice = null,
                status = OrderStatus.REQUESTED,
                mode = TradingMode.REAL,
                idempotencyKey = "732:74683223",
                requestedAt = now,
                orderTrigger = OrderTrigger.STRATEGY_SIGNAL,
                triggerRefId = 732L,
            )
        val entitySlot = slot<ConsumerOrderRequestJpaEntity>()
        every { jpa.save(capture(entitySlot)) } answers {
            val c = entitySlot.captured
            // 영속 후 read 경로(toDomain) 정합도 함께 검증 — 캡처 entity 그대로 반환(id 채움).
            ConsumerOrderRequestJpaEntity(
                id = 286L,
                signalId = c.signalId,
                strategyId = c.strategyId,
                userId = c.userId,
                code = c.code,
                side = c.side,
                qty = c.qty,
                priceType = c.priceType,
                limitPrice = c.limitPrice,
                status = c.status,
                mode = c.mode,
                idempotencyKey = c.idempotencyKey,
                requestedAt = c.requestedAt,
                source = c.source,
                orderTrigger = c.orderTrigger,
                triggerRefId = c.triggerRefId,
            )
        }

        val result = adapter.save(domain)

        // 1) toEntity — 두 키가 entity 로 흘렀는가(과거 NULL 결함 폐지).
        assertThat(entitySlot.captured.orderTrigger).isEqualTo(OrderTrigger.STRATEGY_SIGNAL)
        assertThat(entitySlot.captured.triggerRefId).isEqualTo(732L)
        // 2) toDomain — 읽기 경로 복원(rebuild·signal-trace 귀속 판정 입력).
        assertThat(result.orderTrigger).isEqualTo(OrderTrigger.STRATEGY_SIGNAL)
        assertThat(result.triggerRefId).isEqualTo(732L)
    }

    @Test
    fun `(6) countRealOrdersBetween - REAL·EXTERNAL 제외 인자로 JPA 위임 + 결과 그대로 반환`() {
        // 2026-06-12 hotfix — canary 일일 cap 카운트. adapter 는 mode=REAL + excludedSource=EXTERNAL 을
        // 고정 인자로 JPA 에 위임한다(side=BUY·status<>REJECTED 필터는 JPA @Query 리터럴 책임).
        val from = Instant.parse("2026-06-11T15:00:00Z")
        val to = Instant.parse("2026-06-12T15:00:00Z")
        every {
            jpa.countRealOrdersBetween(TradingMode.REAL, OrderSource.EXTERNAL, from, to)
        } returns 4L

        val count = adapter.countRealOrdersBetween(from, to)

        assertThat(count).isEqualTo(4L)
        verify(exactly = 1) {
            jpa.countRealOrdersBetween(TradingMode.REAL, OrderSource.EXTERNAL, from, to)
        }
    }
}
