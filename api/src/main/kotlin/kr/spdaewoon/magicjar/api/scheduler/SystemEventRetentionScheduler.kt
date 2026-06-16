package kr.spdaewoon.magicjar.api.scheduler

import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 시스템 이벤트 운영 대시보드 DB retention.
 *
 * Kafka `kafka.shared.evt.system.event.v1` retention 은 이미 7일이지만, dashboard source 인 `system_event`
 * 테이블은 별도 정리가 없어 운영 화면에 계속 누적됐다. API 대시보드 소유 경계에서
 * 매일 오래된 row 만 bulk delete 한다.
 */
@Component
class SystemEventRetentionScheduler(
    private val systemEventJpaRepository: SystemEventJpaRepository,
    @Value("\${magicjar.system-event.retention.enabled:true}")
    private val enabled: Boolean,
    @Value("\${magicjar.system-event.retention.days:7}")
    private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(SystemEventRetentionScheduler::class.java)

    @Scheduled(cron = "\${magicjar.system-event.retention.cleanup-cron:0 20 4 * * *}", zone = "Asia/Seoul")
    @Transactional
    fun cleanupExpiredEvents() {
        if (!enabled) {
            log.debug("system_event retention disabled")
            return
        }

        val days = retentionDays.coerceAtLeast(1)
        val cutoff = Instant.now().minus(Duration.ofDays(days))
        val deleted = systemEventJpaRepository.deleteOlderThan(cutoff)
        if (deleted > 0) {
            log.info("system_event retention: deleted={} cutoff={} days={}", deleted, cutoff, days)
        } else {
            log.debug("system_event retention: deleted=0 cutoff={} days={}", cutoff, days)
        }
    }
}
