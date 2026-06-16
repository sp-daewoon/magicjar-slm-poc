package kr.spdaewoon.magicjar.domain.screener.port

import kr.spdaewoon.magicjar.domain.screener.ScreenerCriteria
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult

/**
 * Screener engine port (ADR 0071 §2-1 A-1 + spec §2-2).
 *
 * universe × DSL → ScreenerRunResult 단일 진입점.
 *
 * Phase 1: consumer 모듈 `ScreenerEngineImpl` 구현.
 * Phase 2: api 모듈 `ScreenerService`가 Redis cache wrap + 분산락 SETNX + 옵션 영속 시 본 port 호출.
 *
 * ### 헌법 §6 정합
 * abstract fun 단독 — body 가진 default fun 0건 LOCK (사용자 함정 회피).
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 port + 구현체는 LLM provider import 0 — grep 검증 의무.
 */
interface ScreenerEngine {
    /**
     * universe iterate + DSL 평가 + ranker → 결과 + 랭킹 + skip 매트릭스.
     *
     * 동기 inline 평가 (ADR 0071 §2-1 A-1 LOCK).
     *
     * @param criteria 평가 요청 (userId · strategyId · filter · interval · limit)
     * @return ScreenerRunResult (universeSize·evaluatedCount·matchedCount·skippedCount·skippedReasons·matches·elapsedMs)
     */
    fun screen(criteria: ScreenerCriteria): ScreenerRunResult
}
