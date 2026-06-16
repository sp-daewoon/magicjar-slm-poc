package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 차트 캔들 페이지네이션 + 응답 limit 매트릭스 properties (ADR 0093 §3-1 LOCK).
 *
 * 권위: ADR 0093 §결정 LOCK 매트릭스 — chart-curator spec PR #609 §5 정합.
 *
 * 키 매트릭스 (timeframe별 KIS `inquire-daily-itemchartprice` cursor 페이지네이션 안전 한도):
 *
 * | Interval | maxPages | cover 봉 (×100) | 사유 |
 * |----------|----------|------------------|------|
 * | `1m`     | 48       | 4800             | KIS 분봉 distinct 30 거래일 LOCK 이내 |
 * | `3m`·`5m`·`15m`·`30m`·`90m`·`1h` | 38 | 3800 | 합산 가공 정합 |
 * | `4h`     | 20       | 2000             | 고타임프레임 합산 |
 * | `12h`    | 12       | 1200             | NXT 통합 세션 전체 합산 |
 * | `1d`     | 50       | 5000             | ≈ 20년 일봉 (일반 증권앱 표준) |
 * | `1wk`    | 20       | 2000             | ≈ 38년 주봉 |
 * | `1mo`    | 12       | 1200             | ≈ 100년 월봉 |
 * | `1y`     | 6        | 600              | ≈ 600년 (사실상 무제한) |
 *
 * Default fallback (config 부재 시): 모든 interval **6** (회귀 안전 — 기존 hardcode 값 유지).
 *
 * Endpoint limit cap:
 *   - `GET /api/symbols/{code}/candles?limit=` 사용자 입력 cap (1 ≤ limit ≤ maxResponseLimit).
 *   - default 5000 (ADR 0093 §3-1 일봉 max cap 정합).
 *
 * Override 패턴 (env override 1:1):
 *   ```
 *   magicjar:
 *     candle:
 *       max-pages-by-interval:
 *         "1d": 50
 *         "1m": 48
 *         ...
 *       max-response-limit: 5000
 *       one-minute:
 *         default-lookback-trading-days: 126
 *         max-lookback-trading-days: 252
 *         max-pages-per-day: 8
 *   ```
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 `Map<String, Int>` 단일 키 노출 → timeframe 추가/조정 시 ADR 0093 매트릭스 1:1 갱신
 *   - 🔄 hardcode 2 위치(`DAILY_MAX_PAGES`·`MAX_PAGES`) 제거 → DRY + 단일 진입점
 *   - ✨ `getMaxPagesFor(interval)` 확장 fun으로 default fallback safe-access — sealed 패턴 회피(매트릭스 변경 빈도 낮음)
 */
@ConfigurationProperties(prefix = "magicjar.candle")
data class ChartCandleProperties(
    /**
     * Interval → cursor 페이지네이션 max page 수 매트릭스.
     *
     * 누락 키 fallback = [defaultMaxPages] (회귀 안전 — 기존 hardcode 6).
     *
     * 키 형식: CandleInterval.code (`1m`·`3m`·`5m`·`15m`·`30m`·`90m`·`1h`·`4h`·`12h`·`1d`·`1wk`·`1mo`·`1y`).
     */
    val maxPagesByInterval: Map<String, Int> = emptyMap(),
    /**
     * `maxPagesByInterval`에 없는 interval에 대한 fallback 값.
     * default 6 = 기존 hardcode `DAILY_MAX_PAGES`·`MAX_PAGES` 값 (회귀 안전).
     */
    val defaultMaxPages: Int = 6,
    /**
     * `GET /api/symbols/{code}/candles?limit=` 사용자 입력 cap (≥ 1).
     * default 5000 = ADR 0093 §3-1 일봉 max cap 정합.
     */
    val maxResponseLimit: Int = 5000,
    /**
     * KIS `FHKST03010230` 기반 1m 장기 backfill/recollection 설정.
     */
    val oneMinute: OneMinuteBackfillProperties = OneMinuteBackfillProperties(),
) {
    /**
     * Interval code → max pages 안전 lookup (fallback 정합).
     *
     * @param interval CandleInterval.code (예: "1d"·"1m"·"1wk")
     * @return 매트릭스 명시 값 또는 [defaultMaxPages]
     */
    fun getMaxPagesFor(interval: String): Int = maxPagesByInterval[interval] ?: defaultMaxPages
}

/**
 * 1m backfill/recollection 전용 설정 (ADR 0105).
 */
data class OneMinuteBackfillProperties(
    val defaultLookbackTradingDays: Int = 126,
    val maxLookbackTradingDays: Int = 252,
    val maxPagesPerDay: Int = 8,
) {
    fun normalizedLookbackTradingDays(): Int {
        val maxTradingDays = maxLookbackTradingDays.coerceAtLeast(1)
        return defaultLookbackTradingDays.coerceIn(1, maxTradingDays)
    }

    fun normalizedMaxPagesPerDay(): Int = maxPagesPerDay.coerceIn(1, MAX_PAGES_PER_DAY_CAP)

    companion object {
        const val MAX_PAGES_PER_DAY_CAP: Int = 8
    }
}
