package kr.spdaewoon.magicjar.api.controller.account

import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import java.math.BigDecimal

/**
 * 계좌 잔고 응답 DTO — T-ACCT (ADR 0081 §2-1 + spec §4-3 LOCK).
 *
 * KIS inquire-balance output2 11 필드 매핑 (한글 의미 inline KDoc).
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합 — 컨트롤러·메서드·파라미터·DTO).
 *
 * KIS-first raw fetch 출처 (헌법 §3 LOCK):
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 */
@Schema(description = "계좌 잔고 응답 — KIS inquire-balance output2 11 필드")
data class AccountBalanceResponse(
    @field:Schema(description = "예수금 총금액 (dnca_tot_amt)", example = "10000000.00")
    val cashBalance: BigDecimal,
    @field:Schema(description = "D+1 예수금 (nxdy_excc_amt)", example = "10000000.00")
    val cashD1: BigDecimal,
    @field:Schema(description = "D+2 예수금 — 실가용 (prvs_rcdl_excc_amt)", example = "10000000.00")
    val cashD2: BigDecimal,
    @field:Schema(description = "총 평가금액 — 자산 (tot_evlu_amt)", example = "12000000.00")
    val totalEvaluation: BigDecimal,
    @field:Schema(description = "순자산금액 (nass_amt)", example = "12000000.00")
    val netAssetValue: BigDecimal,
    @field:Schema(description = "매입금액 합계 (pchs_amt_smtl_amt)", example = "1900000.00")
    val totalPurchaseAmount: BigDecimal,
    @field:Schema(description = "평가금액 합계 — 보유 종목 평가금 합 (evlu_amt_smtl_amt)", example = "2000000.00")
    val totalHoldingEvaluation: BigDecimal,
    @field:Schema(description = "평가손익 합계 (evlu_pfls_smtl_amt)", example = "100000.00")
    val totalProfitLoss: BigDecimal,
    @field:Schema(description = "자산증감액 — 전일 대비 (asst_icdc_amt)", example = "50000.00", nullable = true)
    val assetIncreaseDecrease: BigDecimal? = null,
    @field:Schema(description = "자산증감 수익률 % (asst_icdc_erng_rt)", example = "0.42", nullable = true)
    val assetIncreaseDecreaseRate: BigDecimal? = null,
    @field:Schema(description = "조회 시각 (ISO-8601 UTC)", example = "2026-05-18T05:30:00Z")
    val fetchedAt: String,
) {
    companion object {
        fun from(
            balance: AccountBalance,
            fetchedAtIso: String,
        ): AccountBalanceResponse =
            AccountBalanceResponse(
                cashBalance = balance.cashBalance,
                cashD1 = balance.cashD1,
                cashD2 = balance.cashD2,
                totalEvaluation = balance.totalEvaluation,
                netAssetValue = balance.netAssetValue,
                totalPurchaseAmount = balance.totalPurchaseAmount,
                totalHoldingEvaluation = balance.totalHoldingEvaluation,
                totalProfitLoss = balance.totalProfitLoss,
                assetIncreaseDecrease = balance.assetIncreaseDecrease,
                assetIncreaseDecreaseRate = balance.assetIncreaseDecreaseRate,
                fetchedAt = fetchedAtIso,
            )
    }
}

/**
 * 보유 종목 응답 DTO — T-ACCT (KIS output1 향상판).
 */
@Schema(description = "보유 종목 — KIS output1 매핑")
data class AccountHoldingResponse(
    @field:Schema(description = "종목코드 6자리 (pdno)", example = "005930")
    val code: String,
    @field:Schema(description = "종목명 (prdt_name)", example = "삼성전자")
    val name: String,
    @field:Schema(description = "보유 수량 (hldg_qty)", example = "10")
    val quantity: Int,
    @field:Schema(description = "평균 매수가 (pchs_avg_pric)", example = "70000.00")
    val avgPrice: BigDecimal,
    @field:Schema(description = "현재가 (prpr)", example = "71000.00")
    val currentPrice: BigDecimal,
    @field:Schema(description = "평가금액 (evlu_amt)", example = "710000.00")
    val evaluationAmount: BigDecimal,
    @field:Schema(description = "평가손익 (evlu_pfls_amt)", example = "10000.00")
    val profitLoss: BigDecimal,
    @field:Schema(description = "수익률 % (evlu_pfls_rt)", example = "1.43")
    val profitLossRate: BigDecimal,
) {
    companion object {
        fun from(holding: AccountHolding): AccountHoldingResponse =
            AccountHoldingResponse(
                code = holding.code,
                name = holding.name,
                quantity = holding.quantity,
                avgPrice = holding.avgPrice,
                currentPrice = holding.currentPrice,
                evaluationAmount = holding.evaluationAmount,
                profitLoss = holding.profitLoss,
                profitLossRate = holding.profitLossRate,
            )
    }
}

/**
 * 계좌 요약 응답 DTO — Dashboard 헤더용 (spec §4-3 LOCK).
 */
@Schema(description = "계좌 요약 — Dashboard 헤더용")
data class AccountSummaryResponse(
    @field:Schema(description = "총 평가금액 (자산)", example = "12000000.00")
    val totalEvaluation: BigDecimal,
    @field:Schema(description = "예수금 D+2 (실가용)", example = "10000000.00")
    val cashD2: BigDecimal,
    @field:Schema(description = "평가손익 합계", example = "100000.00")
    val totalProfitLoss: BigDecimal,
    @field:Schema(description = "수익률 (%)", example = "0.83")
    val profitLossRate: BigDecimal,
    @field:Schema(description = "보유 종목 수", example = "3")
    val holdingsCount: Int,
    @field:Schema(description = "조회 시각 (ISO-8601 UTC)", example = "2026-05-18T05:30:00Z")
    val fetchedAt: String,
)
