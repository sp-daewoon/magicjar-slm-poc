package kr.spdaewoon.magicjar.api.service.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 수동 Reconcile trigger 서비스 — T-RECON Phase 2 REST endpoint 책임 (ADR 0070 §6-1).
 *
 * ### 설계 (api 모듈 의존 범위)
 * api 모듈은 consumer 모듈 의존 0 (모듈 경계 LOCK).
 * 수동 trigger 시 Kafka `reconcile.manual.trigger.v1` publish → consumer ManualReconcileService가 listen.
 * idempotency: Redis `reconcile:lastrun:{userId}` 5분 TTL 캐시 직접 확인.
 *
 * ### HTTP 상태 매핑 (spec §6-1 정합)
 * - 캐시 hit + runId 존재 → 200 OK + 직전 run 반환
 * - 캐시 miss         → 202 Accepted + Kafka publish (consumer가 비동기 처리)
 * - lock busy          → 409 Conflict (Redis reconcile:lock:{userId} 존재 시)
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Service
class ReconcileManualTriggerService(
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val reconcileQueryService: ReconcileQueryService,
) {
    private val log = LoggerFactory.getLogger(ReconcileManualTriggerService::class.java)

    companion object {
        private const val TOPIC_MANUAL_TRIGGER = KafkaTopics.Reconcile.MANUAL_TRIGGER
    }

    /**
     * 수동 reconcile trigger.
     *
     * @return [ManualTriggerResult] — HTTP 응답 매핑용 sealed
     * @throws ResponseStatusException 409 — lock busy 시
     */
    fun trigger(userId: Long): ManualTriggerResult {
        log.info(
            "ReconcileManualTriggerService.trigger: userId={}****",
            userId.toString().take(4),
        )

        // 1. idempotency 캐시 hit 확인
        val userIdKey = userId.toString()
        val lastRunId = redisTemplate.opsForValue().get(RedisKeyRegistry.Reconcile.lastRun(userIdKey))
        if (lastRunId != null) {
            val runIdLong = lastRunId.toLongOrNull()
            log.info(
                "trigger — idempotency cache hit: userId={}**** lastRunId={}",
                userId.toString().take(4),
                lastRunId,
            )
            val run =
                runIdLong?.let {
                    runCatching { reconcileQueryService.findRunDetail(it, userId) }.getOrNull()
                }
            return ManualTriggerResult.Cached(run, runIdLong)
        }

        // 2. 분산락 확인 (cron 또는 다른 manual 실행 중)
        val lockExists = redisTemplate.hasKey(RedisKeyRegistry.Reconcile.lock(userIdKey))
        if (lockExists) {
            log.warn(
                "trigger — lock busy: userId={}**** (cron 또는 다른 manual 실행 중)",
                userId.toString().take(4),
            )
            throw ResponseStatusException(HttpStatus.CONFLICT, "reconcile 실행 중 (5분 후 재시도)")
        }

        // 3. Kafka publish — consumer ManualReconcileService가 listen (비동기 처리)
        val eventId = UUID.randomUUID().toString()
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "eventId" to eventId,
                    "userId" to userId.toString(),
                    "triggerSource" to "MANUAL",
                ),
            )
        try {
            kafkaTemplate.send(TOPIC_MANUAL_TRIGGER, userId.toString(), payload)
            log.info(
                "trigger — reconcile.manual.trigger.v1 publish: userId={}**** eventId={}",
                userId.toString().take(4),
                eventId,
            )
        } catch (e: Exception) {
            log.error(
                "trigger — Kafka publish 실패: userId={}**** err={}",
                userId.toString().take(4),
                e.message,
            )
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "reconcile trigger 실패: ${e.message}")
        }

        // 4. 즉시 응답 — consumer 비동기 처리 중 (run은 DB에 RUNNING 상태로 삽입됨)
        return ManualTriggerResult.Triggered(eventId)
    }
}

/**
 * 수동 reconcile trigger 결과 sealed (REST controller HTTP 매핑용).
 */
sealed class ManualTriggerResult {
    /**
     * idempotency cache hit — 5분 내 직전 실행 결과 재사용.
     * HTTP 200 OK.
     */
    data class Cached(
        val run: ReconcileRun?,
        val lastRunId: Long?,
    ) : ManualTriggerResult()

    /**
     * Kafka publish 완료 — consumer 비동기 처리 시작.
     * HTTP 202 Accepted.
     */
    data class Triggered(
        val eventId: String,
    ) : ManualTriggerResult()
}
