package kr.spdaewoon.magicjar.persistence.shared.event

import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [SystemEventLogger] port JPA + Kafka 구현 어댑터 — outbox 패턴 (PR #621 account.balance.changed 1:1 정합).
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6 + §9
 *
 * ### graceful 정책
 * - **DB INSERT 실패** → log.error + Kafka publish skip (`return`)
 *   사유: DB 영속이 source-of-truth. Kafka 단독 발사는 STOMP fanout 후 정합 불가 (사용자는 알람만 받고 dashboard 에 0건).
 * - **Kafka publish 실패** → log.warn (DB 영속만 유지)
 *   사유: UI 알람 즉시성은 손실되지만 dashboard backfill + 후속 운영 정합 가능.
 * - **emit 자체 무한 루프 차단** — runCatching 으로 wrap → listener thread 안 죽임.
 *   caller 도 `runCatching { eventLogger.emit(event) }.onFailure { log.error(...) }` 패턴 의무 (cascade 2중 차단).
 *
 * ### 트랜잭션
 * `@Transactional` — DB INSERT commit 이후 `afterCommit` 에서 Kafka publish 예약.
 * Kafka 장애·지연이 caller thread 와 DB transaction 을 오래 붙잡지 않도록 send 는 non-blocking.
 *
 * ### ADR 0051 정합
 * helper 는 adapter private fun 위치. Spring Data Repository default fun 함정 회피 (Repository 는 abstract fun only).
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class SystemEventLoggerAdapter(
    private val jpa: SystemEventJpaRepository,
    private val kafkaProducer: SystemEventKafkaProducer,
    // ADR 0094 P0-OPS-6 + ADR 0095 S-2/S-4 측정 source — Micrometer Counter increment
    // ObjectProvider 패턴: persistence-shared 단독 test fixture(스프링 Actuator 의존 부재) graceful skip
    private val persistenceMetricsProvider: ObjectProvider<PersistenceMetrics>,
) : SystemEventLogger {
    private val log = LoggerFactory.getLogger(SystemEventLoggerAdapter::class.java)

    @Transactional
    override fun emit(event: SystemEvent) {
        // ADR 0095 S-2(WS_DISCONNECTED) · S-4(KIS_RATELIMIT) 측정 — DB INSERT 성공/실패 무관 emit count 캡처
        persistenceMetricsProvider.getIfAvailable()?.incrementSystemEvent(event)

        val saved =
            runCatching { jpa.save(SystemEventJpaEntity.fromDomain(event)) }
                .onFailure { e ->
                    log.error(
                        "SystemEvent DB INSERT 실패 — Kafka publish skip: category={} severity={} action={} err={}",
                        event.category,
                        event.severity,
                        event.action,
                        e.message,
                    )
                }.getOrNull() ?: return

        val savedEvent = saved.toDomain()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publishBestEffort(savedEvent)
                    }
                },
            )
        } else {
            publishBestEffort(savedEvent)
        }
    }

    private fun publishBestEffort(event: SystemEvent) {
        runCatching { kafkaProducer.publish(event) }
            .onFailure { e ->
                log.warn(
                    "SystemEvent Kafka publish 예약 실패 — DB 영속만 유지: category={} action={} err={}",
                    event.category,
                    event.action,
                    e.message,
                )
            }
    }
}
