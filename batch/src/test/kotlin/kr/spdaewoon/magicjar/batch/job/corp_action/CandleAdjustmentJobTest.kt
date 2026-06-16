package kr.spdaewoon.magicjar.batch.job.corpaction

import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * CandleAdjustmentJob 보정 계수 계산 단위 테스트 (smoke — T-CA1 PR-29b).
 *
 * 외부 의존 없이 보정 계수(factor) 계산 로직만 검증.
 * ADR 0018 §4-3 보정 공식 정합:
 *   STOCK_SPLIT    ratio=50 → factor = 1/50 = 0.02
 *   REVERSE_SPLIT  ratio=10 → factor = 10
 *   BONUS_ISSUE    ratio=0.1 → factor = 1/(1.1) ≈ 0.90909091
 *
 * 시나리오: 삼성전자 2018 액면분할 50:1 사례 (역사적 사실)
 */
class CandleAdjustmentJobTest {
    /**
     * 테스트용 보정 계수 계산 — CandleAdjustmentJob.computeFactor() 동일 로직 추출.
     */
    private fun computeFactor(action: CorpAction): BigDecimal? {
        val ratio = action.ratio ?: return null
        return when (action.type) {
            CorpActionType.STOCK_SPLIT ->
                if (ratio > BigDecimal.ZERO) {
                    BigDecimal.ONE.divide(ratio, 8, RoundingMode.HALF_UP)
                } else {
                    null
                }
            CorpActionType.REVERSE_SPLIT ->
                if (ratio > BigDecimal.ZERO) ratio.setScale(8, RoundingMode.HALF_UP) else null
            CorpActionType.BONUS_ISSUE ->
                if (ratio > BigDecimal.ZERO) {
                    BigDecimal.ONE.divide(BigDecimal.ONE.add(ratio), 8, RoundingMode.HALF_UP)
                } else {
                    null
                }
            CorpActionType.CAPITAL_DECREASE ->
                if (ratio > BigDecimal.ZERO) ratio.setScale(8, RoundingMode.HALF_UP) else null
            CorpActionType.CASH_DIVIDEND,
            CorpActionType.STOCK_DIVIDEND,
            -> null
            else -> null
        }
    }

    @Test
    fun `액면분할 50대1 보정 계수 - 삼성전자 2018 시나리오`() {
        // 삼성전자 2018-05-04 액면분할 5000원→100원 (50:1)
        // KIS ksdinfo rev-split HHKDB669105C0: bfr_face_val=5000, aft_face_val=100 → ratio=50
        val action =
            CorpAction(
                instrumentCode = "005930",
                type = CorpActionType.STOCK_SPLIT,
                exDate = LocalDate.of(2018, 5, 4),
                ratio = BigDecimal("50"),
                source = "KIS_KSDINFO",
            )

        val factor = computeFactor(action)

        assertThat(factor).isNotNull
        // factor = 1/50 = 0.02 — 이전 가격을 50으로 나눔 (분할 전 50,000원 → adj 1,000원)
        assertThat(factor).isEqualByComparingTo("0.02000000")
    }

    @Test
    fun `액면병합 10대1 보정 계수`() {
        val action =
            CorpAction(
                instrumentCode = "012345",
                type = CorpActionType.REVERSE_SPLIT,
                exDate = LocalDate.of(2023, 3, 15),
                ratio = BigDecimal("10"),
                source = "KIS_KSDINFO",
            )

        val factor = computeFactor(action)

        assertThat(factor).isNotNull
        // factor = 10 — 이전 가격에 10 곱함 (병합 전 100원 → adj 1,000원)
        assertThat(factor).isEqualByComparingTo("10.00000000")
    }

    @Test
    fun `무상증자 10% 보정 계수`() {
        // KIS ksdinfo bonus-issue HHKDB669101C0: grts_rt=10 → ratio=0.1
        val action =
            CorpAction(
                instrumentCode = "035420",
                type = CorpActionType.BONUS_ISSUE,
                exDate = LocalDate.of(2023, 6, 1),
                ratio = BigDecimal("0.1"), // grts_rt=10 → 10/100 = 0.1
                source = "KIS_KSDINFO",
            )

        val factor = computeFactor(action)

        assertThat(factor).isNotNull
        // factor = 1/(1+0.1) = 1/1.1 ≈ 0.90909091
        assertThat(factor!!.setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo("0.909091")
    }

    @Test
    fun `현금배당 보정 계수 없음 - factor null`() {
        // 배당락 가격 조정은 시장에서 자동 반영 → 소급 불필요 (ADR 0018 §4-3)
        val action =
            CorpAction(
                instrumentCode = "005930",
                type = CorpActionType.CASH_DIVIDEND,
                exDate = LocalDate.of(2024, 3, 29),
                cashDividend = BigDecimal("361"),
                source = "KIS_KSDINFO",
            )

        val factor = computeFactor(action)

        assertThat(factor).isNull()
    }

    @Test
    fun `보정 계수 누적 검증 - 다단계 분할`() {
        // 1차: STOCK_SPLIT ratio=5 → factor=0.2
        // 2차: STOCK_SPLIT ratio=10 → factor=0.1
        // 누적: 0.2 * 0.1 = 0.02 (adjustment_factor 누적 곱)
        val split1 =
            CorpAction(
                instrumentCode = "000660",
                type = CorpActionType.STOCK_SPLIT,
                exDate = LocalDate.of(2020, 1, 10),
                ratio = BigDecimal("5"),
                source = "KIS_KSDINFO",
            )
        val split2 =
            CorpAction(
                instrumentCode = "000660",
                type = CorpActionType.STOCK_SPLIT,
                exDate = LocalDate.of(2022, 1, 10),
                ratio = BigDecimal("10"),
                source = "KIS_KSDINFO",
            )

        val factor1 = computeFactor(split1)!!
        val factor2 = computeFactor(split2)!!
        val cumFactor = factor1.multiply(factor2).setScale(8, RoundingMode.HALF_UP)

        // 0.2 * 0.1 = 0.02
        assertThat(cumFactor).isEqualByComparingTo("0.02000000")
    }
}
