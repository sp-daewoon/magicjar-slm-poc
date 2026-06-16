package kr.spdaewoon.magicjar.domain.ai

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * AiCommentType — ADR 0026 §2-2 amend 5 진입점 + V50 seed type 매핑 검증.
 */
class AiCommentTypeTest {
    @Test
    fun `5 진입점 enum LOCK 검증`() {
        AiCommentType.entries.map { it.name }.shouldContainExactlyInAnyOrder(
            "SYMBOL",
            "CHART",
            "STRATEGY",
            "SIGNAL",
            "MARKET",
        )
    }

    @Test
    fun `toTemplateKey - V50 seed 행 type 컬럼 1대1 매핑`() {
        AiCommentType.SYMBOL.toTemplateKey() shouldBe "symbol"
        AiCommentType.CHART.toTemplateKey() shouldBe "chart"
        AiCommentType.STRATEGY.toTemplateKey() shouldBe "strategy"
        AiCommentType.SIGNAL.toTemplateKey() shouldBe "signal"
        AiCommentType.MARKET.toTemplateKey() shouldBe "market"
    }
}
