package kr.spdaewoon.magicjar.domain.account

import kr.spdaewoon.magicjar.domain.user.AccountType
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS inquire-balance output2 응답 — 계좌 잔고 합계 (도메인 DTO).
 *
 * ADR 0081 §2-6 LOCK + spec §3-1 정합. KIS-first raw fetch 출처:
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py (COLUMN_MAPPING)
 *
 * 11 필드 LOCK — 영문 key → 한글 의미 → DTO 필드 매트릭스:
 *
 * | 영문 key             | 한글 의미                 | 필드                       |
 * |---------------------|-------------------------|----------------------------|
 * | dnca_tot_amt        | 예수금 총금액            | cashBalance                |
 * | nxdy_excc_amt       | 익영업일 정산 (D+1 예수금)| cashD1                     |
 * | prvs_rcdl_excc_amt  | 가수도 정산 (D+2 예수금) | cashD2                     |
 * | tot_evlu_amt        | 총 평가금액 (자산)       | totalEvaluation            |
 * | nass_amt            | 순자산금액               | netAssetValue              |
 * | pchs_amt_smtl_amt   | 매입금액 합계            | totalPurchaseAmount        |
 * | evlu_amt_smtl_amt   | 평가금액 합계            | totalHoldingEvaluation     |
 * | evlu_pfls_smtl_amt  | 평가손익 합계            | totalProfitLoss            |
 * | asst_icdc_amt       | 자산증감액 (전일 대비)   | assetIncreaseDecrease      |
 * | asst_icdc_erng_rt   | 자산증감 수익률 (%)      | assetIncreaseDecreaseRate  |
 *
 * Spring 의존 0 — 순수 도메인 data class.
 *
 * mock 합성 0 LOCK (feedback_no_mock_data) — output2 0건이면 호출자 책임 (502 또는 명시적 0 + fetchedAt).
 */
data class AccountBalance(
    /** 예수금 총금액 (dnca_tot_amt) */
    val cashBalance: BigDecimal,
    /** D+1 예수금 (nxdy_excc_amt) */
    val cashD1: BigDecimal,
    /** D+2 예수금 — 실가용 (prvs_rcdl_excc_amt) */
    val cashD2: BigDecimal,
    /** 총 평가금액 — 자산 (tot_evlu_amt) */
    val totalEvaluation: BigDecimal,
    /** 순자산금액 (nass_amt) — CapitalSnapshotProvider 기준 */
    val netAssetValue: BigDecimal,
    /** 매입금액 합계 (pchs_amt_smtl_amt) */
    val totalPurchaseAmount: BigDecimal,
    /** 평가금액 합계 — 보유 종목 평가금 합 (evlu_amt_smtl_amt) */
    val totalHoldingEvaluation: BigDecimal,
    /** 평가손익 합계 (evlu_pfls_smtl_amt) */
    val totalProfitLoss: BigDecimal,
    /** 자산증감액 — 전일 대비 (asst_icdc_amt, 옵션) */
    val assetIncreaseDecrease: BigDecimal? = null,
    /** 자산증감 수익률 % (asst_icdc_erng_rt, 옵션) */
    val assetIncreaseDecreaseRate: BigDecimal? = null,
    /** 조회 시각 */
    val fetchedAt: Instant,
)

/**
 * KIS inquire-balance output1 응답 — 보유 종목 (도메인 DTO, output1 향상판).
 *
 * ADR 0081 §2-6 + spec §4-3 정합.
 *
 * 영문 key 매핑:
 * - pdno            → code (종목코드 6자리)
 * - prdt_name       → name (종목명)
 * - hldg_qty        → quantity (보유 수량)
 * - pchs_avg_pric   → avgPrice (평균 매수가)
 * - prpr            → currentPrice (현재가)
 * - evlu_amt        → evaluationAmount (평가금액)
 * - evlu_pfls_amt   → profitLoss (평가손익)
 * - evlu_pfls_rt    → profitLossRate (수익률 %)
 * - ord_psbl_qty    → orderableQuantity (주문가능수량, null이면 KIS 응답 결손)
 *
 * Spring 의존 0 — 순수 도메인 data class.
 */
data class AccountHolding(
    val code: String,
    val name: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val evaluationAmount: BigDecimal,
    val profitLoss: BigDecimal,
    val profitLossRate: BigDecimal,
    val orderableQuantity: Int? = null,
)

/**
 * KIS inquire-balance 1회 호출 결과 — output1(보유 종목 리스트) + output2(잔고 합계) 통합.
 *
 * spec §4-2 옵션 A 채택 — 동일 endpoint 1회 호출로 두 응답 부분 동시 흡수.
 * Redis cache `account:balance:{userId}:{accountType}` 키 1건에 본 객체 직렬화 (spec §4-1 LOCK).
 */
data class KisAccountBalance(
    val userId: String,
    val accountType: AccountType,
    val balance: AccountBalance,
    val holdings: List<AccountHolding>,
    val fetchedAt: Instant,
)
