package kr.spdaewoon.magicjar.domain.indicator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * IndicatorSpec 파라미터 추출 헬퍼 검증.
 *
 * stringParam — order_block `detection`/`range` 같은 enum-like 문자열 파라미터 추출.
 */
class IndicatorSpecTest {
    @Test
    fun `stringParam returns stored string value`() {
        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing"))
        spec.stringParam("detection", "bos") shouldBe "engulfing"
    }

    @Test
    fun `stringParam returns default when key absent`() {
        val spec = IndicatorSpec("order_block", mapOf("swingLookback" to 20))
        spec.stringParam("detection", "bos") shouldBe "bos"
        spec.stringParam("range", "wick") shouldBe "wick"
    }

    @Test
    fun `stringParam returns default when value blank`() {
        val spec = IndicatorSpec("order_block", mapOf("detection" to "   "))
        spec.stringParam("detection", "bos") shouldBe "bos"
    }

    @Test
    fun `stringParam coerces non-string value via toString`() {
        val spec = IndicatorSpec("order_block", mapOf("detection" to 42))
        spec.stringParam("detection", "bos") shouldBe "42"
    }

    @Test
    fun `paramsHash stable across equal string params`() {
        val a = IndicatorSpec("order_block", mapOf("detection" to "engulfing", "range" to "body"))
        val b = IndicatorSpec("order_block", mapOf("range" to "body", "detection" to "engulfing"))
        a.paramsHash() shouldBe b.paramsHash()
    }
}
