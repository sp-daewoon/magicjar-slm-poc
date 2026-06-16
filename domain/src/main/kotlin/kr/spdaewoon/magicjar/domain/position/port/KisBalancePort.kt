package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.user.AccountType
import java.math.BigDecimal

/**
 * KIS 잔고 조회 포트 (ADR 0042 Bootstrap STAGE 0-1).
 * 구현: consumer 모듈 KisBalanceAdapter (KIS REST inquire-balance).
 * Spring 의존 0 — 순수 도메인 인터페이스.
 */
interface KisBalancePort {
    fun fetchBalance(
        userId: String,
        accountType: AccountType,
    ): KisBalance
}

/**
 * KIS 잔고 조회 결과.
 * @param holdings 보유 종목 목록 (0건 = 보유 없음 — 정상).
 */
data class KisBalance(
    val userId: String,
    val accountType: AccountType,
    val holdings: List<KisHolding>,
    val fetchedAt: java.time.Instant,
)

/**
 * KIS 보유 종목 (output1 response 항목).
 * @param code pdno — 6자리 종목 코드 (KIS 필드)
 * @param quantity hldg_qty — 보유 수량 (정수)
 * @param avgPrice pchs_avg_pric — 평균 매수 단가
 */
data class KisHolding(
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
)
