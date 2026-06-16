package kr.spdaewoon.magicjar.api.persistence.journal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * journal_note JPA 엔티티 — Flyway V29 정합 (ADR 0072 §3-1).
 *
 * trading_journal(id) FK (ON DELETE CASCADE).
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Entity
@Table(name = "journal_note")
class JournalNoteJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "journal_entry_id", nullable = false)
    val journalEntryId: Long,
    @Column(name = "note_text", nullable = false, columnDefinition = "text")
    val noteText: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
