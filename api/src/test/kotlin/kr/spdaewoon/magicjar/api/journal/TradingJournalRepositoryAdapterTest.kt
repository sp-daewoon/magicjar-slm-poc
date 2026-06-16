package kr.spdaewoon.magicjar.api.journal

import kr.spdaewoon.magicjar.api.persistence.journal.JournalNoteJpaRepository
import kr.spdaewoon.magicjar.api.persistence.journal.TradingJournalJpaEntity
import kr.spdaewoon.magicjar.api.persistence.journal.TradingJournalJpaRepository
import kr.spdaewoon.magicjar.api.persistence.journal.TradingJournalRepositoryAdapter
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import java.math.BigDecimal
import java.time.Instant

/**
 * TradingJournalRepositoryAdapterTest — 4건 GREEN (spec §3-1 + 헌법 §6 검증).
 *
 * - saveEntry 정상 INSERT
 * - saveEntry UQ 충돌 시 null 반환
 * - findEntryById userId 격리 조회
 * - findEntryById userId mismatch → null 반환
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class TradingJournalRepositoryAdapterTest {
    private val journalJpaRepository: TradingJournalJpaRepository = mock()
    private val noteJpaRepository: JournalNoteJpaRepository = mock()
    private val sut = TradingJournalRepositoryAdapter(journalJpaRepository, noteJpaRepository)

    private fun makeJpaEntity(
        id: Long = 1001L,
        userId: Long = 12345678L,
    ): TradingJournalJpaEntity =
        TradingJournalJpaEntity(
            id = id,
            userId = userId,
            eventType = "ORDER_FILLED",
            instrumentCode = "005930",
            instrumentName = "삼성전자",
            occurredAt = Instant.now(),
            summary = "체결: 삼성전자 매수 100주 @ 75,000원",
            payload = """{"code":"005930"}""",
            sourceTable = "order_execution",
            sourceId = 101L,
            realizedPnl = BigDecimal("150000"),
        )

    private fun makeDomainEntry(userId: Long = 12345678L): TradingJournalEntry =
        TradingJournalEntry(
            userId = userId,
            eventType = JournalEventType.ORDER_FILLED,
            instrumentCode = "005930",
            occurredAt = Instant.now(),
            summary = "체결 테스트",
            payload = """{"code":"005930"}""",
            sourceTable = "order_execution",
            sourceId = 101L,
        )

    @Test
    fun `saveEntry — 정상 INSERT 반환`() {
        val domainEntry = makeDomainEntry()
        val savedEntity = makeJpaEntity()
        whenever(journalJpaRepository.save(any())).thenReturn(savedEntity)

        val result = sut.saveEntry(domainEntry)

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(1001L)
        assertThat(result.eventType).isEqualTo(JournalEventType.ORDER_FILLED)
    }

    @Test
    fun `saveEntry — UQ 충돌 시 null 반환 (INSERT IGNORE 멱등성)`() {
        val domainEntry = makeDomainEntry()
        whenever(journalJpaRepository.save(any()))
            .thenThrow(DataIntegrityViolationException("UQ 충돌 테스트"))

        val result = sut.saveEntry(domainEntry)

        assertThat(result).isNull()
    }

    @Test
    fun `findEntryById — userId 격리 조회`() {
        val entity = makeJpaEntity(id = 1001L, userId = 12345678L)
        whenever(journalJpaRepository.findByIdAndUserId(1001L, 12345678L)).thenReturn(entity)
        whenever(noteJpaRepository.findByJournalEntryIdOrderByCreatedAtAsc(1001L)).thenReturn(emptyList())

        val result = sut.findEntryById(1001L, 12345678L)

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(1001L)
        assertThat(result.userId).isEqualTo(12345678L)
    }

    @Test
    fun `findEntryById — userId mismatch 시 null 반환`() {
        whenever(journalJpaRepository.findByIdAndUserId(1001L, 99999L)).thenReturn(null)

        val result = sut.findEntryById(1001L, 99999L)

        assertThat(result).isNull()
        verify(noteJpaRepository, never()).findByJournalEntryIdOrderByCreatedAtAsc(any())
    }

    @Test
    fun `findEntries — 날짜 필터가 없어도 동적 Specification 조회를 사용한다`() {
        val entity = makeJpaEntity(id = 1001L, userId = 12345678L)
        whenever(
            journalJpaRepository.findAll(
                any<Specification<TradingJournalJpaEntity>>(),
                any<Pageable>(),
            ),
        ).thenReturn(PageImpl(listOf(entity)))
        whenever(noteJpaRepository.findByJournalEntryIdOrderByCreatedAtAsc(1001L)).thenReturn(emptyList())

        val result =
            sut.findEntries(
                userId = 12345678L,
                from = null,
                to = null,
                eventTypes = listOf(JournalEventType.ORDER_FILLED),
                instrumentCode = null,
                instrumentQuery = null,
                instrumentCodes = null,
                page = 0,
                size = 20,
            )

        assertThat(result).hasSize(1)
        assertThat(result[0].eventType).isEqualTo(JournalEventType.ORDER_FILLED)
        verify(journalJpaRepository).findAll(any<Specification<TradingJournalJpaEntity>>(), any<Pageable>())
    }
}
