package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CountDownLatch

/**
 * [HoldingPositionIndex] 동시성 회귀 — 적대 리뷰 R3 (backend-core 2026-06-07).
 *
 * 검증 대상 race(fix 전 누락 가능):
 *   1. refresh()가 findAllHolding() snapshot(신규 BUY code 미포함)을 읽는 사이
 *   2. onFillRefresh("newCode")가 인덱스에 신규 매수 종목을 추가
 *   3. refresh()의 retainAll이 그 신규 code를 제거 → 손절 안전망 인덱스에서 최대 60초 누락
 * fix: writeLock + refreshStartedAtMs 이후 fill-refresh 된 code 보존 → newCode 가 살아남아야 한다.
 */
class HoldingPositionIndexConcurrencyTest {
    private fun pos(
        code: String,
        strategyId: Long = 100L,
        qty: Int = 10,
    ) = StrategyPositionJpaEntity(
        userId = "u1",
        strategyId = strategyId,
        code = code,
        quantity = qty,
        avgPrice = BigDecimal("50000"),
        updatedAt = Instant.now(),
    )

    private fun strategyEntity(id: Long) =
        mockk<ConsumerStrategyJpaEntity>(relaxed = true).also {
            every { it.fixedStopLossPct } returns BigDecimal("3")
            every { it.takeProfitPct } returns BigDecimal("5")
        }

    @Test
    fun `refresh retainAll 은 동시에 추가된 신규 BUY code 를 제거하지 않는다`() {
        val positionRepo = mockk<StrategyPositionJpaRepository>()
        val strategyRepo = mockk<ConsumerStrategyJpaRepository>()
        every { strategyRepo.findById(any()) } answers { Optional.of(strategyEntity(firstArg())) }

        // 신규 BUY code "111111" 은 refresh 의 snapshot DB read 직후 onFillRefresh 로 들어온다.
        val refreshReadStarted = CountDownLatch(1)
        val fillDone = CountDownLatch(1)

        // refresh 의 findAllHolding: 기존 보유 "000001" 만 포함(신규 "111111" 미포함). read 직후 onFillRefresh 를 끼워넣는다.
        every { positionRepo.findAllHolding() } answers {
            refreshReadStarted.countDown()
            // onFillRefresh 가 인덱스에 "111111" 을 추가하고 timestamp 를 찍을 시간을 준다.
            fillDone.await()
            listOf(pos("000001"))
        }
        // onFillRefresh("111111") 용 단건 조회.
        every { positionRepo.findHoldingByCode("111111") } returns listOf(pos("111111"))

        val index =
            HoldingPositionIndex(
                strategyPositionRepository = positionRepo,
                strategyRepository = strategyRepo,
                enabled = true,
            )

        val fillThread =
            Thread {
                refreshReadStarted.await()
                index.onFillRefresh("111111")
                fillDone.countDown()
            }
        fillThread.start()

        index.refresh()
        fillThread.join()

        // 신규 BUY code 가 보존돼야 한다(fix 전이면 retainAll 로 제거돼 비어 있음).
        assertThat(index.heldBy("111111")).hasSize(1)
        // 기존 보유 code 도 정상.
        assertThat(index.heldBy("000001")).hasSize(1)
    }

    @Test
    fun `refresh 는 snapshot 에서 사라진 전량청산 code 는 제거한다`() {
        val positionRepo = mockk<StrategyPositionJpaRepository>()
        val strategyRepo = mockk<ConsumerStrategyJpaRepository>()
        every { strategyRepo.findById(any()) } answers { Optional.of(strategyEntity(firstArg())) }
        every { positionRepo.findHoldingByCode("000001") } returns listOf(pos("000001"))
        // 첫 refresh: 보유 1건.
        every { positionRepo.findAllHolding() } returns listOf(pos("000001"))

        val index =
            HoldingPositionIndex(
                strategyPositionRepository = positionRepo,
                strategyRepository = strategyRepo,
                enabled = true,
            )
        index.onFillRefresh("000001")
        assertThat(index.heldBy("000001")).hasSize(1)

        // 다음 refresh 에서 전량 청산되어 snapshot 에 없음(동시 fill 없음) → 제거돼야 한다.
        every { positionRepo.findAllHolding() } returns emptyList()
        index.refresh()
        assertThat(index.heldBy("000001")).isEmpty()
    }
}
