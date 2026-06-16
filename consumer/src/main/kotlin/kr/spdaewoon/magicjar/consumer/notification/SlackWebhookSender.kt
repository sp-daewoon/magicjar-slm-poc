package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.port.SlackPostResult
import kr.spdaewoon.magicjar.domain.notification.port.SlackWebhookPort
import kr.spdaewoon.magicjar.domain.notification.port.SystemConfigSlackPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Slack Incoming Webhook HTTP POST 어댑터 (ADR 0044 + spec §2-4 LOCK).
 *
 * ### JDK HttpClient 선택 사유
 * DART SSL handshake 결함(hotfix) 정합 — Reactor Netty WebClient의 cipher 제한 risk 회피.
 * JDK 21 native TLS 직접 사용.
 *
 * ### Resilience4j-없이 직접 retry (ADR 0070 §2-4 spec §11-3 정합)
 * Resilience4j 의존성 없이 단순 for-loop retry 3회(0→1s→2s 백오프).
 * 이유: Resilience4j RetryRegistry 빈 충돌 회피 (T-O baseline과 분리).
 *
 * ### rate limit 정합 (ADR 0070 §2-4)
 * Slack tier 한도: 1초 5건. P0 단건 → 초과 없음.
 * P1/P2 retry worker 5분 주기 → 초당 1건 이하.
 *
 * ### Slack webhook URL 미설정 시 graceful
 * system_config.notification_slack_webhook IS NULL → 전송 skip + log.warn.
 * outbox는 PENDING 유지 → retry worker가 URL 설정 후 재시도.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class SlackWebhookSender(
    private val systemConfigSlackPortProvider: ObjectProvider<SystemConfigSlackPort>,
    private val objectMapper: ObjectMapper,
) : SlackWebhookPort {
    private val log = LoggerFactory.getLogger(SlackWebhookSender::class.java)

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val READ_TIMEOUT_SEC = 10L
        private const val MAX_RETRY = 3
        private val BACKOFF_MS = listOf(0L, 1000L, 2000L)
    }

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build()

    /**
     * SlackWebhookPort 구현 — 직접 URL 지정 발송.
     */
    override fun post(
        webhookUrl: String,
        message: String,
    ): SlackPostResult {
        val body = objectMapper.writeValueAsString(mapOf("text" to message))
        return postWithRetry(webhookUrl, body)
    }

    /**
     * outbox row 기반 발송 (NotificationDispatcher P0 즉시 발송 용).
     *
     * @return true = 200 OK, false = 실패 (outbox PENDING 유지)
     */
    fun sendOutbox(outbox: NotificationOutbox): Boolean {
        val webhookUrl = resolveWebhookUrl() ?: return false

        // payload에서 메시지 추출
        val message = buildMessageFromPayload(outbox.payloadJson)
        val result = post(webhookUrl, message)

        return when (result) {
            is SlackPostResult.Success -> {
                log.info("Slack 발송 성공: outboxId={} statusCode={}", outbox.id, result.statusCode)
                true
            }
            is SlackPostResult.Failure -> {
                log.warn(
                    "Slack 발송 실패: outboxId={} statusCode={} err={}",
                    outbox.id,
                    result.statusCode,
                    result.error,
                )
                false
            }
        }
    }

    /**
     * direct 발송 (outbox 없이 직접 text 전송 — graceful fallback용).
     */
    fun sendDirect(message: String): Boolean {
        val webhookUrl = resolveWebhookUrl() ?: return false
        return when (val result = post(webhookUrl, message)) {
            is SlackPostResult.Success -> true
            is SlackPostResult.Failure -> {
                log.warn("Slack direct 발송 실패: err={}", result.error)
                false
            }
        }
    }

    /**
     * 주문 체결 알림 전용 추가 Slack 채널 이중 송출 (2026-06-10 최고 관리자 지시 · V79).
     *
     * 진보 페르소나 (ADR 0087):
     *   - ✨ 기존 채널 발송과 **완전 격리** — 본 메서드 실패가 outbox/기존 채널에 절대 영향 주지 않음(caller가 runCatching wrap).
     *   - 💡 새 키(notification_slack_webhook_order) 미설정 시 null → silent skip (false 반환, 기존 흐름 영향 0).
     *
     * @param message 기존 채널과 동일한 본문 (NotificationFormatter 결과)
     * @return true = 추가 채널 발송 성공, false = 미설정/실패 (기존 흐름에 영향 없음)
     */
    fun sendToOrderChannel(message: String): Boolean {
        val webhookUrl =
            resolveOrderWebhookUrl() ?: run {
                log.debug("주문 추가 Slack 채널 미설정 — 이중 송출 skip (기존 채널 영향 0)")
                return false
            }
        return when (val result = post(webhookUrl, message)) {
            is SlackPostResult.Success -> {
                log.info("주문 추가 Slack 채널 발송 성공: statusCode={}", result.statusCode)
                true
            }
            is SlackPostResult.Failure -> {
                log.warn("주문 추가 Slack 채널 발송 실패 (기존 채널 영향 0): err={}", result.error)
                false
            }
        }
    }

    // --- private ---

    private fun resolveWebhookUrl(): String? = resolveWebhook(order = false)

    private fun resolveOrderWebhookUrl(): String? = resolveWebhook(order = true)

    /**
     * webhook URL resolve 공통 helper (DRY · 진보 페르소나 ADR 0087).
     * order=true → 주문 추가 채널(notification_slack_webhook_order), false → 기본 채널.
     */
    private fun resolveWebhook(order: Boolean): String? {
        val port = systemConfigSlackPortProvider.getIfAvailable()
        if (port == null) {
            log.warn("SystemConfigSlackPort 미등록 — Slack 발송 skip (order={})", order)
            return null
        }
        val url = if (order) port.fetchOrderSlackWebhook() else port.fetchSlackWebhook()
        if (url.isNullOrBlank()) {
            if (order) {
                // 추가 채널 미설정은 정상(이중 송출 옵션) — debug.
                log.debug("system_config.notification_slack_webhook_order 미설정 — 이중 송출 skip")
            } else {
                log.warn("system_config.notification_slack_webhook 미설정 — Slack 발송 skip")
            }
            return null
        }
        return url
    }

    private fun postWithRetry(
        webhookUrl: String,
        body: String,
    ): SlackPostResult {
        var lastFailure: SlackPostResult.Failure? = null
        for (attempt in 0 until MAX_RETRY) {
            if (attempt > 0) {
                val backoff = BACKOFF_MS.getOrNull(attempt) ?: 2000L
                log.debug("Slack retry {}/{} — backoff {}ms", attempt, MAX_RETRY - 1, backoff)
                Thread.sleep(backoff)
            }
            val result = doPost(webhookUrl, body)
            when (result) {
                is SlackPostResult.Success -> return result
                is SlackPostResult.Failure -> {
                    lastFailure = result
                    if (!result.retryable) {
                        log.warn("Slack 4xx 응답 — 재시도 중단: statusCode={}", result.statusCode)
                        return result
                    }
                    log.warn(
                        "Slack {}xx 응답 — retry {}/{}: err={}",
                        result.statusCode,
                        attempt + 1,
                        MAX_RETRY,
                        result.error,
                    )
                }
            }
        }
        log.error("Slack retry 소진: maxRetry={} lastErr={}", MAX_RETRY, lastFailure?.error)
        return lastFailure ?: SlackPostResult.Failure(null, "retry exhausted", false)
    }

    private fun doPost(
        webhookUrl: String,
        body: String,
    ): SlackPostResult =
        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val statusCode = response.statusCode()

            if (statusCode in 200..299) {
                SlackPostResult.Success(statusCode)
            } else {
                val retryable = statusCode >= 500
                SlackPostResult.Failure(statusCode, "HTTP $statusCode: ${response.body().take(200)}", retryable)
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            SlackPostResult.Failure(null, "timeout: ${e.message}", retryable = true)
        } catch (e: Exception) {
            SlackPostResult.Failure(null, e.message ?: "unknown error", retryable = true)
        }

    private fun buildMessageFromPayload(payloadJson: String): String =
        try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>
            val userId = map["userId"]?.toString() ?: "?"
            val eventType =
                map["eventType"]?.toString()?.let {
                    runCatching { NotificationEventType.valueOf(it) }.getOrNull()
                } ?: NotificationEventType.RECONCILE_RUN
            val reason = map["reason"]?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val meta = (map["meta"] as? Map<String, String?>) ?: emptyMap()
            NotificationFormatter.format(userId, eventType, reason, meta)
        } catch (e: Exception) {
            log.warn("payload 파싱 실패 — fallback text 사용: err={}", e.message)
            payloadJson.take(500)
        }
}
