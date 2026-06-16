package kr.spdaewoon.magicjar.persistence.shared.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * SystemEventLoggerAdapter 단위 test — graceful 정책 4 case 검증.
 *
 * plan: `_workspace/plans/2026-05-20-system-event-log-p1-implementation-plan.md` Task 6
 *
 * graceful 정책 (spec §9 + domain port doc):
 *  - case 1 정상: DB INSERT + Kafka publish 둘 다 호출
 *  - case 2 DB 실패: log.error + Kafka publish skip (DB 가 source-of-truth — Kafka 단독 발사는 STOMP fanout 손실)
 *  - case 3 Kafka 실패: DB 영속 + log.warn (UI 알람은 후속 정합 가능)
 *  - case 4 무한 루프 차단: adapter 안에서 예외 → emit 0 (listener thread 살아 있음)
 */
class SystemEventLoggerAdapterTest {
    private val jpa = mockk<SystemEventJpaRepository>(relaxed = true)
    private val kafkaProducer = mockk<SystemEventKafkaProducer>(relaxed = true)

    // ADR 0094 P0-OPS-6 — PersistenceMetrics ObjectProvider mock (graceful skip: getIfAvailable null 반환)
    private val metricsProvider =
        mockk<ObjectProvider<PersistenceMetrics>>(relaxed = true).also {
            every { it.getIfAvailable() } returns null
        }
    private val sut = SystemEventLoggerAdapter(jpa, kafkaProducer, metricsProvider)

    private fun fixture() =
        SystemEvent(
            category = SystemEventCategory.RECONCILE,
            severity = SystemEventSeverity.INFO,
            actor = "OrderReconciliationWorker",
            action = "RECONCILE_FALLBACK_FIRED",
            userId = "74683223",
            detailJson = """{"count":1}""",
            occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
        )

    @Test
    fun `정상 emit — DB INSERT + Kafka publish 둘 다 호출`() {
        val event = fixture()
        every { jpa.save(any()) } answers { firstArg<SystemEventJpaEntity>() }

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify { kafkaProducer.publish(any<SystemEvent>()) }
    }

    @Test
    fun `DB INSERT 실패 — log error + Kafka publish 시도 안 함 (DB 영속이 source-of-truth)`() {
        val event = fixture()
        every { jpa.save(any()) } throws RuntimeException("DB connection fail")

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify(exactly = 0) { kafkaProducer.publish(any()) }
    }

    @Test
    fun `Kafka publish 실패 — DB INSERT 는 영속 + log warn (graceful)`() {
        val event = fixture()
        every { jpa.save(any()) } answers { firstArg() }
        every { kafkaProducer.publish(any()) } throws RuntimeException("Kafka broker down")

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify { kafkaProducer.publish(any<SystemEvent>()) }
        // no throw — graceful (listener thread 살아 있음)
    }

    @Test
    fun `emit 자체 무한 루프 차단 — adapter 안에서 발생한 예외는 emit 0 (log error only)`() {
        val event = fixture()
        every { jpa.save(any()) } throws IllegalStateException("simulated")
        every { kafkaProducer.publish(any()) } throws IllegalStateException("simulated")

        sut.emit(event)

        // no throw — listener thread 살아 있다
    }
}
