package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * AiCostTracker — provider 별 cost 계산 + 임계 검사 (ADR 0026 §2-5 amend · ADR 0095 amend S-AI-1).
 */
class AiCostTrackerTest {
    private val tracker = AiCostTracker(mockk(relaxed = true))

    @Test
    fun `mock provider 는 항상 cost 0`() {
        tracker.computeCost("mock", "mock-1.0", 1000, 2000) shouldBe BigDecimal.ZERO
    }

    @Test
    fun `anthropic haiku 비용 계산 - 1000 input 1000 output = 0_0048 USD`() {
        // 1000 * 0.80/1M + 1000 * 4.00/1M = 0.0008 + 0.0040 = 0.0048
        val cost = tracker.computeCost("anthropic", "claude-3-5-haiku-20241022", 1000, 1000)
        cost shouldBe BigDecimal("0.004800")
    }

    @Test
    fun `gemini flash 비용 계산 - 1M input 1M output = 0_375 USD`() {
        val cost = tracker.computeCost("gemini", "gemini-1.5-flash", 1_000_000, 1_000_000)
        cost shouldBe BigDecimal("0.375000")
    }

    @Test
    fun `openai gpt-4o-mini 비용 계산`() {
        // 1000 * 0.15/1M + 1000 * 0.60/1M = 0.00015 + 0.00060 = 0.00075
        val cost = tracker.computeCost("openai", "gpt-4o-mini", 1000, 1000)
        cost shouldBe BigDecimal("0.000750")
    }

    @Test
    fun `미등록 모델 - 보수적 fallback (input 1usd output 5usd per 1M)`() {
        val cost = tracker.computeCost("unknown", "unknown-model", 1000, 1000)
        // 1000 * 1.00/1M + 1000 * 5.00/1M = 0.001 + 0.005 = 0.006
        cost shouldBe BigDecimal("0.006000")
    }

    @Test
    fun `보수적 fallback 은 실제 anthropic haiku 보다 과대 추정 - 임계 보호`() {
        val haiku = tracker.computeCost("anthropic", "claude-3-5-haiku", 1000, 1000)
        val fallback = tracker.computeCost("unknown", "future-model", 1000, 1000)
        fallback shouldBeGreaterThan haiku
    }

    @Test
    fun `monthly threshold $10 LOCK (ADR 0095 amend S-AI-1)`() {
        tracker.monthlyThresholdUsd shouldBe BigDecimal("10.00")
    }
}
