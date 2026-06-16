package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.time.Instant

/**
 * KIS 과거 1분봉 range 조회 포트.
 *
 * `KisRestPort.fetchCandles(ONE_MIN)`은 cold-start/장기 backfill용 lookback 정책을 따른다.
 * SymbolDetail 재진입처럼 짧은 gap만 복구하는 user path는 이 포트로 범위를 명시한다.
 */
interface KisOneMinuteCandleRangePort {
    /**
     * [from] 이상 [to] 이하의 KIS 1m candle을 ASC로 반환한다.
     *
     * KIS upstream/rate-limit/credential 실패는 empty result로 숨기지 않고 예외로 전파한다.
     * 호출자는 data readiness를 fail-closed로 닫아야 한다.
     */
    fun fetchOneMinuteCandlesBetween(
        code: String,
        from: Instant,
        to: Instant,
    ): List<Candle>
}
