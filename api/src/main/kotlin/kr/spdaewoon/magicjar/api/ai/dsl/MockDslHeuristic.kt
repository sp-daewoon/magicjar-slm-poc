package kr.spdaewoon.magicjar.api.ai.dsl

/**
 * Mock 모드 자연어 → DSL 룰 기반 변환기.
 *
 * `MAGICJAR_AI_MOCK_MODE=mock` 또는 `auto + Anthropic key 없음`일 때 활성화. 룰 기반이라 LLM 호출 0건 — 룰 5 정합.
 * 사용자 fitness gap: mock은 `_workspace/feedback_no_mock_data.md` "사용자 흐름에서 mock 합성 폐기" 정합 검토 →
 *   mock LLM 응답은 단위 테스트 + key 없는 개발 환경의 graceful fallback이므로 OK (실시장 데이터 합성과 다름).
 *
 * 매칭되지 않으면 `null` 반환 — 호출자가 untranslatable 처리.
 *
 * Few-shot 카탈로그 (prompt template과 동일 — `dsl-translate.md`):
 *  - 골든크로스, RSI 과매도, 거래량 돌파, 신고가, MACD, 복합조건, BB 하단, 변동성 손절, 백분율
 */
object MockDslHeuristic {
    /** 한국어 자연어를 정규화: 소문자화 + 공백 정리 + 흔한 한자/이형 표기 통일. */
    private fun normalize(input: String): String =
        input
            .trim()
            .lowercase()
            .replace("이동평균선", "이동평균")
            .replace("이평선", "이동평균")
            .replace("이평", "이동평균")
            .replace("\\s+".toRegex(), " ")

    private val rsiOversoldRegex =
        Regex("rsi\\s*(?:\\(?\\s*(\\d{1,3})\\s*\\)?)?\\s*(?:이|가)?\\s*(\\d{1,3})\\s*(?:이하|미만|아래|밑)")
    private val rsiOverboughtRegex =
        Regex("rsi\\s*(?:\\(?\\s*(\\d{1,3})\\s*\\)?)?\\s*(?:이|가)?\\s*(\\d{1,3})\\s*(?:이상|초과|위)")
    private val crossoverRegex =
        Regex(
            "(\\d{1,3})\\s*일\\s*(?:이동평균|선)?\\s*(?:이|가)?\\s*(\\d{1,3})\\s*일\\s*(?:이동평균|선)?\\s*(?:을|를)?\\s*(상향\\s*돌파|골든\\s*크로스)",
        )
    private val crossunderRegex =
        Regex(
            "(\\d{1,3})\\s*일\\s*(?:이동평균|선)?\\s*(?:이|가)?\\s*(\\d{1,3})\\s*일\\s*(?:이동평균|선)?\\s*(?:을|를)?\\s*(하향\\s*돌파|데드\\s*크로스)",
        )
    private val volumeMultiplierRegex =
        Regex("거래량\\s*(?:이|가)?\\s*(\\d{1,3})\\s*일\\s*(?:평균|이동평균)\\s*(?:의)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*배")
    private val highBreakoutRegex =
        Regex("(\\d{1,4})\\s*일\\s*(?:최고가|신고가)\\s*(?:을|를)?\\s*(돌파|breaks?)")
    private val percentReturnRegex =
        Regex("(\\d{1,3})\\s*일\\s*(?:수익률|등락률)\\s*(?:이|가)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*(이상|초과|넘|위)")
    private val bbLowerRegex =
        Regex("볼린저\\s*(?:밴드)?\\s*하단(?:.*?(\\d+).*?(\\d+))?")
    private val macdGoldenRegex = Regex("macd.*시그널.*(상향|골든|crosses_above)")

    fun translate(naturalLanguage: String): String? {
        val n = normalize(naturalLanguage)

        rsiOversoldRegex.find(n)?.let { m ->
            val period = m.groupValues[1].ifBlank { "14" }
            val threshold = m.groupValues[2]
            return "rsi($period) < $threshold"
        }
        rsiOverboughtRegex.find(n)?.let { m ->
            val period = m.groupValues[1].ifBlank { "14" }
            val threshold = m.groupValues[2]
            return "rsi($period) > $threshold"
        }
        crossoverRegex.find(n)?.let { m ->
            val fast = m.groupValues[1]
            val slow = m.groupValues[2]
            val base = "ma($fast) crosses_above ma($slow)"
            volumeMultiplierRegex.find(n)?.let { v ->
                val volPeriod = v.groupValues[1]
                val multiplier = v.groupValues[2]
                return "$base AND volume > volume_ma($volPeriod) * $multiplier"
            }
            return base
        }
        crossunderRegex.find(n)?.let { m ->
            return "ma(${m.groupValues[1]}) crosses_below ma(${m.groupValues[2]})"
        }
        highBreakoutRegex.find(n)?.let { m ->
            return "close breaks high(${m.groupValues[1]})"
        }
        percentReturnRegex.find(n)?.let { m ->
            return "returns(${m.groupValues[1]}) > ${m.groupValues[2]}%"
        }
        bbLowerRegex.find(n)?.let { m ->
            val period = m.groupValues[1].ifBlank { "20" }
            val std = m.groupValues[2].ifBlank { "2" }
            return "close < bb_lower($period, $std)"
        }
        if (macdGoldenRegex.containsMatchIn(n)) {
            return "macd(12,26,9) crosses_above macd_signal(12,26,9)"
        }
        return null
    }
}
