package kr.spdaewoon.magicjar.domain.instrument.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import java.time.LocalDate

/**
 * 종목 마스터 영속 포트 (ADR 0017).
 * PR-11b 보강: findByActiveTrue·findActiveByCodeIn·markDelisted·searchByPgTrgm 추가.
 * PR-21b 보강: findActiveTradeable·countByMarket·findByMarketCapDesc·findByKospi200 추가 (add-only).
 */
interface InstrumentRepository {
    fun findByCode(code: String): Instrument?

    fun findActiveByMarket(market: InstrumentMarket): List<Instrument>

    /** 활성 종목 전체 조회 (Redis 인덱스 rebuild 용도). */
    fun findByActiveTrue(): List<Instrument>

    /** 종목코드 목록으로 활성 종목 조회 (sync diff 용도). */
    fun findActiveByCodeIn(codes: Collection<String>): List<Instrument>

    /**
     * 상장폐지 처리 — DELETE 금지 (ADR 0017 §2-2 생존자 편향 차단).
     * delisted_at = today, active = false UPDATE.
     */
    fun markDelisted(
        code: String,
        delistedAt: LocalDate,
    )

    /**
     * DB pg_trgm fallback 검색 (Redis miss 시 폴백).
     * design §6-5: query 정규화 + limit + market 필터.
     */
    fun searchByPgTrgm(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult>

    fun upsert(instrument: Instrument): Instrument

    fun upsertAll(instruments: List<Instrument>): Int

    // ==========================================================================
    // PR-21b add-only 신규 메서드 — T-SC·T-IND screening 지원
    // ==========================================================================

    /**
     * 매매 가능한 활성 종목 목록.
     * 조건: active=true AND trading_halt=false AND liquidation=false.
     * T-SC universe 필터링 + 매매 가드 1차 차단.
     */
    fun findActiveTradeable(): List<Instrument>

    /**
     * market별 활성 종목 수 집계.
     * 반환: Map<"KOSPI"|"KOSDAQ"|..., count>
     */
    fun countByMarket(): Map<String, Long>

    /**
     * 시가총액 내림차순 종목 목록.
     * T-SC universe — 대형주 screening.
     *
     * @param limit 최대 결과 수
     */
    fun findByMarketCapDesc(limit: Int): List<Instrument>

    /**
     * KOSPI200 편입 종목 목록.
     * T-SC universe — 기준 universe 조회.
     */
    fun findByKospi200(): List<Instrument>

    /**
     * 활성 일반/우선주 조회 (KIS scrt_grp_cls_code = 'ST').
     * 사용자 검색 인덱스 rebuild 용도 — ETF·ETN·REIT·DR·WARRANT 제외, 보통주+우선주 포함.
     * type=PREFERRED는 prst_cls_code 분기 결과로 securities_group은 ST로 동일.
     */
    fun findActiveStocks(): List<Instrument>
}
