package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * API manual reconcile trigger consumer.
 *
 * API publishes `reconcile.manual.trigger.v1`; this listener is the missing bridge that runs
 * ManualReconcileService inside the consumer JVM where KIS and position adapters live.
 */
@Component
class ManualReconcileKafkaListener(
    private val manualReconcileService: ManualReconcileService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ManualReconcileKafkaListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Reconcile.MANUAL_TRIGGER],
        groupId = "magicjar-reconcile-manual",
    )
    fun onManualTrigger(record: ConsumerRecord<String, String>) {
        val raw =
            try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            } catch (e: Exception) {
                log.error("manual reconcile trigger parse 실패: offset={} err={}", record.offset(), e.message)
                return
            }
        val userId = raw["userId"]?.toString()?.trim().orEmpty()
        if (userId.isBlank()) {
            log.warn("manual reconcile trigger userId blank — offset={}", record.offset())
            return
        }

        when (val outcome = manualReconcileService.trigger(userId)) {
            is ManualReconcileOutcome.Cached ->
                log.info("manual reconcile cached: userId={} lastRunId={}", maskUserId(userId), outcome.lastRunId)
            is ManualReconcileOutcome.Completed ->
                log.info(
                    "manual reconcile completed: userId={} runId={} status={} driftCount={}",
                    maskUserId(userId),
                    outcome.run.id,
                    outcome.run.status,
                    outcome.run.driftCount,
                )
            ManualReconcileOutcome.LockBusy ->
                log.warn("manual reconcile lock busy: userId={}", maskUserId(userId))
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
