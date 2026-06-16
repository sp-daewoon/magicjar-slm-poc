package kr.spdaewoon.magicjar.domain.journal.port

import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.JournalNote
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import java.time.Instant

/**
 * 매매일지 영속 포트 (헌법 §6 abstract fun only — default fun 0건 LOCK).
 *
 * ADR 0072 §9-2 Phase 1 산출물. 어댑터: TradingJournalRepositoryAdapter (api 모듈).
 *
 * domain 모듈은 Spring 의존 0 (ADR 0004 헥사고날 정합).
 * Pageable/Page는 api 모듈 어댑터 계층에서 처리.
 * 도메인 포트는 순수 List + offset/limit 파라미터 사용.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface TradingJournalRepository {
    /**
     * 매매일지 entry 영속. 멱등성 UQ 충돌 시 graceful skip (null 반환).
     * UQ: (source_table, source_id, event_type)
     */
    fun saveEntry(entry: TradingJournalEntry): TradingJournalEntry?

    /**
     * 사용자 매매일지 시계열 페이징 조회 (occurred_at DESC).
     * [from], [to] 미지정 시 전체 기간.
     * [eventTypes] 미지정 시 모든 타입.
     * [instrumentCode] 미지정 시 모든 종목.
     * [instrumentQuery]는 종목명/코드 조각 검색 원문, [instrumentCodes]는 종목 마스터에서 매칭된 코드 후보.
     * [page] 0-base 페이지 번호, [size] 페이지당 row 수.
     */
    fun findEntries(
        userId: Long,
        from: Instant?,
        to: Instant?,
        eventTypes: List<JournalEventType>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        instrumentCodes: Set<String>?,
        page: Int,
        size: Int,
    ): List<TradingJournalEntry>

    /**
     * 총 entry 수 (페이징 totalElements 산정용).
     */
    fun countEntries(
        userId: Long,
        from: Instant?,
        to: Instant?,
        eventTypes: List<JournalEventType>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        instrumentCodes: Set<String>?,
    ): Long

    /**
     * entry 단건 조회 (notes 포함). userId 격리 검증 포함.
     * userId mismatch 시 null 반환.
     */
    fun findEntryById(
        id: Long,
        userId: Long,
    ): TradingJournalEntry?

    /**
     * 사용자 메모 영속.
     */
    fun saveNote(note: JournalNote): JournalNote

    /**
     * entry에 속한 메모 목록 조회.
     */
    fun findNotesByEntryId(entryId: Long): List<JournalNote>
}
