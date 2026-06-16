package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 알림 event_id 중복 dispatch 방지 어댑터 (spec §5 + ADR 0070 §2-4 LOCK).
 *
 * ### Redis 키 (spec §5)
 * `notification:dedup:{eventId}` TTL 24h — event_id 기반 중복 방지.
 *
 * ### 사용 케이스
 * - 분할 체결(ORDER_FILLED) N건 도착 시 동일 orderId로 1회만 Slack 전송
 * - Kafka at-least-once 재전달 안전망
 *
 * ### SETNX (SET if Not eXists) 패턴
 * setIfAbsent 성공 → 처음 처리 (allowProcess = true)
 * setIfAbsent 실패 → 이미 처리됨 (allowProcess = false)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
@Component
class NotificationDedupAdapter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(NotificationDedupAdapter::class.java)

    companion object {
        private val DEDUP_TTL = Duration.ofHours(24)
    }

    /**
     * eventId 기반 중복 체크.
     *
     * @param eventId Kafka 이벤트 UUID
     * @return true = 처음 처리 (발송 허용), false = 중복 (skip)
     */
    fun isFirstOccurrence(eventId: UUID): Boolean {
        val key = RedisKeyRegistry.Notification.eventDedup(eventId.toString())
        return try {
            val acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_TTL) ?: false
            if (!acquired) {
                log.debug("dedup skip: eventId={}", eventId)
            }
            acquired
        } catch (e: Exception) {
            log.warn("Redis dedup 체크 실패 — graceful allow: eventId={} err={}", eventId, e.message)
            true // fail-open
        }
    }

    /**
     * 분할 체결 dedup — orderId 기반 (같은 orderId의 N건 부분 체결 1회만).
     *
     * @param userId  사용자 ID
     * @param orderId 주문 ID
     * @return true = 처음 처리
     */
    fun isFirstOrderFill(
        userId: String,
        orderId: Any?,
    ): Boolean {
        if (orderId == null) return true
        val key = RedisKeyRegistry.Notification.orderDedup(userId, orderId.toString())
        return try {
            val acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_TTL) ?: false
            if (!acquired) {
                log.debug("order fill dedup skip: userId={}**** orderId={}", userId.take(4), orderId)
            }
            acquired
        } catch (e: Exception) {
            log.warn("Redis order dedup 실패 — graceful allow: userId={}**** err={}", userId.take(4), e.message)
            true
        }
    }
}
