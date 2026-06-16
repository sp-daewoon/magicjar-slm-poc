package kr.spdaewoon.magicjar.persistence.shared.notification

import kr.spdaewoon.magicjar.domain.notification.port.SystemConfigSlackPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * system_config.notification_slack_webhook BYTEA 복호화 어댑터.
 *
 * persistence-shared에 위치해 api와 consumer가 같은 Slack webhook source를 읽는다.
 * NULL 또는 복호화 실패 시 null 반환 → SlackWebhookSender graceful skip.
 */
@Component
class SystemConfigSlackAdapter(
    private val systemConfigJpaRepository: SystemConfigJpaRepository,
    private val secretCipher: SecretCipher,
) : SystemConfigSlackPort {
    private val log = LoggerFactory.getLogger(SystemConfigSlackAdapter::class.java)

    override fun fetchSlackWebhook(): String? = resolveWebhook(column = "notification_slack_webhook") { it.notificationSlackWebhook }

    override fun fetchOrderSlackWebhook(): String? =
        resolveWebhook(column = "notification_slack_webhook_order") { it.notificationSlackWebhookOrder }

    /**
     * system_config 단일 row 의 BYTEA webhook 컬럼을 복호화하는 공통 helper (DRY · 진보 페르소나 ADR 0087).
     *
     * 기본 채널·주문 추가 채널이 동일 복호화 흐름이라 컬럼 선택 [selector] 만 파라미터화.
     * NULL/empty/복호화 실패 → null (sender graceful skip).
     */
    private fun resolveWebhook(
        column: String,
        selector: (SystemConfigJpaEntity) -> ByteArray?,
    ): String? {
        val config =
            systemConfigJpaRepository.findSingleton() ?: run {
                log.warn("system_config 레코드 없음 — {} null 반환", column)
                return null
            }

        val encrypted = selector(config)
        if (encrypted == null || encrypted.isEmpty()) {
            log.debug("system_config.{} 미설정 (NULL)", column)
            return null
        }

        return try {
            val decrypted = secretCipher.decrypt(encrypted)
            val url = String(decrypted, Charsets.UTF_8).trim()
            if (url.isBlank()) {
                log.warn("system_config.{} 복호화 결과 empty", column)
                null
            } else {
                url
            }
        } catch (e: Exception) {
            log.error("{} 복호화 실패 — null 반환: err={}", column, e.message)
            null
        }
    }
}
