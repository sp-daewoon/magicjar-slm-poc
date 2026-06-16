package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionReplayLockPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StrategyPositionExecutionReplayServiceTest {
    private val executionRepository = mockk<OrderExecutionRepository>()
    private val updatePort = mockk<ExecutionStrategyPositionUpdatePort>(relaxed = true)
    private val strategyPositionPort = mockk<StrategyPositionPort>()
    private val replayLockPort = mockk<StrategyPositionReplayLockPort>()
    private val service =
        StrategyPositionExecutionReplayService(
            orderExecutionRepositoryProvider = provider(executionRepository),
            strategyPositionUpdatePortProvider = provider(updatePort),
            strategyPositionPortProvider = provider(strategyPositionPort),
            replayLockPortProvider = provider(replayLockPort),
        )

    init {
        // default: lock 항상 획득 성공(직렬화 메커니즘 테스트는 별도 케이스에서 override).
        every { replayLockPort.lockForReplay(any(), any(), any()) } returns
            StrategyPositionReplayLockPort.LockResult.ACQUIRED
    }

    @Test
    fun `strategy_position row가 없으면 저장된 체결들을 원 주문 기준으로 재생한다`() {
        val request = request()
        val executions = listOf(execution(id = 10L), execution(id = 11L))
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns null
        every { executionRepository.findByOrderId(99L) } returns executions

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isEqualTo(2)
        verify(exactly = 1) { replayLockPort.lockForReplay("12345678", 100L, "005930") }
        verify(exactly = 1) { updatePort.applyExecution(request, executions[0]) }
        verify(exactly = 1) { updatePort.applyExecution(request, executions[1]) }
    }

    @Test
    fun `strategy_position row가 이미 있으면 중복 재생하지 않는다`() {
        val request = request()
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns existingPosition()

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        verify(exactly = 0) { executionRepository.findByOrderId(any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    @Test
    fun `strategyId가 0 sentinel이면 lock도 잡지 않고 즉시 skip한다`() {
        val request = request().copy(strategyId = 0L)

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        verify(exactly = 0) { replayLockPort.lockForReplay(any(), any(), any()) }
        verify(exactly = 0) { strategyPositionPort.findByUserStrategyCode(any(), any(), any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    @Test
    fun `orderId가 null이면 DB를 건드리지 않고 즉시 skip한다`() {
        val request = request().copy(id = null)

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        verify(exactly = 0) { replayLockPort.lockForReplay(any(), any(), any()) }
        verify(exactly = 0) { strategyPositionPort.findByUserStrategyCode(any(), any(), any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    @Test
    fun `저장된 execution이 비어 있으면 apply 없이 0을 반환한다`() {
        val request = request()
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns null
        every { executionRepository.findByOrderId(99L) } returns emptyList()

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        verify(exactly = 1) { replayLockPort.lockForReplay("12345678", 100L, "005930") }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    @Test
    fun `advisory lock 미획득이면 over-count 회피 위해 replay를 skip한다`() {
        val request = request()
        every { replayLockPort.lockForReplay("12345678", 100L, "005930") } returns
            StrategyPositionReplayLockPort.LockResult.SKIPPED

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        // lock 미획득이면 존재확인·execution 조회·apply 모두 진입하지 않음.
        verify(exactly = 0) { strategyPositionPort.findByUserStrategyCode(any(), any(), any()) }
        verify(exactly = 0) { executionRepository.findByOrderId(any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    @Test
    fun `순차 이중 호출 — 첫 호출 후 row 존재하면 2차는 skip(멱등)`() {
        val request = request()
        val executions = listOf(execution(id = 10L))
        // 1차: 없음 → 재생. 2차: 존재 → skip.
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returnsMany
            listOf(null, existingPosition())
        every { executionRepository.findByOrderId(99L) } returns executions

        val first = service.replayIfMissing(request, "WS_PREINSERT_ACK_MERGE")
        val second = service.replayIfMissing(request, "REST_BACKFILL_INSERTED")

        assertThat(first).isEqualTo(1)
        assertThat(second).isZero()
        // execution 가산은 1차에서만 정확히 1회(이중계상 0).
        verify(exactly = 1) { updatePort.applyExecution(request, executions[0]) }
    }

    @Test
    fun `동시 이중 replay — advisory lock 직렬화로 execution 이중계상 0`() {
        // advisory lock 의 직렬화 의미(같은 키 동시 진입 시 한 번에 하나만 임계영역)를 deterministic 하게 모사한다.
        // 락이 read-modify-write 구간(존재확인→apply) 전체를 감싸므로, 먼저 진입한 호출이 row 를 만든 뒤에야
        // 다음 호출이 존재확인을 수행 → 두 번째는 existing!=null 로 skip 한다.
        val request = request()
        val executions = listOf(execution(id = 10L))

        val keyLock = ConcurrentHashMap<String, Any>()
        val rowCreated = AtomicInteger(0)
        val applyCount = AtomicInteger(0)

        // 임계영역 직렬화 모사: lockForReplay 가 (user,strategy,code) 키 monitor 를 잡고,
        // 같은 키 호출은 직전 호출의 replayIfMissing 이 끝날 때까지 블로킹된다.
        val monitor = keyLock.computeIfAbsent("12345678:100:005930") { Any() }
        every { replayLockPort.lockForReplay("12345678", 100L, "005930") } answers {
            // 실제 advisory xact lock 은 트랜잭션 종료 시 해제되지만, 여기선 메서드 종료까지 monitor 보유로 등가 모사.
            StrategyPositionReplayLockPort.LockResult.ACQUIRED
        }
        // 존재확인: row 가 한 번이라도 만들어졌으면 non-null 반환(임계영역 직렬화 전제).
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } answers {
            if (rowCreated.get() > 0) existingPosition() else null
        }
        every { executionRepository.findByOrderId(99L) } returns executions
        every { updatePort.applyExecution(request, executions[0]) } answers {
            applyCount.incrementAndGet()
            rowCreated.incrementAndGet() // apply 가 곧 row 생성(첫 BUY upsert).
        }

        val results = IntArray(2)
        val threads =
            (0 until 2).map { idx ->
                Thread {
                    // monitor 직렬화 — advisory lock 임계영역(replayIfMissing 전체)을 한 번에 하나만 실행.
                    synchronized(monitor) {
                        results[idx] = service.replayIfMissing(request, "CONCURRENT_$idx")
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // execution 가산은 정확히 1회 — over-count 0.
        assertThat(applyCount.get()).isEqualTo(1)
        assertThat(results.sum()).isEqualTo(1)
    }

    @Test
    fun `under-count 명세 고정 — 다른 주문이 같은 전략·종목 보유 중이면 replay skip(누락 허용·over-count 아님)`() {
        // 같은 전략·종목이 다른 주문으로 이미 보유 중(existing != null)이면, sentinel 에 붙었던 누락 execution 은
        // replay 되지 않는다(under-count). 이는 의도된 보수적 동작 — row 가 있는데 가산하면 이중계상 위험.
        // 누락은 position(KIS 권위) drift WARN/auto-correct 로 회복하므로 자본 흐름상 안전(유령 물량 0).
        val request = request()
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns
            existingPosition(quantity = 5) // 다른 주문으로 이미 5주 보유.

        val replayed = service.replayIfMissing(request, "REST_BACKFILL_INSERTED")

        assertThat(replayed).isZero()
        // 누락 execution 은 손실되지만(under-count) over-count 는 0 — apply 미진입.
        verify(exactly = 0) { executionRepository.findByOrderId(any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    private fun existingPosition(quantity: Int = 1): StrategyPosition =
        StrategyPosition(
            userId = "12345678",
            strategyId = 100L,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal("78000"),
            openedAt = kotlinx.datetime.Instant.fromEpochSeconds(1747094405L),
            updatedAt = kotlinx.datetime.Instant.fromEpochSeconds(1747094405L),
        )

    private fun request(): OrderRequest =
        OrderRequest(
            id = 99L,
            userId = "12345678",
            signalId = 42L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.MARKET,
            quantity = 2,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = "ODNO123",
            idempotencyKey = "42:12345678",
            requestedAt = Instant.ofEpochSecond(1747094405L),
            source = OrderSource.AUTO,
        )

    private fun execution(id: Long): OrderExecution =
        OrderExecution(
            id = id,
            orderId = 99L,
            userId = "12345678",
            kisOrderNo = "ODNO123",
            executedQuantity = 1,
            executedPrice = BigDecimal("78000"),
            executedAt = Instant.ofEpochSecond(1747094406L),
            eventId = "ODNO123:$id",
            createdAt = Instant.ofEpochSecond(1747094406L),
        )

    private fun <T : Any> provider(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}
