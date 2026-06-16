package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * NotificationDispatcher 단위 테스트 (spec §2-4 + ADR 0070 §2-4 LOCK).
 *
 * 7 케이스:
 *  (1) ORDER_FILLED → outbox INSERT PENDING + 즉시 발송
 *  (2) ORDER_REJECTED → outbox INSERT PENDING + 즉시 발송
 *  (3) ORDER_CANCELLED → outbox INSERT PENDING
 *  (4) RISK_VIOLATION (P0) → outbox INSERT + 즉시 발송 시도
 *  (5) RECONCILE_DRIFT (P0) → outbox INSERT + 즉시 발송 시도
 *  (6) KILLSWITCH_ENGAGED (P0) → outbox INSERT + 즉시 발송 시도
 *  (7) RECONCILE_RUN (P2) → rate limit 체크 후 outbox INSERT
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationDispatcherTest {
    private val objectMapper = ObjectMapper()
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>()
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)
    private val instrumentRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val strategyRepository = mockk<ConsumerStrategyJpaRepository>()

    private val outboxProvider = mockk<ObjectProvider<NotificationOutboxRepository>>()
    private val rateLimiterProvider = mockk<ObjectProvider<NotificationRateLimiterPort>>()
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()
    private val instrumentRepositoryProvider = mockk<ObjectProvider<ConsumerInstrumentJpaRepository>>()
    private val strategyRepositoryProvider = mockk<ObjectProvider<ConsumerStrategyJpaRepository>>()

    private val dispatcher: NotificationDispatcher =
        run {
            every { outboxProvider.getIfAvailable() } returns outboxRepository
            every { rateLimiterProvider.getIfAvailable() } returns rateLimiter
            every { slackSenderProvider.getIfAvailable() } returns slackSender
            every { instrumentRepositoryProvider.getIfAvailable() } returns instrumentRepository
            every { strategyRepositoryProvider.getIfAvailable() } returns strategyRepository
            // rate limit: P1/P2는 기본 emit 허용
            every { rateLimiter.shouldEmit(any(), any(), any(), any()) } returns true
            every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns true
            every { instrumentRepository.findById(any()) } returns Optional.empty()
            every { strategyRepository.findById(any()) } returns Optional.empty()
            // outbox save: eventId 포함 응답
            every { outboxRepository.save(any()) } answers {
                val arg = it.invocation.args[0] as NotificationOutbox
                arg.copy(id = 100L)
            }
            NotificationDispatcher(
                outboxProvider,
                rateLimiterProvider,
                slackSenderProvider,
                instrumentRepositoryProvider,
                strategyRepositoryProvider,
                objectMapper,
            )
        }

    @Test
    fun `(1) ORDER_FILLED — outbox INSERT PENDING plus immediate Slack (P0 TRADE)`() {
        every { instrumentRepository.findById("005930") } returns Optional.of(ConsumerInstrumentJpaEntity(code = "005930", name = "삼성전자"))
        every { strategyRepository.findById(43L) } returns
            Optional.of(
                ConsumerStrategyJpaEntity(
                    id = 43L,
                    userId = 1L,
                    name = "222",
                    nameKo = "익절 전략",
                    dslEntry = "close > 0",
                    dslExit = null,
                ),
            )
        val record = record(KafkaTopics.Order.FILLED, filledPayload("U1", "005930"))
        dispatcher.onOrderFilled(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        assertThat(slot.captured.status).isEqualTo(NotificationStatus.PENDING)
        assertThat(slot.captured.payloadJson).contains("삼성전자(005930)")
        assertThat(slot.captured.payloadJson).contains("매수")
        assertThat(slot.captured.payloadJson).contains("75,000원")
        assertThat(slot.captured.payloadJson).contains("익절 전략")
        verify { slackSender.sendOutbox(any()) }
        // ORDER_FILLED 는 추가 Slack 채널로도 이중 송출 (2026-06-10 V79)
        verify { slackSender.sendToOrderChannel(any()) }
    }

    @Test
    fun `(1-b) ORDER_FILLED — 추가 채널 송출 실패해도 기존 outbox plus 발송 영향 0 (격리)`() {
        every { slackSender.sendToOrderChannel(any()) } throws RuntimeException("추가 채널 webhook 5xx")
        val record = record(KafkaTopics.Order.FILLED, filledPayload("U1", "005930"))

        // 추가 채널 예외가 caller 흐름으로 전파되지 않음 (예외 0 종료)
        dispatcher.onOrderFilled(record)

        // 기존 채널 흐름은 정상 — outbox INSERT + sendOutbox 호출
        verify { outboxRepository.save(any()) }
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(2) ORDER_REJECTED — outbox INSERT PENDING plus immediate Slack (P0 TRADE)`() {
        val record = record(KafkaTopics.Order.REJECTED, rejectedPayload("U1", "005930"))
        dispatcher.onOrderRejected(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
        // ORDER_REJECTED 는 추가 채널 이중 송출 대상이 아님 (ORDER_FILLED 한정)
        verify(exactly = 0) { slackSender.sendToOrderChannel(any()) }
    }

    @Test
    fun `(3) ORDER_CANCELLED — outbox INSERT PENDING (P2)`() {
        val record = record(KafkaTopics.Order.CANCELLED, cancelledPayload("U1", "005930"))
        dispatcher.onOrderCancelled(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P2)
    }

    @Test
    fun `(4) RISK_VIOLATION (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record(KafkaTopics.Risk.VIOLATION, riskViolationPayload("U1"))
        dispatcher.onRiskViolation(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        assertThat(slot.captured.payloadJson).contains("D. 거래 빈도 한도 도달 - 일별 최대 거래")
        assertThat(slot.captured.payloadJson).contains("오늘 허용 주문 수 50회")
        // P0 즉시 발송 시도 → sender 호출
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(5) RECONCILE_DRIFT (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record(KafkaTopics.Reconcile.DRIFT, driftPayload("U1"))
        dispatcher.onReconcileDrift(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(6) KILLSWITCH_ENGAGED (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record(KafkaTopics.Risk.KILLSWITCH_ENGAGED, killSwitchPayload("U1"))
        dispatcher.onKillSwitchEngaged(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(7) RECONCILE_RUN (P2) — rate limit 체크 후 outbox INSERT`() {
        val record = record(KafkaTopics.Reconcile.RUN, reconcileRunPayload("U1"))
        dispatcher.onReconcileRun(record)

        verify { outboxRepository.save(any()) }
        verify { rateLimiter.shouldEmit("U1", "reconcile.run", any(), any()) }
    }

    @Test
    fun `POSITION_LIMIT 반복 리스크는 동일 사용자 종목 규칙 전략 기준으로 Slack dedupe 한다`() {
        every {
            rateLimiter.shouldEmitOncePerWindow("U1", "risk.violation.position_limit.005930.8", 15)
        } returns false

        val record = record(KafkaTopics.Risk.VIOLATION, positionLimitPayload("U1"))
        dispatcher.onRiskViolation(record)

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `SELL_WITHOUT_POSITION 차단은 Slack 발송 제외 — outbox INSERT 없음 (DB·Kafka·STOMP 는 별도 경로 유지)`() {
        val record = record(KafkaTopics.Risk.VIOLATION, sellWithoutPositionPayload("U1"))
        dispatcher.onRiskViolation(record)

        // Slack 채널만 제외: outbox INSERT·발송 0건
        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    @Test
    fun `타 리스크 룰(TRADE_FREQUENCY)은 SELL_WITHOUT_POSITION 제외 영향 없이 기존대로 Slack 발송`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record(KafkaTopics.Risk.VIOLATION, riskViolationPayload("U1"))
        dispatcher.onRiskViolation(record)

        verify { outboxRepository.save(any()) }
        verify { slackSender.sendOutbox(any()) }
    }

    // --- payload builders ---

    private fun record(
        topic: String,
        payload: String,
    ) = ConsumerRecord<String, String>(topic, 0, 0L, "U1", payload)

    private fun filledPayload(
        userId: String,
        code: String,
    ) =
        """{"eventId":"${UUID.randomUUID()}","orderId":"O1","userId":"$userId","strategyId":43,"source":"AUTO","code":"$code","side":"BUY","executedQuantity":10,"executedPrice":"75000","executedAt":${Instant.now().epochSecond}}"""

    private fun rejectedPayload(
        userId: String,
        code: String,
    ) = """{"eventId":"${UUID.randomUUID()}","orderId":"O2","userId":"$userId","code":"$code","side":"BUY","reason":"KILLSWITCH_ON"}"""

    private fun cancelledPayload(
        userId: String,
        code: String,
    ) = """{"eventId":"${UUID.randomUUID()}","orderId":"O3","userId":"$userId","code":"$code","side":"SELL","kisOrderNo":"K123"}"""

    private fun riskViolationPayload(userId: String) =
        """
        {
          "eventId":"${UUID.randomUUID()}",
          "signalId":"S1",
          "userId":"$userId",
          "code":"005930",
          "rule":"TRADE_FREQUENCY_DAY",
          "reason":"오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.",
          "rawReason":"Strategy daily trade count 50 >= limit 50",
          "alertTitle":"D. 거래 빈도 한도 도달 - 일별 최대 거래",
          "alertMessage":"오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.",
          "alertFormula":"오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일",
          "alertSetting":"D. 거래 빈도 한도 - 일별 최대 거래"
        }
        """.trimIndent()

    private fun positionLimitPayload(userId: String) =
        """
        {
          "eventId":"${UUID.randomUUID()}",
          "signalId":190,
          "strategyId":8,
          "userId":"$userId",
          "code":"005930",
          "rule":"POSITION_LIMIT",
          "verdict":"BLOCKED",
          "reason":"종목 005930 주문이 종목당 최대 투자금액을 넘어서 자동 주문을 내보내지 않았습니다.",
          "rawReason":"Unit price 347750 exceeds remaining position budget 304500",
          "alertTitle":"A. 포지션 한도 초과 - 종목당 최대 투자금액 차단",
          "alertMessage":"종목 005930 주문이 종목당 최대 투자금액을 넘어서 자동 주문을 내보내지 않았습니다.",
          "alertFormula":"신호 단가 347,750원 > 남은 종목 한도 304,500원",
          "alertSetting":"A. 포지션 한도 - 절대 한도와 비율 한도 중 작은 값 적용"
        }
        """.trimIndent()

    private fun sellWithoutPositionPayload(userId: String) =
        """
        {
          "eventId":"${UUID.randomUUID()}",
          "signalId":201,
          "strategyId":8,
          "userId":"$userId",
          "code":"005930",
          "rule":"SELL_WITHOUT_POSITION",
          "verdict":"BLOCKED",
          "reason":"보유 수량이 없어 매도 신호를 주문으로 보내지 않았습니다.",
          "alertTitle":"보유 없는 매도 차단",
          "alertMessage":"보유 수량이 없어 매도 신호를 주문으로 보내지 않았습니다."
        }
        """.trimIndent()

    private fun driftPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","runId":101,"userId":"$userId","symbol":"005930","kisQty":100,"internalQty":99,"qtyDiff":1}"""

    private fun killSwitchPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","userId":"$userId","reason":"POSITION_DRIFT","triggeredBy":"AUTO","engagedAt":${Instant.now().epochSecond}}"""

    private fun reconcileRunPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","runId":101,"userId":"$userId","status":"RECONCILED","driftCount":0}"""
}
