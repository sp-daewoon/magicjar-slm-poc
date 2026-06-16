package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal

/**
 * 시장 레짐 룰 분류기 — spec `2026-06-16-strategy-daily-performance.md` §4-4 · ADR 0126 §3-4 정합.
 *
 * 지수 일봉의 변동성(rangePct)·등락률(changePct) 만으로 결정론적 분류한다.
 * **AI 호출 0** (ADR 0023·0126 정합 — 레짐은 룰 기반, 멱등·재현·백필 일관).
 *
 * 우선순위 적용 (spec §4-4):
 * 1. `rangePct ≥ highVolThresholdPct` → [MarketRegime.HIGH_VOL] (방향 무관, 변동성 우선)
 * 2. else `changePct ≥ +trendThresholdPct` → [MarketRegime.UPTREND]
 * 3. else `changePct ≤ −trendThresholdPct` → [MarketRegime.DOWNTREND]
 * 4. else → [MarketRegime.SIDEWAYS]
 *
 * 임계치 default 는 spec §4-4 LOCK 값(HIGH_VOL 2.5% · 추세 ±0.6%)이며,
 * 어댑터(api `application.yml` `magicjar.market-index.regime.*`)가 주입해 추후 튜닝 가능하다.
 *
 * 본 클래스는 순수 함수 — Spring·JPA·외부 I/O 의존 0.
 */
class MarketRegimeClassifier(
    private val highVolThresholdPct: BigDecimal = DEFAULT_HIGH_VOL_THRESHOLD_PCT,
    private val trendThresholdPct: BigDecimal = DEFAULT_TREND_THRESHOLD_PCT,
) {
    /**
     * 레짐 분류.
     *
     * @param changePct 당일 등락률 (%). null 이면 추세 판정 불가 → range 만으로 HIGH_VOL 여부 결정,
     *                  아니면 [MarketRegime.SIDEWAYS] (방향 미상 = 횡보로 둠).
     * @param rangePct  당일 변동폭 (%). null 이면 HIGH_VOL 판정 제외.
     * @return 우선순위 적용 레짐.
     */
    fun classify(
        changePct: BigDecimal?,
        rangePct: BigDecimal?,
    ): MarketRegime {
        if (rangePct != null && rangePct >= highVolThresholdPct) {
            return MarketRegime.HIGH_VOL
        }
        if (changePct == null) {
            return MarketRegime.SIDEWAYS
        }
        return when {
            changePct >= trendThresholdPct -> MarketRegime.UPTREND
            changePct <= trendThresholdPct.negate() -> MarketRegime.DOWNTREND
            else -> MarketRegime.SIDEWAYS
        }
    }

    companion object {
        /** spec §4-4 LOCK — `rangePct ≥ 2.5%` → HIGH_VOL. */
        val DEFAULT_HIGH_VOL_THRESHOLD_PCT: BigDecimal = BigDecimal("2.5")

        /** spec §4-4 LOCK — `|changePct| ≥ 0.6%` → UPTREND/DOWNTREND. */
        val DEFAULT_TREND_THRESHOLD_PCT: BigDecimal = BigDecimal("0.6")
    }
}
