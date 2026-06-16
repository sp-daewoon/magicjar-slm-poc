package kr.spdaewoon.magicjar.domain.notification.port

/**
 * Slack webhook outbound 포트 (ADR 0044 + spec §2-4 LOCK).
 *
 * 구현: consumer 모듈 SlackWebhookSender (JDK HttpClient + Resilience4j retry).
 * 헌법 §6 (ADR 0051) — abstract fun만.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
interface SlackWebhookPort {
    /**
     * Slack Incoming Webhook POST.
     *
     * @param webhookUrl system_config.notification_slack_webhook 복호화 값
     * @param message    Slack 전송 메시지 (JSON blocks or text)
     * @return SlackPostResult (Success or Failure)
     */
    fun post(
        webhookUrl: String,
        message: String,
    ): SlackPostResult
}

/** Slack POST 결과 sealed class */
sealed class SlackPostResult {
    data class Success(
        val statusCode: Int,
    ) : SlackPostResult()

    data class Failure(
        val statusCode: Int?,
        val error: String,
        val retryable: Boolean,
    ) : SlackPostResult()
}
