package kr.spdaewoon.magicjar.consumer.ai

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Consumer-side AI 비용 계산기 (api 모듈 `AiCostTracker` 와 동일 cost 표 LOCK).
 *
 * 책임:
 * - provider · model · tokens 입력 → USD cost 계산
 * - ai.comment.responded.v1 payload 의 `costUsd` 필드 합성 (api 측 audit listener 가 그대로 INSERT)
 *
 * 비용 표 (api `AiCostTracker` §companion 정합):
 * - anthropic:claude-3-5-haiku  → input $0.80 / output $4.00
 * - anthropic:claude-3-5-sonnet → input $3.00 / output $15.00
 * - gemini:gemini-1.5-flash     → input $0.075 / output $0.30 (free tier 한도 내 무료)
 * - openai:gpt-4o-mini          → input $0.15 / output $0.60
 * - mock:*                      → $0
 * - claude-cli:*                → $0 (구독 기반 Claude CLI/bridge — 별도 토큰 과금 0, ADR 0112 정합)
 */
@Component
class AiCostCalculator {
    fun compute(
        provider: String,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
    ): BigDecimal {
        if (provider.equals("mock", ignoreCase = true)) return BigDecimal.ZERO
        // claude-cli (구독 기반): 토큰 카운트 미제공 + 별도 과금 0 → 항상 $0 정직 기록.
        if (provider.equals("claude-cli", ignoreCase = true)) return BigDecimal.ZERO
        val (inputPerMillion, outputPerMillion) = lookupRate(provider, model)
        val inputCost = BigDecimal(tokensIn).multiply(inputPerMillion).divide(MILLION, 6, RoundingMode.HALF_UP)
        val outputCost = BigDecimal(tokensOut).multiply(outputPerMillion).divide(MILLION, 6, RoundingMode.HALF_UP)
        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP)
    }

    private fun lookupRate(
        provider: String,
        model: String,
    ): Pair<BigDecimal, BigDecimal> {
        val p = provider.lowercase()
        val m = model.lowercase()
        return when {
            p == "anthropic" && m.contains("haiku") -> ANTHROPIC_HAIKU
            p == "anthropic" && m.contains("sonnet") -> ANTHROPIC_SONNET
            p == "gemini" && m.contains("flash") -> GEMINI_FLASH
            p == "openai" && m.contains("4o-mini") -> OPENAI_4O_MINI
            else -> FALLBACK
        }
    }

    private companion object {
        val MILLION: BigDecimal = BigDecimal("1000000")
        val ANTHROPIC_HAIKU: Pair<BigDecimal, BigDecimal> = BigDecimal("0.80") to BigDecimal("4.00")
        val ANTHROPIC_SONNET: Pair<BigDecimal, BigDecimal> = BigDecimal("3.00") to BigDecimal("15.00")
        val GEMINI_FLASH: Pair<BigDecimal, BigDecimal> = BigDecimal("0.075") to BigDecimal("0.30")
        val OPENAI_4O_MINI: Pair<BigDecimal, BigDecimal> = BigDecimal("0.15") to BigDecimal("0.60")
        val FALLBACK: Pair<BigDecimal, BigDecimal> = BigDecimal("1.00") to BigDecimal("5.00")
    }
}
