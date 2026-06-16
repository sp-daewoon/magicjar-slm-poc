package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * STOMP publish 직전 영속 helper — ADR 0084 P1-2 (spec 2026-05-18 order-path-stability §2-6).
 *
 * 4개 STOMP publisher(`StompOrderNotifier` · `StompSignalDispatcher` · `StompRiskNotifier` · `StompReconcileNotifier`)
 * publish 직전 본 helper 호출 → stomp_event 테이블 INSERT.
 *
 * frontend 재접속 시 `GET /api/stomp/events?since=` backfill 호출로 disconnect 동안 누락 이벤트 회복.
 *
 * ### 운영 가드
 * - 영속 실패 시 publish 자체는 막지 않음 (best-effort) — log.error 후 swallow.
 *   사유: STOMP 즉시 publish는 자본 흐름과 분리되어 있으므로 영속 실패 시에도 frontend 실시간 표시 우선.
 * - toggle: `magicjar.stomp.event-persistence.enabled` (default true) — 영속 path 비활성 rollback.
 *
 * 헌법 §6 (ADR 0051): body 가진 helper는 본 클래스 내부에만.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class StompEventPersister(
    private val stompEventRepository: StompEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.stomp.event-persistence.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(StompEventPersister::class.java)

    /**
     * STOMP publish 직전 영속 (best-effort).
     *
     * @param userId    사용자 ID (multi-tenant 격리 키)
     * @param channel   STOMP topic 그룹 (order · signal · risk · recon 등)
     * @param eventType 이벤트 세부 유형
     * @param payload   STOMP 페이로드 객체 — 직렬화는 본 helper 책임
     */
    fun persist(
        userId: Long,
        channel: String,
        eventType: String,
        payload: Any,
    ) {
        if (!enabled) return
        // 직렬화는 별도 try-catch로 분리 — 실패 시에도 audit row는 "{}" fallback으로 발행하여 backfill 추적성 보장.
        // PR #530 CandleBackfillWorker.persistAudit() 동일 패턴 정합.
        val payloadJson =
            try {
                objectMapper.writeValueAsString(payload)
            } catch (e: Exception) {
                log.warn(
                    "stomp_event payload 직렬화 실패 → '{}' fallback: userId={} channel={} eventType={} err={}",
                    "{}",
                    userId,
                    channel,
                    eventType,
                    e.message,
                )
                "{}"
            }
        try {
            val event =
                StompEvent(
                    userId = userId,
                    channel = channel,
                    eventType = eventType,
                    payloadJson = payloadJson,
                    createdAt = Instant.now(),
                )
            stompEventRepository.save(event)
        } catch (e: Exception) {
            // best-effort — publish 자체는 막지 않음
            log.error(
                "stomp_event 영속 실패: userId={} channel={} eventType={} err={}",
                userId,
                channel,
                eventType,
                e.message,
            )
        }
    }

    /**
     * userId가 String 형(예: 기존 STOMP publisher의 payload 필드)으로 들어오는 경우의 편의 오버로드.
     * Long 파싱 실패 시 영속 skip (warn 로그).
     */
    fun persist(
        userId: String,
        channel: String,
        eventType: String,
        payload: Any,
    ) {
        val parsed = userId.toLongOrNull()
        if (parsed == null) {
            log.warn(
                "stomp_event 영속 skip — userId 파싱 실패: userId={} channel={} eventType={}",
                userId.take(8),
                channel,
                eventType,
            )
            return
        }
        persist(parsed, channel, eventType, payload)
    }
}
