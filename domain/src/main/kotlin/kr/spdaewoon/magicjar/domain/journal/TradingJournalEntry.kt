package kr.spdaewoon.magicjar.domain.journal

import java.math.BigDecimal
import java.time.Instant

/**
 * 매매일지 도메인 엔티티 — 실제 체결 복기장.
 *
 * 신규 row는 실제 매수/매도 체결 ORDER_FILLED만 저장한다.
 * 과거 이벤트 타입 row는 DB에 남아 있을 수 있으나 서비스 조회 기본값에서 제외한다.
 * Flyway V29 `trading_journal` 테이블 정합.
 *
 * - [sourceTable]: 원본 영속 테이블명 (order_execution)
 * - [sourceId]: 원본 PK
 * - [payload]: 체결 원본 이벤트 + 전략/시그널 근거 JSON 문자열
 * - [summary]: 한국어 요약 ("체결: 삼성전자 매수 100주 @ 75,000원")
 * - [realizedPnl]: 매도 체결에서 계산 가능할 때 값 존재
 *
 * 멱등성 UQ `ux_trading_journal_source` (source_table + source_id + event_type):
 * Kafka at-least-once 재처리 시 동일 source 중복 INSERT 회피.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class TradingJournalEntry(
    val id: Long? = null,
    val userId: Long,
    val eventType: JournalEventType,
    val instrumentCode: String? = null,
    val instrumentName: String? = null,
    val occurredAt: Instant,
    val summary: String,
    val payload: String, // JSON 문자열 (JSONB 영속)
    val sourceTable: String,
    val sourceId: Long,
    val realizedPnl: BigDecimal? = null,
    val createdAt: Instant = Instant.now(),
    val notes: List<JournalNote> = emptyList(),
)
