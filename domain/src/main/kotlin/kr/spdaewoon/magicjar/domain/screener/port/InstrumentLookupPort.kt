package kr.spdaewoon.magicjar.domain.screener.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter

/**
 * Screener universe 조회 port (ADR 0071 §2-2 B-1 + spec §2-2 + §10).
 *
 * `instrument` 테이블(T-IM1 baseline)을 단일 출처로 universe 후보 조회.
 *
 * KIS endpoint 호출 0 LOCK — 영속 데이터 reuse (사용자 메모리 `feedback_kis_first_policy` 정합).
 *
 * Phase 1: bean 부재 시 Phase 2 미합류 환경 — consumer 모듈 ScreenerEngineImpl 에서 `ObjectProvider`로 graceful empty list fallback.
 * Phase 2: api 모듈 `InstrumentLookupAdapter`가 `InstrumentRepository` baseline (ADR 0017 PR-21b `findActiveTradeable` 등) reuse 구현.
 *
 * ### 헌법 §6 정합
 * abstract fun 단독 — body 가진 default fun 0건 LOCK.
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 port + 구현체는 LLM provider import 0 — grep 검증 의무.
 */
interface InstrumentLookupPort {
    /**
     * 필터 매트릭스 적용한 active 종목 list 반환.
     *
     * 필수 매트릭스 (ADR 0071 §2-2 LOCK):
     * - `active = true` LOCK (생존자 편향 cover)
     * - `delistedAt IS NULL` LOCK
     * - `market IN filter.markets`
     * - `type IN filter.types`
     * - `marketCap >= filter.marketCapMin` (옵션)
     * - `avgVolume5d * close >= filter.avgVolumeMin` (옵션)
     *
     * @param filter universe 필터
     * @return active 종목 list (empty 가능 — instrument 미수집 환경)
     */
    fun findActive(filter: ScreenerFilter): List<Instrument>
}
