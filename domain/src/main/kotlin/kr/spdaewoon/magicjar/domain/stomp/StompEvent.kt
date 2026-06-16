package kr.spdaewoon.magicjar.domain.stomp

import java.time.Instant

/**
 * STOMP 이벤트 영속 도메인 — ADR 0084 P1-2 + spec 2026-05-18 order-path-stability §2-6 정합.
 *
 * 목적: frontend STOMP 클라이언트가 disconnect 상태일 때 publish된 메시지 손실 risk 회피.
 * publish 시점에 stomp_event 테이블에 INSERT → 재접속 시 GET /api/stomp/events?since= backfill.
 *
 * ### 필드
 * @param id          BIGSERIAL PK (DB 생성)
 * @param userId      사용자 ID (multi-tenant 격리 + STOMP fanout 키)
 * @param channel     STOMP topic 그룹 — order · signal · risk · recon · notification 등
 * @param eventType   이벤트 세부 유형 — REQUESTED · FILLED · reconcile.drift.detected 등
 * @param payloadJson JSONB 페이로드 직렬화 결과 (frontend 재구성 의무)
 * @param createdAt   생성 시각 (publish 직전 시점, Asia/Seoul 변환은 표시 layer)
 *
 * ### 보존 기간
 * 7일 (retention cron 별도 사이클) — STOMP unread backfill에 충분.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합 (W3 트랙 잔여 결함 수습)
 */
data class StompEvent(
    val id: Long? = null,
    val userId: Long,
    val channel: String,
    val eventType: String,
    val payloadJson: String,
    val createdAt: Instant = Instant.now(),
)
