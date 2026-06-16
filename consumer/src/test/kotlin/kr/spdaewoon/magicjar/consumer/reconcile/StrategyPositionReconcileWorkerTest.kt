package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider

/**
 * [StrategyPositionReconcileWorker] 단위 테스트 — ADR 0115 §P1-1 amend (2026-06-10).
 *
 * 순회 범위 = position(KIS 권위 quantity>0) ∪ strategy_position(quantity>0 유령) code 합집합.
 * under-count(KIS만 보유) + over-count(strategy_position 만 잔존하는 유령) 두 방향 + 종목 실패 격리 검증.
 */
class StrategyPositionReconcileWorkerTest {
    private val positionRepo: PositionJpaRepository = mock()
    private val strategyPositionRepo: StrategyPositionJpaRepository = mock()
    private val adapter: ConsumerExecutionStrategyPositionAdapter = mock()
    private val holdingIndex: HoldingPositionIndex = mock()
    private val holdingIndexProvider: ObjectProvider<HoldingPositionIndex> = mock()
    private val worker =
        StrategyPositionReconcileWorker(positionRepo, strategyPositionRepo, adapter, holdingIndexProvider)

    @BeforeEach
    fun setUp() {
        // 기본: strategy_position 잔존 종목 0 (대부분 케이스는 position 측만으로 충분).
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive(any()))
            .thenReturn(emptyList())
        // index provider — 정합 후 onFillRefresh 동반 호출(⑤). 빈 합류 가정.
        whenever(holdingIndexProvider.getIfAvailable()).thenReturn(holdingIndex)
    }

    @Test
    fun `KIS 보유 2종목 → 각 종목 reconcile 호출`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(
                    PositionJpaEntity(userId = "user-A", code = "005930", quantity = 3),
                    PositionJpaEntity(userId = "user-A", code = "000660", quantity = 5),
                ),
            )

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(2)
        verify(adapter).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
        verify(adapter).reconcileStrategyPositionDrift("user-A", "000660", "MOCK")
    }

    @Test
    fun `보유 0종목 → 호출 0`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan(any(), any(), any()))
            .thenReturn(emptyList())

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(0)
        verify(adapter, never()).reconcileStrategyPositionDrift(any(), any(), any())
    }

    @Test
    fun `한 종목 실패해도 나머지 종목 정합 진행(격리)`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(
                    PositionJpaEntity(userId = "user-A", code = "005930", quantity = 3),
                    PositionJpaEntity(userId = "user-A", code = "000660", quantity = 5),
                ),
            )
        doThrow(RuntimeException("boom"))
            .whenever(adapter)
            .reconcileStrategyPositionDrift(eq("user-A"), eq("005930"), eq("MOCK"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        // 005930 실패해도 000660 은 정합.
        assertThat(attempted).isEqualTo(1)
        verify(adapter, times(1)).reconcileStrategyPositionDrift("user-A", "000660", "MOCK")
    }

    @Test
    fun `position 조회 실패해도 strategy_position 측 유령 종목은 정합 진행(부분 격리)`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan(any(), any(), any()))
            .thenThrow(RuntimeException("db down"))
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive("user-A"))
            .thenReturn(listOf("005930"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        // position 측이 죽어도 strategy_position 측 유령 over-count 정합은 계속(보수적 부분 집합 진행).
        assertThat(attempted).isEqualTo(1)
        verify(adapter).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
    }

    @Test
    fun `양쪽 조회 모두 실패 → skip(0 반환, 예외 전파 없음)`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan(any(), any(), any()))
            .thenThrow(RuntimeException("db down"))
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive(any()))
            .thenThrow(RuntimeException("db down"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(0)
        verifyNoInteractions(adapter)
    }

    @Test
    fun `유령 over-count — KIS position 0주여도 strategy_position 잔존 종목이 union 으로 정합 도달`() {
        // 2026-06-10 오후 incident 재현: AUTO SELL 체결 후 strategy_position 차감 누락 →
        // KIS(position) 0주인데 strategy_position 3주 잔존. position 측 enumeration 에선 빠지지만
        // strategy_position union 으로 reconcile 에 도달해야 한다.
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(emptyList())
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive("user-A"))
            .thenReturn(listOf("005930"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(1)
        verify(adapter).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
    }

    @Test
    fun `union distinct — 양쪽에 동일 종목 있으면 1회만 정합 호출`() {
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(PositionJpaEntity(userId = "user-A", code = "005930", quantity = 5)),
            )
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive("user-A"))
            .thenReturn(listOf("005930"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(1)
        verify(adapter, times(1)).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
    }

    @Test
    fun `union 합집합 — KIS only + strategy only 각각 정합`() {
        // 000660: KIS 보유(under-count 방향), 005930: strategy_position 만 잔존(over-count 유령).
        whenever(positionRepo.findByUserIdAndSourceAndQuantityGreaterThan("user-A", "MOCK", 0))
            .thenReturn(
                listOf(PositionJpaEntity(userId = "user-A", code = "000660", quantity = 5)),
            )
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive("user-A"))
            .thenReturn(listOf("005930"))

        val attempted = worker.reconcileForUser("user-A", "MOCK")

        assertThat(attempted).isEqualTo(2)
        verify(adapter).reconcileStrategyPositionDrift("user-A", "000660", "MOCK")
        verify(adapter).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")
    }
}
