package kr.spdaewoon.magicjar.persistence.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * SystemEventKafkaProducer 단위 test — partition key 분기 검증.
 *
 * plan: `_workspace/plans/2026-05-20-system-event-log-p1-implementation-plan.md` Task 5
 */
class SystemEventKafkaProducerTest {
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    private val sut = SystemEventKafkaProducer(kafkaTemplate, objectMapper)

    @Test
    fun `userId 있으면 partitionKey = userId 로 publish`() {
        val event =
            SystemEvent(
                category = SystemEventCategory.RECONCILE,
                severity = SystemEventSeverity.INFO,
                actor = "OrderReconciliationWorker",
                action = "RECONCILE_FALLBACK_FIRED",
                userId = "74683223",
                detailJson = """{"count":1}""",
                occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
            )
        every {
            kafkaTemplate.send(KafkaTopics.System.EVENT, "74683223", any<String>())
        } returns CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        sut.publish(event)

        verify { kafkaTemplate.send(KafkaTopics.System.EVENT, "74683223", any<String>()) }
    }

    @Test
    fun `userId null 이면 partitionKey = broadcast 로 publish`() {
        val event =
            SystemEvent(
                category = SystemEventCategory.WEBSOCKET,
                severity = SystemEventSeverity.WARN,
                actor = "KisWebSocketBridge",
                action = "WS_DISCONNECTED",
                detailJson = """{"reason":"timeout"}""",
            )
        every {
            kafkaTemplate.send(KafkaTopics.System.EVENT, "broadcast", any<String>())
        } returns CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        sut.publish(event)

        verify { kafkaTemplate.send(KafkaTopics.System.EVENT, "broadcast", any<String>()) }
    }
}
