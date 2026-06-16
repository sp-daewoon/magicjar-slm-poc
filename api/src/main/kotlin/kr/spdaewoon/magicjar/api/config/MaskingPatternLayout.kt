package kr.spdaewoon.magicjar.api.config

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그 마스킹 — KIS APP_KEY·SECRET_KEY·access_token·webhook_url 평문 노출 방지 (ADR 0046 §2-5, spec §6-8).
 *
 * 정규식 패턴:
 * 1) (app_key|app_secret|secret_key|webhook_url|access_token)["':\s=]+[\w\-./]{8,} → [REDACTED]
 * 2) KIS 계좌번호 8자리 (\b\d{8}\b) → 앞 4자리만 표시 (예: 74683223 → 7468****)
 */
class MaskingPatternLayout : PatternLayout() {
    private val sensitivePattern =
        Regex(
            """(?i)(app_key|app_secret|secret_key|webhook_url|access_token)["':\s=]+[\w\-./]{8,}""",
        )
    private val accountNumberPattern = Regex("""\b(\d{4})\d{4}\b""")

    override fun doLayout(event: ILoggingEvent): String {
        val original = super.doLayout(event)
        return original
            .replace(sensitivePattern) { matchResult ->
                val key = matchResult.groupValues[1]
                "$key=[REDACTED]"
            }.replace(accountNumberPattern) { matchResult ->
                "${matchResult.groupValues[1]}****"
            }
    }
}
