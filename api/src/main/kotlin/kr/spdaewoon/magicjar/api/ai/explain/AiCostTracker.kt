package kr.spdaewoon.magicjar.api.ai.explain

import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * AI 월 비용 추적기 (ADR 0026 §2-5 amend · ADR 0095 amend S-AI-1).
 *
 * 책임:
 * - 월 누적 비용 조회 (`monthlySpend()`)
 * - provider 별 cost 계산 (`computeCost(provider, model, tokensIn, tokensOut)`)
 * - $10/월 초과 임계 검사 (`isOverThreshold()`) — ADR 0095 amend S-AI-1 Slack CRITICAL alert source
 *
 * 비용 표 (1M token 단위, 2026-05 기준 — provider 공식 가격 페이지 정합):
 * - anthropic:claude-3-5-haiku  → input $0.80 / output $4.00
 * - anthropic:claude-3-5-sonnet → input $3.00 / output $15.00
 * - gemini:gemini-1.5-flash     → input $0.075 / output $0.30 (free tier 한도 내 무료)
 * - openai:gpt-4o-mini          → input $0.15 / output $0.60
 * - mock:*                      → $0 (LLM 호출 0건)
 *
 * 모델 미등록 시 보수적 fallback ($1 / $5 — 향후 amend 까지 안전 buffer).
 */
@Component
class AiCostTracker(
    private val repository: AiCommentLogJpaRepository,
) {
    /** 월 알림 임계 — ADR 0095 amend S-AI-1 정합. */
    val monthlyThresholdUsd: BigDecimal = BigDecimal("10.00")

    /**
     * 현재 월 누적 비용 (UTC 기준 — Asia/Seoul 보다 9h 빠르므로 월말 9h 윈도 차이 허용).
     *
     * 사용 인덱스: `idx_ai_comment_log_provider_created` (V49) — 월 윈도 ~30일 fully indexed.
     */
    fun monthlySpend(): BigDecimal {
        val firstOfMonth =
            Instant
                .now()
                .atZone(ZoneOffset.UTC)
                .with(TemporalAdjusters.firstDayOfMonth())
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
        return repository.sumCostUsdSince(firstOfMonth)
    }

    /** $10/월 초과 여부 — alert dispatcher 가 호출 (ADR 0095 amend S-AI-1). */
    fun isOverThreshold(): Boolean = monthlySpend() >= monthlyThresholdUsd

    /**
     * 호출 비용 계산 (provider · model 기반).
     *
     * @param provider "anthropic" / "gemini" / "openai" / "mock"
     * @param model "claude-3-5-haiku-20241022" 등 (matching 은 prefix 기반 — model version suffix 무시)
     */
    fun computeCost(
        provider: String,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
    ): BigDecimal {
        if (provider.equals("mock", ignoreCase = true)) return BigDecimal.ZERO
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

        // (input per 1M, output per 1M) — USD
        val ANTHROPIC_HAIKU: Pair<BigDecimal, BigDecimal> = BigDecimal("0.80") to BigDecimal("4.00")
        val ANTHROPIC_SONNET: Pair<BigDecimal, BigDecimal> = BigDecimal("3.00") to BigDecimal("15.00")
        val GEMINI_FLASH: Pair<BigDecimal, BigDecimal> = BigDecimal("0.075") to BigDecimal("0.30")
        val OPENAI_4O_MINI: Pair<BigDecimal, BigDecimal> = BigDecimal("0.15") to BigDecimal("0.60")

        /** 미등록 모델 — 보수적 fallback (실제 비용보다 과대 추정해 임계 보호). */
        val FALLBACK: Pair<BigDecimal, BigDecimal> = BigDecimal("1.00") to BigDecimal("5.00")
    }
}
