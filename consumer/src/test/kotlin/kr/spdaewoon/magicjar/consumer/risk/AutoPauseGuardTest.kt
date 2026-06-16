package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.risk.AutoPauseGuard.Companion.BENIGN_AUTO_PAUSE_EXCLUDED_RULES
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * AutoPauseGuard 단위 테스트 (ADR 0067 §7-1 + F-1 + F9 정합).
 *
 * 매트릭스:
 *  1. threshold 미달 → false 반환 (callback 미호출)
 *  2. threshold 도달 → true 반환 + callback 호출 (RISK_VIOLATION reason)
 *  3. RiskEventRepository 미합류(Phase 2 부재) → false skip
 *  4. [F9] 무해 가드 rule 제외 set 을 repo 카운트에 전달한다
 *  5. [F9 회귀] 무해 rule 제외 후 카운트가 임계 미만이면 정지 0 (정상 거동이 자동정지 안 함)
 *  6. [F9 회귀] 위험 rule 카운트가 임계 도달이면 정지 발동 (자본 위험 신호는 그대로 동작)
 *
 * AI 호출 0 — T-RISK 트랙 정책.
 */
class AutoPauseGuardTest {
    private val riskEventRepository = mockk<RiskEventRepository>(relaxed = true)
    private val provider =
        mockk<ObjectProvider<RiskEventRepository>>().also {
            every { it.getIfAvailable() } returns riskEventRepository
        }
    private val callback = mockk<EvaluatorAutoDisableCallback>(relaxed = true)
    private val guard = AutoPauseGuard(provider, callback)

    @Test
    fun `threshold 미달 시 false 반환 — callback 미호출`() {
        every {
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L, excludedRules = any())
        } returns 5

        val result = guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `threshold 도달 시 true 반환 + onAutoDisable 호출`() {
        every {
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L, excludedRules = any())
        } returns 10

        val result = guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 100L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `RiskEventRepository 미합류 시 false skip`() {
        val emptyProvider =
            mockk<ObjectProvider<RiskEventRepository>>().also {
                every { it.getIfAvailable() } returns null
            }
        val skipGuard = AutoPauseGuard(emptyProvider, callback)

        val result = skipGuard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `F9 — 무해 가드 rule 제외 set 을 repo 카운트에 전달한다`() {
        val excludedSlot = slot<Set<String>>()
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 100L,
                windowMinutes = 10L,
                excludedRules = capture(excludedSlot),
            )
        } returns 0

        guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        // 보유 0 SELL 차단·중복 매수 차단·익절 예약 SELL 가용 0 = 정상 거동 → 임계 제외 의무.
        assertThat(excludedSlot.captured)
            .containsExactlyInAnyOrder(
                RiskRule.SELL_WITHOUT_POSITION.name,
                RiskRule.MAX_CONCURRENT_POSITIONS.name,
                RiskRule.SELL_QUANTITY_UNAVAILABLE.name,
            )
        // 진짜 자본 위험 신호는 제외하지 않는다(회귀 방지).
        assertThat(excludedSlot.captured)
            .doesNotContain(
                RiskRule.DAILY_LOSS_LIMIT.name,
                RiskRule.POSITION_LIMIT.name,
                RiskRule.TRADE_FREQUENCY_MINUTE.name,
                RiskRule.KILL_SWITCH_ON.name,
            )
    }

    @Test
    fun `F9 회귀 — 무해 rule 10건이어도 제외 후 카운트 0이면 정지 0`() {
        // repo 계약: excludedRules 적용 후 카운트 반환 → 무해 가드만 누적된 flat 전략은 0 으로 떨어진다.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 85L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 0

        val result = guard.checkAndPauseIfNeeded(strategyId = 85L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `F9 회귀 — 위험 rule 10건이면 제외 후에도 임계 도달 → 정지 발동`() {
        // 위험 rule(DAILY_LOSS_LIMIT 등)은 제외 set 에 없으므로 카운트에 그대로 남아 임계 도달.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 86L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 10

        val result = guard.checkAndPauseIfNeeded(strategyId = 86L, autoPauseThreshold = 10)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 86L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `F9 회귀 — 혼합(무해+위험) 제외 후 위험분만 임계 미달이면 정지 0`() {
        // 전체 BLOCKED 12건이라도 무해 가드 제외 후 위험분 7건 < threshold 10 → 정지 안 함.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 87L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 7

        val result = guard.checkAndPauseIfNeeded(strategyId = 87L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }
}
