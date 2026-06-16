package kr.spdaewoon.magicjar.domain.instrument.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument

/**
 * KIS 종목 마스터 fetch 포트 (ADR 0017 §2-3 — T-IM1 PR-11a add-only).
 *
 * 구현체: consumer/.../infrastructure/kis/KisInstrumentMasterAdapter
 * KIS-first 출처: KIS open-trading-api repo
 *   KOSPI: stocks_info/kis_kospi_code_mst.py
 *   KOSDAQ: stocks_info/kis_kosdaq_code_mst.py
 *   docs/operations/kis-api-reference.md §6
 */
interface KisInstrumentMasterPort {
    /**
     * KIS 종목 마스터 전체 fetch — KOSPI + KOSDAQ + KONEX + ETF·ETN.
     * 실패 시 throw (호출자가 catch + graceful fallback 결정).
     */
    fun fetchAll(): List<Instrument>
}
