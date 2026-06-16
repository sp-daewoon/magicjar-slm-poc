package kr.spdaewoon.magicjar.api.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.ReconcileStompPayload
import kr.spdaewoon.magicjar.api.messaging.StompDestinations
import kr.spdaewoon.magicjar.api.messaging.StompEventPersister
import kr.spdaewoon.magicjar.api.messaging.StompReconcileNotifier
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * StompReconcileNotifierTest — 3 케이스 (spec §2-3 ~10건 GREEN 의무 LOCK).
 *
 * 3 토픽 → 4 event discriminator STOMP 발행 검증.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
class StompReconcileNotifierTest {
    private val messagingTemplate: SimpMessagingTemplate = mock(SimpMessagingTemplate::class.java)
    private val objectMapper = ObjectMapper()

    // ADR 0084 P1-2: StompEventPersister inject — repository mock + enabled=true
    private val stompEventRepository: StompEventRepository = mock(StompEventRepository::class.java)
    private val stompEventPersister = StompEventPersister(stompEventRepository, objectMapper, enabled = true)
    private val sut = StompReconcileNotifier(messagingTemplate, objectMapper, stompEventPersister)

    private fun makeRecord(
        topic: String,
        userId: String,
        extraFields: Map<String, Any?> = emptyMap(),
    ): ConsumerRecord<String, String> {
        val base = mutableMapOf<String, Any?>("userId" to userId, "runId" to 1001)
        base.putAll(extraFields)
        val payload = objectMapper.writeValueAsString(base)
        return ConsumerRecord(topic, 0, 0L, "key", payload)
    }

    @Test
    fun `reconcile_run_v1 status=RUNNING → reconcile_run_started event STOMP 발행`() {
        val topic = KafkaTopics.Reconcile.RUN
        val userId = "12345678"
        val record = makeRecord(topic, userId, mapOf("status" to "RUNNING", "triggerSource" to "CRON"))

        sut.onReconcileEvent(record, topic)

        val captor = ArgumentCaptor.forClass(ReconcileStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(
            eq(StompDestinations.reconUser(userId)),
            capture(captor),
        )
        assertThat(captor.value.eventType).isEqualTo("reconcile.run.started")
        assertThat(captor.value.userId).isEqualTo(userId)
    }

    @Test
    fun `reconcile_run_v1 status=RECONCILED → reconcile_run_completed event STOMP 발행`() {
        val topic = KafkaTopics.Reconcile.RUN
        val userId = "12345678"
        val record = makeRecord(topic, userId, mapOf("status" to "RECONCILED", "driftCount" to 0))

        sut.onReconcileEvent(record, topic)

        val captor = ArgumentCaptor.forClass(ReconcileStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(
            eq(StompDestinations.reconUser(userId)),
            capture(captor),
        )
        assertThat(captor.value.eventType).isEqualTo("reconcile.run.completed")
        assertThat(captor.value.status).isEqualTo("RECONCILED")
    }

    @Test
    fun `reconcile_drift_v1 → reconcile_drift_detected event STOMP 발행`() {
        val topic = KafkaTopics.Reconcile.DRIFT
        val userId = "12345678"
        val record =
            makeRecord(
                topic,
                userId,
                mapOf("symbol" to "005930", "kisQty" to 100, "internalQty" to 99, "qtyDiff" to 1),
            )

        sut.onReconcileEvent(record, topic)

        val captor = ArgumentCaptor.forClass(ReconcileStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(
            eq(StompDestinations.reconUser(userId)),
            capture(captor),
        )
        assertThat(captor.value.eventType).isEqualTo("reconcile.drift.detected")
        assertThat(captor.value.symbol).isEqualTo("005930")
        assertThat(captor.value.kisQty).isEqualTo(100)
        assertThat(captor.value.qtyDiff).isEqualTo(1)
    }

    @Test
    fun `killswitch_engaged_v1 → killswitch_auto_engaged event STOMP 발행`() {
        val topic = KafkaTopics.Risk.KILLSWITCH_ENGAGED
        val userId = "12345678"
        val record =
            makeRecord(
                topic,
                userId,
                mapOf("reason" to "POSITION_DRIFT", "triggeredBy" to "AUTO"),
            )

        sut.onReconcileEvent(record, topic)

        val captor = ArgumentCaptor.forClass(ReconcileStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(
            eq(StompDestinations.reconUser(userId)),
            capture(captor),
        )
        assertThat(captor.value.eventType).isEqualTo("killswitch.auto.engaged")
        assertThat(captor.value.reason).isEqualTo("POSITION_DRIFT")
        assertThat(captor.value.triggeredBy).isEqualTo("AUTO")
    }

    @Test
    fun `userId null인 페이로드 — STOMP 발행 skip`() {
        val topic = KafkaTopics.Reconcile.RUN
        val payload = objectMapper.writeValueAsString(mapOf("runId" to 1))
        val record = ConsumerRecord<String, String>(topic, 0, 0L, "key", payload)

        sut.onReconcileEvent(record, topic)

        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }
}
