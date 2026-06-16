package kr.spdaewoon.magicjar.consumer.messaging.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * KIS 키 변경 이벤트 Kafka 리스너 (ADR 0074 §2-2-3 Phase 3).
 *
 * 토픽: kafka.api.evt.system.kis-key.changed.v1
 * groupId: magicjar-consumer-system
 *
 * 처리 흐름:
 * 1. 5초 잠금 확인 (kis:key-changed-lock:{userId}:{accountType}) — Phase 1이 SET한 lock
 *    lock 키 존재 확인 → 존재하면 안전하게 진행 (Phase 1 성공 신호)
 *    lock 키 없어도 진행 (5초 TTL 만료 후 수신 가능한 idempotent 처리)
 * 2. Redis cache invalidate (idempotent 안전망)
 *    - kis:token:{userId}:{accountType} DEL (KIS OAuth tokenP cache)
 *    - kis:approval:{userId}:{accountType} DEL (WS approval_key cache)
 * 3. KisWebSocketBridge.reconnectGracefully(userId) 호출 → 종목 재구독
 *
 * accountType=REAL 시만 WS 재연결 (MOCK은 WS 미사용 — ADR 0074 §2-2-3 정합).
 *
 * AI 호출 0 — 룰 5 + ADR 0074 §11-2 정합.
 */
@Component
class SystemKisKeyChangedListener(
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    private val kisWebSocketBridge: KisWebSocketBridge,
) {
    private val log = LoggerFactory.getLogger(SystemKisKeyChangedListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.System.KIS_KEY_CHANGED],
        groupId = "magicjar-consumer-system",
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        val event =
            try {
                objectMapper.readValue<KisKeyChangedEvent>(record.value())
            } catch (e: Exception) {
                log.error(
                    "{} 파싱 실패 — offset={} err={}",
                    KafkaTopics.System.KIS_KEY_CHANGED,
                    record.offset(),
                    e.message,
                )
                ack.acknowledge()
                return
            }

        log.info(
            "[KisKeyChanged] 이벤트 수신: userId={} accountType={} occurredAt={}",
            maskUserId(event.userId),
            event.accountType,
            event.occurredAt,
        )

        try {
            // 1. 5초 잠금 확인 (존재 여부만 확인 — lock 없어도 진행, idempotent)
            val lockKey = RedisKeyRegistry.Kis.keyChangedLock(event.userId, event.accountType)
            val lockExists = redisTemplate.hasKey(lockKey)
            if (lockExists) {
                log.debug("[KisKeyChanged] 5초 잠금 확인됨 — Phase 1 성공 신호: key={}", lockKey)
            }

            // 2. Redis cache invalidate (idempotent 안전망 — Phase 1에서 이미 DEL했으나 재확인)
            invalidateRedisCache(event.userId, event.accountType)

            // 3. REAL 키 변경 시만 WS graceful reconnect (MOCK은 WS 미사용)
            if (RuntimeModeParser.isRealMode(event.accountType)) {
                kisWebSocketBridge.reconnectGracefully(event.userId)
                log.info(
                    "[KisKeyChanged] WS reconnect 완료 — userId={} accountType={}",
                    maskUserId(event.userId),
                    event.accountType,
                )
            } else {
                log.info(
                    "[KisKeyChanged] MOCK 키 변경 — WS reconnect 불필요: userId={} accountType={}",
                    maskUserId(event.userId),
                    event.accountType,
                )
            }

            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "[KisKeyChanged] 처리 실패 (재처리 대상) — userId={} accountType={} err={}",
                maskUserId(event.userId),
                event.accountType,
                e.message,
                e,
            )
            // ack 미호출 → 재처리 (idempotent 보장)
        }
    }

    private fun invalidateRedisCache(
        userId: String,
        accountType: String,
    ) {
        // P0 hotfix 2026-05-20 (backend-core) — 빈 userId 차단 (`kis:token::REAL` 같은 polluted key 재생성 방지)
        if (userId.isBlank()) {
            log.warn(
                "[KisKeyChanged] invalidateRedisCache userId 빈 문자열 — accountType={} 스킵 (cache pollution 차단)",
                accountType,
            )
            return
        }

        // KIS OAuth tokenP cache (KisAuthAdapter Redis 키)
        val tokenKey = RedisKeyRegistry.Kis.token(userId, accountType)
        val tokenDeleted = redisTemplate.delete(tokenKey)
        log.debug("[KisKeyChanged] Redis DEL: key={} deleted={}", tokenKey, tokenDeleted)

        // WS approval_key cache (KisApprovalAdapter Redis 키)
        val approvalKey = RedisKeyRegistry.Kis.approval(userId, accountType)
        val approvalDeleted = redisTemplate.delete(approvalKey)
        log.debug("[KisKeyChanged] Redis DEL: key={} deleted={}", approvalKey, approvalDeleted)
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}

/**
 * kafka.api.evt.system.kis-key.changed.v1 이벤트 페이로드 (ADR 0074 §2-2-3 Kafka 이벤트 스키마 정합).
 *
 * {
 *   "eventId": "<uuid>",
 *   "userId": "<userId>",
 *   "accountType": "REAL" | "MOCK",
 *   "occurredAt": "<instant>",
 *   "schemaVersion": 1
 * }
 */
data class KisKeyChangedEvent(
    val eventId: String,
    val userId: String,
    val accountType: String,
    val occurredAt: String,
    val schemaVersion: Int = 1,
)
