package kr.spdaewoon.magicjar.domain.journal

import java.time.Instant

/**
 * 매매일지 사용자 메모 도메인 엔티티 — ADR 0072 §9-2 Phase 1 산출물.
 *
 * [TradingJournalEntry] 1:N 관계. 사용자 회고 자유 텍스트.
 * Flyway V29 `journal_note` 테이블 정합.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class JournalNote(
    val id: Long? = null,
    val userId: Long,
    val journalEntryId: Long,
    val noteText: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
