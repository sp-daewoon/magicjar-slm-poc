package kr.spdaewoon.magicjar.domain.user

import kotlinx.datetime.Instant

/**
 * 시스템 전역 설정 — singleton row (id=1, ADR 0046 §2-3).
 * DART API 키·Slack webhook 등 암호화 저장 (BYTEA).
 */
data class SystemConfig(
    val id: Short = 1,
    val dartApiKeyEnc: ByteArray?,
    val notificationSlackWebhookEnc: ByteArray?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SystemConfig) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
