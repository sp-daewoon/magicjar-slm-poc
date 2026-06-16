package kr.spdaewoon.magicjar.api.persistence.journal

import org.springframework.data.jpa.repository.JpaRepository

/**
 * journal_note Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건 LOCK.
 * body 가진 helper는 [JournalNoteRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface JournalNoteJpaRepository : JpaRepository<JournalNoteJpaEntity, Long> {
    /** entry ID로 메모 목록 조회 (created_at ASC) */
    fun findByJournalEntryIdOrderByCreatedAtAsc(journalEntryId: Long): List<JournalNoteJpaEntity>
}
