package kr.spdaewoon.magicjar.domain.instrument.corpaction.port

import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import java.time.Instant
import java.time.LocalDate

/**
 * CorpAction 영속 port (ADR 0018 §2-1 + ADR 0051 §2 — default fun 함정 회피).
 *
 * 구현: batch 모듈 CorpActionRepositoryAdapter (JPA + nativeQuery ON CONFLICT upsert).
 * 주의: JpaRepository body-fun 금지 (ADR 0051) — 인터페이스 추상 메서드만.
 */
interface CorpActionRepository {
    /**
     * ON CONFLICT upsert (UNIQUE: instrument_code + type + ex_date + source).
     * 멱등 — 동일 이벤트 재처리 안전.
     */
    fun upsert(action: CorpAction): CorpAction

    /**
     * 종목 코드 + 권리락일 기준 조회 (중복 source 가능 — KIS + DART 양쪽 보존).
     */
    fun findByCodeAndExDate(
        code: String,
        exDate: LocalDate,
    ): List<CorpAction>

    /**
     * 미적용(appliedAt = null) corp_action 전체 조회 — CandleAdjustmentJob 입력.
     */
    fun findPendingApplied(): List<CorpAction>

    /**
     * CandleAdjustmentJob 완료 시 appliedAt 갱신.
     */
    fun markApplied(
        id: Long,
        at: Instant,
    )
}
