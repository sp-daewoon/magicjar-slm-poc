package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.reconcile.HoldingDiff
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Reconcile Kafka publish 어댑터 (ADR 0070 §4-1 토픽 매트릭스 + §2-11 자본 보호 흐름).
 *
 * publish 토픽 3건:
 * - `kafka.consumer.evt.reconcile.run.v1` (7d) — run lifecycle (RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR)
 * - `kafka.consumer.evt.reconcile.drift.v1` (30d) — drift 검출 시 종목별 1건씩 — KillSwitchAutoToggler listen 트리거
 * - `kafka.shared.evt.killswitch.engaged.v1` (30d) — KillSwitch auto-ON publish (drift 직후)
 *
 * partition key = userId — 사용자별 ordering 보장.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2 fitness 검증)
 */
@Component
class ReconcileEventKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher {
    private val log = LoggerFactory.getLogger(ReconcileEventKafkaPublisher::class.java)

    companion object {
        const val TOPIC_RUN = KafkaTopics.Reconcile.RUN
        const val TOPIC_DRIFT = KafkaTopics.Reconcile.DRIFT
        const val TOPIC_KILLSWITCH = KafkaTopics.Risk.KILLSWITCH_ENGAGED
    }

    override fun publishRun(run: ReconcileRun) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "runId" to run.id,
                        "userId" to run.userId,
                        "startedAt" to run.startedAt.epochSecond,
                        "finishedAt" to run.finishedAt?.epochSecond,
                        "driftCount" to run.driftCount,
                        "status" to run.status.name,
                        "lastError" to run.lastError,
                        "triggerSource" to run.triggerSource.name,
                    ),
                )
            kafkaTemplate.send(TOPIC_RUN, run.userId, payload)
            log.debug(
                "{} publish: userId={} runId={} status={}",
                TOPIC_RUN,
                run.userId,
                run.id,
                run.status,
            )
        } catch (e: Exception) {
            log.error(
                "{} publish 실패: userId={} runId={} err={}",
                TOPIC_RUN,
                run.userId,
                run.id,
                e.message,
            )
        }
    }

    override fun publishDrift(
        userId: String,
        runId: Long?,
        diff: HoldingDiff,
    ) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "runId" to runId,
                        "userId" to userId,
                        "symbol" to diff.symbol,
                        "kisQty" to diff.kisQty,
                        "internalQty" to diff.internalQty,
                        "qtyDiff" to diff.qtyDiff,
                        "kisAvgPrice" to diff.kisAvgPrice.toPlainString(),
                        "internalAvgPrice" to diff.internalAvgPrice.toPlainString(),
                        "avgPriceDiffRatio" to diff.avgPriceDiffRatio.toPlainString(),
                        "detectedAt" to
                            java.time.Instant
                                .now()
                                .epochSecond,
                    ),
                )
            kafkaTemplate.send(TOPIC_DRIFT, userId, payload)
            log.warn(
                "{} publish (자본 사고): userId={} symbol={} qtyDiff={}",
                TOPIC_DRIFT,
                userId,
                diff.symbol,
                diff.qtyDiff,
            )
        } catch (e: Exception) {
            log.error(
                "{} publish 실패: userId={} symbol={} err={}",
                TOPIC_DRIFT,
                userId,
                diff.symbol,
                e.message,
            )
        }
    }

    override fun publishKillSwitchEngaged(
        userId: String,
        reason: String,
        triggeredBy: String,
    ) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "userId" to userId,
                        "reason" to reason,
                        "triggeredBy" to triggeredBy,
                        "engagedAt" to
                            java.time.Instant
                                .now()
                                .epochSecond,
                    ),
                )
            kafkaTemplate.send(TOPIC_KILLSWITCH, userId, payload)
            log.warn(
                "{} publish: userId={} reason={} triggeredBy={}",
                TOPIC_KILLSWITCH,
                userId,
                reason,
                triggeredBy,
            )
        } catch (e: Exception) {
            log.error(
                "{} publish 실패: userId={} err={}",
                TOPIC_KILLSWITCH,
                userId,
                e.message,
            )
        }
    }
}
