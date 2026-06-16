package kr.spdaewoon.magicjar.domain.performance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [MarketRegimeClassifier] 레짐 4분류 경계값 테스트 — spec §4-4 우선순위 LOCK.
 *
 * 임계치 default: HIGH_VOL 2.5% · 추세 ±0.6%.
 */
class MarketRegimeClassifierTest {
    private val classifier = MarketRegimeClassifier()

    private fun pct(v: String) = BigDecimal(v)

    @Test
    fun `range_pct 2_5 이상이면 방향 무관 HIGH_VOL (변동성 우선)`() {
        // 경계값 정확히 2.5% → HIGH_VOL (≥)
        classifier.classify(changePct = pct("0.0"), rangePct = pct("2.5")) shouldBe MarketRegime.HIGH_VOL
        // 강한 상승이어도 변동성 우선 → HIGH_VOL
        classifier.classify(changePct = pct("3.0"), rangePct = pct("4.0")) shouldBe MarketRegime.HIGH_VOL
        // 강한 하락이어도 변동성 우선 → HIGH_VOL
        classifier.classify(changePct = pct("-3.0"), rangePct = pct("5.0")) shouldBe MarketRegime.HIGH_VOL
    }

    @Test
    fun `range_pct 2_5 미만 + change_pct 0_6 이상이면 UPTREND`() {
        // 경계값 정확히 +0.6% → UPTREND (≥)
        classifier.classify(changePct = pct("0.6"), rangePct = pct("1.0")) shouldBe MarketRegime.UPTREND
        classifier.classify(changePct = pct("1.5"), rangePct = pct("2.4")) shouldBe MarketRegime.UPTREND
    }

    @Test
    fun `range_pct 2_5 미만 + change_pct 마이너스 0_6 이하면 DOWNTREND`() {
        // 경계값 정확히 −0.6% → DOWNTREND (≤)
        classifier.classify(changePct = pct("-0.6"), rangePct = pct("1.0")) shouldBe MarketRegime.DOWNTREND
        classifier.classify(changePct = pct("-1.8"), rangePct = pct("2.4")) shouldBe MarketRegime.DOWNTREND
    }

    @Test
    fun `change_pct 절대값 0_6 미만이면 SIDEWAYS`() {
        // +0.59% → 추세 미달 → SIDEWAYS
        classifier.classify(changePct = pct("0.59"), rangePct = pct("1.0")) shouldBe MarketRegime.SIDEWAYS
        classifier.classify(changePct = pct("0.0"), rangePct = pct("0.5")) shouldBe MarketRegime.SIDEWAYS
        // −0.59% → 추세 미달 → SIDEWAYS
        classifier.classify(changePct = pct("-0.59"), rangePct = pct("1.0")) shouldBe MarketRegime.SIDEWAYS
    }

    @Test
    fun `change_pct null 이면 range 만으로 판정 - HIGH_VOL 아니면 SIDEWAYS`() {
        classifier.classify(changePct = null, rangePct = pct("3.0")) shouldBe MarketRegime.HIGH_VOL
        classifier.classify(changePct = null, rangePct = pct("1.0")) shouldBe MarketRegime.SIDEWAYS
        classifier.classify(changePct = null, rangePct = null) shouldBe MarketRegime.SIDEWAYS
    }

    @Test
    fun `range_pct null 이면 HIGH_VOL 판정 제외 후 추세 판정`() {
        classifier.classify(changePct = pct("0.8"), rangePct = null) shouldBe MarketRegime.UPTREND
        classifier.classify(changePct = pct("-0.8"), rangePct = null) shouldBe MarketRegime.DOWNTREND
        classifier.classify(changePct = pct("0.1"), rangePct = null) shouldBe MarketRegime.SIDEWAYS
    }

    @Test
    fun `임계치 주입으로 튜닝 가능 (application_yml 노출 정합)`() {
        val tuned =
            MarketRegimeClassifier(
                highVolThresholdPct = BigDecimal("5.0"),
                trendThresholdPct = BigDecimal("1.0"),
            )
        // default 라면 HIGH_VOL 이지만 tuned(5.0) 기준 미달 → 추세 판정 (change 3.0 ≥ 1.0 → UPTREND)
        tuned.classify(changePct = pct("3.0"), rangePct = pct("3.0")) shouldBe MarketRegime.UPTREND
        // default 라면 UPTREND(0.6) 이지만 tuned(1.0) 미달 → SIDEWAYS
        tuned.classify(changePct = pct("0.7"), rangePct = pct("1.0")) shouldBe MarketRegime.SIDEWAYS
    }
}
