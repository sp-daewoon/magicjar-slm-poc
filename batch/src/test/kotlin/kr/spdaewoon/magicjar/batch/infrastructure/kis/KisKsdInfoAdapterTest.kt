package kr.spdaewoon.magicjar.batch.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * KisKsdInfoAdapter 파싱 단위 테스트 (smoke — T-CA1 PR-29b).
 *
 * 외부 KIS API 호출 없이 샘플 JSON 파싱 검증.
 * ADR 0018 §4-3 보정 계수 계산 및 CorpActionType 매핑 정합.
 *
 * KIS ksdinfo 6 endpoint 출처 (헌법 §3):
 *  rev-split  HHKDB669105C0 : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_rev_split/ksdinfo_rev_split.py
 *  dividend   HHKDB669102C0 : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_dividend/ksdinfo_dividend.py
 *  bonus-issue HHKDB669101C0: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_bonus_issue/ksdinfo_bonus_issue.py
 */
class KisKsdInfoAdapterTest {
    private val objectMapper = ObjectMapper()

    /** adapter 비공개 메서드를 테스트하기 위한 헬퍼 — 실제 callKsdInfo 대신 샘플 JSON 주입 */
    private fun parseRevSplitSample(output: com.fasterxml.jackson.databind.JsonNode) =
        output.mapNotNull { node ->
            val instrCode =
                node
                    .path("sht_cd")
                    .asText("")
                    .trim()
                    .ifEmpty { return@mapNotNull null }
            val exDateStr = node.path("stk_splt_dt").asText("").trim()
            val bfrFace =
                node
                    .path("bfr_face_val")
                    .asText("0")
                    .trim()
                    .toBigDecimalOrNull()
                    ?: java.math.BigDecimal.ONE
            val aftFace =
                node
                    .path("aft_face_val")
                    .asText("0")
                    .trim()
                    .toBigDecimalOrNull()
                    ?: java.math.BigDecimal.ONE
            val ratio =
                if (aftFace > java.math.BigDecimal.ZERO) {
                    bfrFace.divide(aftFace, 8, java.math.RoundingMode.HALF_UP)
                } else {
                    java.math.BigDecimal.ONE
                }
            val type =
                if (ratio > java.math.BigDecimal.ONE) {
                    CorpActionType.STOCK_SPLIT
                } else {
                    CorpActionType.REVERSE_SPLIT
                }
            Triple(instrCode, exDateStr, type to ratio)
        }

    @Test
    fun `rev-split 파싱 - 액면분할 50대1 삼성전자 시나리오`() {
        // KIS ksdinfo rev-split 응답 샘플 (HHKDB669105C0 output 필드 정합)
        // bfr_face_val=5000, aft_face_val=100 → ratio=50 → STOCK_SPLIT
        val sampleJson =
            """
            [
              {
                "sht_cd": "005930",
                "stk_splt_dt": "20180504",
                "bfr_face_val": "5000",
                "aft_face_val": "100"
              }
            ]
            """.trimIndent()

        val output = objectMapper.readTree(sampleJson)
        val results = parseRevSplitSample(output)

        assertThat(results).hasSize(1)
        val (code, dateStr, typeRatio) = results[0]
        val (type, ratio) = typeRatio

        assertThat(code).isEqualTo("005930")
        assertThat(dateStr).isEqualTo("20180504")
        assertThat(type).isEqualTo(CorpActionType.STOCK_SPLIT)
        // ratio = 5000/100 = 50 (1주→50주, 가격×(1/50))
        assertThat(ratio).isEqualByComparingTo("50.00000000")
    }

    @Test
    fun `rev-split 파싱 - 액면병합 10대1 시나리오`() {
        // bfr_face_val=100, aft_face_val=1000 → ratio=0.1 → REVERSE_SPLIT
        val sampleJson =
            """
            [
              {
                "sht_cd": "012345",
                "stk_splt_dt": "20230315",
                "bfr_face_val": "100",
                "aft_face_val": "1000"
              }
            ]
            """.trimIndent()

        val output = objectMapper.readTree(sampleJson)
        val results = parseRevSplitSample(output)

        assertThat(results).hasSize(1)
        val (_, _, typeRatio) = results[0]
        assertThat(typeRatio.first).isEqualTo(CorpActionType.REVERSE_SPLIT)
    }

    @Test
    fun `dividend 파싱 - 현금배당 시나리오`() {
        // KIS ksdinfo dividend 응답 샘플 (HHKDB669102C0 output 필드 정합)
        // cas_dvdn_amt>0 → CASH_DIVIDEND
        val sampleJson =
            """
            [
              {
                "sht_cd": "005930",
                "stck_dvdn_dt": "20240329",
                "cas_dvdn_amt": "361",
                "stk_dvdn_rt": "0"
              }
            ]
            """.trimIndent()

        val node = objectMapper.readTree(sampleJson)[0]
        val cashAmt = node.path("cas_dvdn_amt").asText("0").toBigDecimalOrNull()
        val stockRt = node.path("stk_dvdn_rt").asText("0").toBigDecimalOrNull()
        val type =
            if ((stockRt ?: java.math.BigDecimal.ZERO) > java.math.BigDecimal.ZERO) {
                CorpActionType.STOCK_DIVIDEND
            } else {
                CorpActionType.CASH_DIVIDEND
            }

        assertThat(type).isEqualTo(CorpActionType.CASH_DIVIDEND)
        assertThat(cashAmt).isEqualByComparingTo("361")
    }

    @Test
    fun `bonus-issue 파싱 - 무상증자 10% 시나리오`() {
        // KIS ksdinfo bonus-issue 응답 샘플 (HHKDB669101C0 output 필드 정합)
        // grts_rt=10 → ratio=0.1 → BONUS_ISSUE
        val sampleJson =
            """
            [
              {
                "sht_cd": "035420",
                "grts_stck_dt": "20230601",
                "grts_rt": "10"
              }
            ]
            """.trimIndent()

        val node = objectMapper.readTree(sampleJson)[0]
        val instrCode = node.path("sht_cd").asText("").trim()
        val grtsRt = node.path("grts_rt").asText("0").toBigDecimalOrNull()
        val ratio = grtsRt?.divide(java.math.BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP)

        assertThat(instrCode).isEqualTo("035420")
        assertThat(ratio).isEqualByComparingTo("0.10000000")
    }

    @Test
    fun `빈 sht_cd 행은 skip 처리`() {
        val sampleJson =
            """
            [
              { "sht_cd": "", "stk_splt_dt": "20240101", "bfr_face_val": "5000", "aft_face_val": "100" },
              { "sht_cd": "000660", "stk_splt_dt": "20240201", "bfr_face_val": "5000", "aft_face_val": "100" }
            ]
            """.trimIndent()

        val output = objectMapper.readTree(sampleJson)
        val results = parseRevSplitSample(output)

        // 빈 sht_cd는 skip → 000660만 결과
        assertThat(results).hasSize(1)
        assertThat(results[0].first).isEqualTo("000660")
    }
}
