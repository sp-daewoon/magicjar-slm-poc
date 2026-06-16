package kr.spdaewoon.magicjar.domain.instrument.corpaction

/**
 * 기업 행위(Corporate Action) 유형 (ADR 0018 §2-1 정합).
 *
 * KIS ksdinfo 6 endpoint 매핑:
 *   STOCK_SPLIT / REVERSE_SPLIT → /uapi/domestic-stock/v1/ksdinfo/rev-split (tr_id: HHKDB669105C0)
 *   BONUS_ISSUE                 → /uapi/domestic-stock/v1/ksdinfo/bonus-issue (tr_id: HHKDB669101C0)
 *   RIGHTS_OFFERING             → /uapi/domestic-stock/v1/ksdinfo/paidin-capin (tr_id: HHKDB669100C0)
 *   CASH_DIVIDEND / STOCK_DIVIDEND → /uapi/domestic-stock/v1/ksdinfo/dividend (tr_id: HHKDB669102C0)
 *   MERGER / SPINOFF            → /uapi/domestic-stock/v1/ksdinfo/merger-split (tr_id: HHKDB669104C0)
 *   CAPITAL_DECREASE            → /uapi/domestic-stock/v1/ksdinfo/cap-dcrs (tr_id: HHKDB669106C0)
 *
 * DART pblntf_ty 매핑:
 *   B (주요사항보고서) → 액면분할·합병·증자 결정 공시
 *   D (지분공시)       → 배당 결정 공시
 */
enum class CorpActionType {
    /** 액면분할 (예: 1:50 분할) — KIS HHKDB669105C0 rev-split */
    STOCK_SPLIT,

    /** 액면병합 (예: 50:1 병합) — KIS HHKDB669105C0 rev-split */
    REVERSE_SPLIT,

    /** 유상증자 — KIS HHKDB669100C0 paidin-capin */
    RIGHTS_OFFERING,

    /** 무상증자 — KIS HHKDB669101C0 bonus-issue */
    BONUS_ISSUE,

    /** 현금배당 — KIS HHKDB669102C0 dividend */
    CASH_DIVIDEND,

    /** 주식배당 — KIS HHKDB669102C0 dividend */
    STOCK_DIVIDEND,

    /** 합병 — KIS HHKDB669104C0 merger-split */
    MERGER,

    /** 분할(인적·물적) — KIS HHKDB669104C0 merger-split */
    SPINOFF,

    /** 자본감소 — KIS HHKDB669106C0 cap-dcrs */
    CAPITAL_DECREASE,

    /** 상장폐지 공시 — DART list pblntf_ty=B */
    DELISTING_NOTICE,
}
