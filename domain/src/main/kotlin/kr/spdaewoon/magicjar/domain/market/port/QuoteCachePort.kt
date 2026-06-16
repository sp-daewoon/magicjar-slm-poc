package kr.spdaewoon.magicjar.domain.market.port

import java.math.BigDecimal

/**
 * 실시간 호가 캐시 포트 (Redis 어댑터 위임).
 * tick 데이터의 최신값 조회 — 구현은 api 모듈 adapter에서.
 */
interface QuoteCachePort {
    fun getLatestPrice(code: String): BigDecimal?

    fun putLatestPrice(
        code: String,
        price: BigDecimal,
    )
}
