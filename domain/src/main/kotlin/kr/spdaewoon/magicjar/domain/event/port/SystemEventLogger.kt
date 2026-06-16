package kr.spdaewoon.magicjar.domain.event.port

import kr.spdaewoon.magicjar.domain.event.SystemEvent

/**
 * 시스템 운영 이벤트 영속 port (domain).
 *
 * 구현: persistence-shared `SystemEventLoggerAdapter` (DB INSERT + Kafka publish outbox 패턴).
 *
 * graceful 정책 (spec §9):
 * - DB INSERT 실패 → log.error + Kafka publish skip (DB 영속이 source-of-truth, Kafka 단독 발사는 STOMP fanout 손실 위험 후 정합 불가)
 * - Kafka publish 실패 → log.warn (DB 영속만 유지 — UI 알람은 후속 정합 가능)
 * - emit 자체에서 발생한 모든 예외는 listener thread 안 죽임 (caller는 추가 `runCatching` wrap 의무 — cascade 차단)
 * - emit 내부에서 다시 emit 호출 금지 (무한 루프 방지 — log만)
 */
interface SystemEventLogger {
    /**
     * 운영 이벤트 emit — DB 영속 + Kafka publish (outbox).
     *
     * 호출 측 의무:
     * - 결함 발생 즉시 호출 (지연 0)
     * - `runCatching { logger.emit(event) }.onFailure { log.error(...) }` 패턴 의무
     */
    fun emit(event: SystemEvent)
}
