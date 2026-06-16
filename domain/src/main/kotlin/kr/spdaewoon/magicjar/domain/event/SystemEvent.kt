package kr.spdaewoon.magicjar.domain.event

import java.time.Instant

/**
 * 시스템 운영 이벤트 — Reconcile·WebSocket·Kafka·KIS·DB·Redis 등 외부 의존 실패 catalog.
 *
 * 영속: SystemEventLoggerAdapter → DB system_event + Kafka system.event.v1.
 * UI 표시: P2 STOMP fanout `/topic/system.event.user.{userId}` 또는 `/topic/system.event.broadcast`.
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.1
 */
data class SystemEvent(
    val category: SystemEventCategory,
    val severity: SystemEventSeverity,
    /** 발사 component (예: "OrderReconciliationWorker") */
    val actor: String,
    /** 이벤트 코드 (예: "RECONCILE_FALLBACK_FIRED") */
    val action: String,
    /** null = system-wide broadcast */
    val userId: String? = null,
    /** 동일 사건 묶기 — 자동 생성 또는 emit site 명시 전달 (WS disconnect→reconnect 같은 case) */
    val correlationId: String? = null,
    /** 자유 형식 JSON (재발생 사유·retry count 등) */
    val detailJson: String,
    val occurredAt: Instant = Instant.now(),
)

/**
 * 시스템 이벤트 분류 — UI 필터·Kafka partition 분배·dashboard 그룹화 축.
 *
 * P1: RECONCILE·WEBSOCKET 활성, 나머지는 P3 catalog 확장에서 활용.
 */
enum class SystemEventCategory {
    /** P1 — Reconcile worker 3종 (OrderReconciliationWorker·BalanceReconcileService) */
    RECONCILE,

    /** P1 — KisWebSocketBridge disconnect/reconnect/fail */
    WEBSOCKET,

    /** P3 — Kafka publish retry·DLT */
    KAFKA,

    /**
     * P1 — EGW00201·EGW00121 rate limit hit (진단 PR #639 → hotfix P0 활성).
     *
     * emit point: api/consumer/batch `RedisKisRateLimiterAdapter.onTpsExceeded()`
     * aggregator: 5분당 1회 (spam 회피)
     * severity: WARN (반복 burst 시 Phase 3 Circuit Breaker OPEN trigger 시 ERROR로 격상 후속)
     */
    KIS_RATELIMIT,

    /** P3 — DB connection·query fail */
    DB,

    /** P3 — Redis fail */
    REDIS,

    /** 확장용 — 향후 catalog 추가 시 임시 분류 */
    OTHER,
}

/**
 * 시스템 이벤트 심각도 — UI 표시 우선순위 + CRITICAL toast 트리거 축.
 *
 * - INFO: 정상 흐름 audit (예: WS_RECONNECTED 성공)
 * - WARN: 운영 결함 신호 — 후속 정합 필요 (예: RECONCILE_FALLBACK_RESOLVED·WS_DISCONNECTED)
 * - ERROR: 자동 회복 실패 — 사용자 결정 필요
 * - CRITICAL: 즉시 사용자 알림 (P2 toast popup 트리거)
 */
enum class SystemEventSeverity { INFO, WARN, ERROR, CRITICAL }
