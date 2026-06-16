package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 도메인 전용 Either-like result (ADR 0068 §14-1 시그니처 LOCK).
 *
 * §14-3 TradingModeGate · §14-5 KisOrderHttpAdapter 반환형 LOCK.
 *
 * 좌(left) = 실패 ([DenyReason]) · 우(right) = 성공.
 *
 * arrow-kt 의존성 회피 (domain 모듈 0 외부 의존성 정합 — ADR 0004).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
sealed class OrderResult<out L, out R> {
    data class Left<out L>(
        val value: L,
    ) : OrderResult<L, Nothing>()

    data class Right<out R>(
        val value: R,
    ) : OrderResult<Nothing, R>()

    val isLeft: Boolean get() = this is Left
    val isRight: Boolean get() = this is Right

    fun leftOrNull(): L? = (this as? Left)?.value

    fun rightOrNull(): R? = (this as? Right)?.value

    inline fun <T> fold(
        ifLeft: (L) -> T,
        ifRight: (R) -> T,
    ): T =
        when (this) {
            is Left -> ifLeft(value)
            is Right -> ifRight(value)
        }
}
