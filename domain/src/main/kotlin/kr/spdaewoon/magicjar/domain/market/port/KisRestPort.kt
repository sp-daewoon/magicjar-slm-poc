package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent

/**
 * KIS REST API 포트 (ADR 0019·0046 정합).
 * 구현은 api 모듈 adapter/kis에서 (PR-6 T-ONBOARD).
 * PR-9b market-data: fetchPrice 확장 (dispatch §4-1 LOCK).
 */
interface KisRestPort {
    fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle>

    /**
     * KIS REST 현재가 단건 조회 (FHKST01010100 inquire-price).
     * polling fallback 및 Redis warm-up 용도.
     * @param userId 계정 사용자 ID (KIS 토큰 조회)
     * @param code   종목코드 (6자리)
     */
    fun fetchPrice(
        userId: String,
        code: String,
    ): TickEvent
}
