package kr.spdaewoon.magicjar.api.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.journal.JournalEventConsumer
import kr.spdaewoon.magicjar.api.messaging.journal.JournalSummaryComposer
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaEntity
import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * JournalEventConsumerTest — 실제 체결 복기장 회귀 테스트.
 *
 * - ORDER_FILLED 변환
 * - executedQty/executionId 키 정합
 * - strategy/signal context enrich
 * - 멱등성 UQ conflict 재처리 → null 반환 시 skip
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class JournalEventConsumerTest {
    private val journalRepository: TradingJournalRepository = mock()
    private val summaryComposer = JournalSummaryComposer()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val signalJpaRepository: SignalJpaRepository = mock()
    private val strategyJpaRepository: StrategyJpaRepository = mock()
    private val instrumentJpaRepository: InstrumentJpaRepository = mock()
    private val sut =
        JournalEventConsumer(
            journalRepository,
            summaryComposer,
            objectMapper,
            signalJpaRepository,
            strategyJpaRepository,
            instrumentJpaRepository = instrumentJpaRepository,
        )

    private fun makeRecord(
        topic: String,
        value: String,
    ): ConsumerRecord<String, String> = ConsumerRecord(topic, 0, 0L, "key", value)

    private fun makeSavedEntry(
        eventType: JournalEventType,
        sourceId: Long,
    ): TradingJournalEntry =
        TradingJournalEntry(
            id = 1001L,
            userId = 12345678L,
            eventType = eventType,
            occurredAt = Instant.now(),
            summary = "테스트",
            payload = "{}",
            sourceTable = "test",
            sourceId = sourceId,
        )

    @Test
    fun `onOrderFilled — ORDER_FILLED entry INSERT 검증`() {
        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "code" to "005930",
                "side" to "BUY",
                "executionId" to 9001L,
                "executedQty" to 100,
                "executedPrice" to "75000",
                "executedAt" to Instant.now().epochSecond,
                "instrumentName" to "삼성전자",
                "realizedPnl" to "150000",
            )
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_FILLED, 101L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderFilled(makeRecord(KafkaTopics.Order.FILLED, objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.ORDER_FILLED)
        assertThat(entry.userId).isEqualTo(12345678L)
        assertThat(entry.instrumentCode).isEqualTo("005930")
        assertThat(entry.sourceTable).isEqualTo("order_execution")
        assertThat(entry.sourceId).isEqualTo(9001L)
        assertThat(entry.summary).contains("체결")
        assertThat(entry.summary).contains("삼성전자")
        assertThat(entry.realizedPnl).isEqualByComparingTo(BigDecimal("150000"))
    }

    @Test
    fun `onOrderFilled — filledQty 키와 instrument 마스터 이름으로 매매일지를 생성한다`() {
        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "code" to "038110",
                "side" to "BUY",
                "filledQty" to 5,
                "executedPrice" to "1000",
                "executedAt" to Instant.now().epochSecond,
                "instrumentName" to "038110",
            )
        whenever(instrumentJpaRepository.findById("038110"))
            .thenReturn(Optional.of(InstrumentJpaEntity(code = "038110", name = "에코플라스틱", market = "KOSDAQ", type = "STOCK")))
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_FILLED, 101L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderFilled(makeRecord(KafkaTopics.Order.FILLED, objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        val storedPayload = objectMapper.readValue(entry.payload, Map::class.java)
        assertThat(entry.instrumentName).isEqualTo("에코플라스틱")
        assertThat(entry.summary).contains("에코플라스틱 매수 5주")
        assertThat(BigDecimal(storedPayload["executedQty"].toString())).isEqualByComparingTo(BigDecimal("5"))
        assertThat(BigDecimal(storedPayload["executedQuantity"].toString())).isEqualByComparingTo(BigDecimal("5"))
    }

    @Test
    fun `onOrderFilled — strategy signal context를 payload에 보강한다`() {
        val signal =
            SignalJpaEntity(
                id = 404L,
                strategyId = 77L,
                userId = "12345678",
                code = "005930",
                interval = "1h",
                ts = Instant.parse("2026-05-14T05:30:00Z"),
                side = "BUY",
                strength = 82,
                reason = "종가가 20이평을 상향 돌파",
                price = BigDecimal("75000"),
                ruleSignal = "MA_CROSS_UP",
            )
        val strategy =
            StrategyJpaEntity(
                id = 77L,
                userId = 12345678L,
                name = "ma-cross",
                nameKo = "이평 돌파",
                dslEntry = "cross(close, sma(20))",
                dslExit = "cross_down(close, sma(20))",
                tf = "1h",
            )
        whenever(signalJpaRepository.findById(404L)).thenReturn(Optional.of(signal))
        whenever(strategyJpaRepository.findById(77L)).thenReturn(Optional.of(strategy))

        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "signalId" to 404L,
                "strategyId" to 77L,
                "code" to "005930",
                "side" to "BUY",
                "executedQty" to 100,
                "executedPrice" to "75000",
                "executedAt" to Instant.now().epochSecond,
            )
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_FILLED, 101L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderFilled(makeRecord(KafkaTopics.Order.FILLED, objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.summary).contains("전략: 이평 돌파")
        val storedPayload = objectMapper.readValue(entry.payload, Map::class.java)
        assertThat(storedPayload["strategyDisplayName"]).isEqualTo("이평 돌파")
        assertThat(storedPayload["signalReason"]).isEqualTo("종가가 20이평을 상향 돌파")
        assertThat(storedPayload["ruleSignal"]).isEqualTo("MA_CROSS_UP")
    }

    @Test
    fun `멱등성 UQ conflict 재처리 — saveEntry null 반환 시 skip (INSERT 0건)`() {
        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "code" to "005930",
                "side" to "BUY",
                "executedQty" to 100,
                "executedPrice" to "75000",
                "executedAt" to Instant.now().epochSecond,
            )
        // UQ 충돌 — saveEntry null 반환
        whenever(journalRepository.saveEntry(any())).thenReturn(null)

        // 1회 처리
        sut.onOrderFilled(makeRecord(KafkaTopics.Order.FILLED, objectMapper.writeValueAsString(payload)))
        // 2회 처리 (재처리 시뮬레이션)
        sut.onOrderFilled(makeRecord(KafkaTopics.Order.FILLED, objectMapper.writeValueAsString(payload)))

        // saveEntry 2회 호출, null 반환 → log.warn only (예외 0)
        verify(journalRepository, org.mockito.kotlin.times(2)).saveEntry(any())
    }
}
