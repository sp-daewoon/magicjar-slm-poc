package kr.spdaewoon.magicjar.domain.instrument.corpaction.port

import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import java.time.LocalDate

/**
 * KIS ksdinfo 6 endpoint 조회 port (ADR 0018 §2-5 + KIS-first 정합).
 *
 * KIS open-trading-api raw 출처 (헌법 §3 — 자체 추론 0):
 *
 *  1. 배당 (dividend)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/dividend
 *     tr_id: HHKDB669102C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_dividend/ksdinfo_dividend.py
 *
 *  2. 액면분할/병합 (rev-split)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/rev-split
 *     tr_id: HHKDB669105C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_rev_split/ksdinfo_rev_split.py
 *
 *  3. 합병/분할 (merger-split)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/merger-split
 *     tr_id: HHKDB669104C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_merger_split/ksdinfo_merger_split.py
 *
 *  4. 무상증자 (bonus-issue)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/bonus-issue
 *     tr_id: HHKDB669101C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_bonus_issue/ksdinfo_bonus_issue.py
 *
 *  5. 유상증자 (paidin-capin)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/paidin-capin
 *     tr_id: HHKDB669100C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_paidin_capin/ksdinfo_paidin_capin.py
 *
 *  6. 자본감소 (cap-dcrs)
 *     URL : /uapi/domestic-stock/v1/ksdinfo/cap-dcrs
 *     tr_id: HHKDB669106C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_cap_dcrs/ksdinfo_cap_dcrs.py
 *
 * 공통 params (raw 정합):
 *   cts    : 연속조회 키
 *   gb1    : 시장구분 (0=전체, 1=KOSPI, 2=KOSDAQ)
 *   f_dt   : 조회 시작일 (YYYYMMDD)
 *   t_dt   : 조회 종료일 (YYYYMMDD)
 *   sht_cd : 종목코드 (빈문자열 = 전체)
 *
 * 구현: batch 모듈 KisKsdInfoAdapter
 */
interface KisKsdInfoFetcher {
    /**
     * 배당 조회 (CASH_DIVIDEND·STOCK_DIVIDEND).
     * tr_id: HHKDB669102C0
     */
    fun fetchDividend(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 액면분할/병합 조회 (STOCK_SPLIT·REVERSE_SPLIT).
     * tr_id: HHKDB669105C0
     */
    fun fetchRevSplit(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 합병/분할 조회 (MERGER·SPINOFF).
     * tr_id: HHKDB669104C0
     */
    fun fetchMergerSplit(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 무상증자 조회 (BONUS_ISSUE).
     * tr_id: HHKDB669101C0
     */
    fun fetchBonusIssue(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 유상증자 조회 (RIGHTS_OFFERING).
     * tr_id: HHKDB669100C0
     */
    fun fetchPaidinCapin(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 자본감소 조회 (CAPITAL_DECREASE).
     * tr_id: HHKDB669106C0
     */
    fun fetchCapDcrs(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>
}
