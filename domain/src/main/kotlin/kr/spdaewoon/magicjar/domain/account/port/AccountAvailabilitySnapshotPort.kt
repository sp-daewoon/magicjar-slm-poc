package kr.spdaewoon.magicjar.domain.account.port

import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 주문 hot path 가 참조하는 계좌 availability snapshot.
 *
 * source 는 KIS `inquire-balance` 단일 호출이다. output2 의 현금성 금액과 output1 의 종목별
 * `ord_psbl_qty`를 같이 보존해 BUY/SELL 주문 가능성 판단을 KIS 추가 호출 없이 수행한다.
 */
interface AccountAvailabilitySnapshotPort {
    fun read(
        userId: String,
        mode: TradingMode,
    ): AccountAvailabilitySnapshot?

    fun putIfNewer(snapshot: AccountAvailabilitySnapshot): Boolean
}

data class AccountAvailabilitySnapshot(
    val userId: String,
    val mode: TradingMode,
    val cashBalance: BigDecimal,
    val cashD1: BigDecimal,
    val cashD2: BigDecimal,
    val netAssetValue: BigDecimal,
    val holdingQtyByCode: Map<String, Int>,
    val sellableQtyByCode: Map<String, Int>,
    val versionMillis: Long,
    val refreshedAt: Instant,
    val source: String = "inquire-balance",
) {
    fun isFresh(
        now: Instant,
        maxAge: Duration,
    ): Boolean = Duration.between(refreshedAt, now) <= maxAge

    fun sellableQty(code: String): Int? = sellableQtyByCode[code]

    companion object {
        fun from(
            balance: KisAccountBalance,
            mode: TradingMode,
            versionMillis: Long,
            refreshedAt: Instant,
        ): AccountAvailabilitySnapshot =
            AccountAvailabilitySnapshot(
                userId = balance.userId,
                mode = mode,
                cashBalance = balance.balance.cashBalance,
                cashD1 = balance.balance.cashD1,
                cashD2 = balance.balance.cashD2,
                netAssetValue = balance.balance.netAssetValue,
                holdingQtyByCode = balance.holdings.associate { it.code to it.quantity },
                sellableQtyByCode =
                    balance.holdings
                        .mapNotNull { holding ->
                            holding.orderableQuantity?.let { holding.code to it }
                        }.toMap(),
                versionMillis = versionMillis,
                refreshedAt = refreshedAt,
            )
    }
}
