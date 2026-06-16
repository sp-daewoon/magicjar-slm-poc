package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.reconcile.HoldingDiff
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant

/**
 * ReconcileEventKafkaPublisher 단위 테스트 (ADR 0070 §4-1 Kafka publish 매트릭스).
 *
 * 3 케이스:
 *  (a) publishRun — reconcile.run.v1 토픽 + partition key=userId
 *  (b) publishDrift — reconcile.drift.v1 토픽 + payload symbol·qtyDiff 포함
 *  (c) publishKillSwitchEngaged — killswitch.engaged.v1 토픽 + triggeredBy=AUTO 포함
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class ReconcileEventKafkaPublisherTest {
    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()

    @Test
    fun `(a) publishRun — reconcile_run_v1 토픽 + partition key=userId`() {
        val publisher = ReconcileEventKafkaPublisher(kafkaTemplate, objectMapper)
        val topicSlot = slot<String>()
        val keySlot = slot<String>()
        val payloadSlot = slot<String>()
        every { kafkaTemplate.send(capture(topicSlot), capture(keySlot), capture(payloadSlot)) } returns mockk(relaxed = true)

        val run =
            ReconcileRun(
                id = 100L,
                userId = "U1",
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                driftCount = 0,
                status = ReconcileStatus.RECONCILED,
                triggerSource = ReconcileTriggerSource.CRON,
            )
        publisher.publishRun(run)

        verify { kafkaTemplate.send(KafkaTopics.Reconcile.RUN, "U1", any<String>()) }
        assertThat(topicSlot.captured).isEqualTo(KafkaTopics.Reconcile.RUN)
        assertThat(keySlot.captured).isEqualTo("U1")
        assertThat(payloadSlot.captured).contains("\"userId\":\"U1\"")
        assertThat(payloadSlot.captured).contains("\"status\":\"RECONCILED\"")
    }

    @Test
    fun `(b) publishDrift — reconcile_drift_v1 토픽 + payload symbol·qtyDiff 포함`() {
        val publisher = ReconcileEventKafkaPublisher(kafkaTemplate, objectMapper)
        val payloadSlot = slot<String>()
        every { kafkaTemplate.send(any<String>(), any<String>(), capture(payloadSlot)) } returns mockk(relaxed = true)

        val diff =
            HoldingDiff(
                symbol = "005930",
                kisQty = 100,
                internalQty = 99,
                qtyDiff = 1,
                kisAvgPrice = BigDecimal("70000"),
                internalAvgPrice = BigDecimal("70000"),
                avgPriceDiffRatio = BigDecimal.ZERO,
                isDrift = true,
                isPriceDrift = false,
            )
        publisher.publishDrift("U1", runId = 200L, diff = diff)

        verify { kafkaTemplate.send(KafkaTopics.Reconcile.DRIFT, "U1", any<String>()) }
        assertThat(payloadSlot.captured).contains("\"symbol\":\"005930\"")
        assertThat(payloadSlot.captured).contains("\"qtyDiff\":1")
        assertThat(payloadSlot.captured).contains("\"kisQty\":100")
    }

    @Test
    fun `(c) publishKillSwitchEngaged — killswitch_engaged_v1 토픽 + triggeredBy=AUTO 포함`() {
        val publisher = ReconcileEventKafkaPublisher(kafkaTemplate, objectMapper)
        val payloadSlot = slot<String>()
        every { kafkaTemplate.send(any<String>(), any<String>(), capture(payloadSlot)) } returns mockk(relaxed = true)

        publisher.publishKillSwitchEngaged("U1", reason = "POSITION_DRIFT", triggeredBy = "AUTO")

        verify { kafkaTemplate.send(KafkaTopics.Risk.KILLSWITCH_ENGAGED, "U1", any<String>()) }
        assertThat(payloadSlot.captured).contains("\"triggeredBy\":\"AUTO\"")
        assertThat(payloadSlot.captured).contains("\"reason\":\"POSITION_DRIFT\"")
    }
}
