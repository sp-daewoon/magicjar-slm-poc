package kr.spdaewoon.magicjar.consumer.application.indicator

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import kotlinx.datetime.Instant as KtInstant

/**
 * IndicatorSource 진입 직전 1d candles list에 Redis live today bar를 합류 — 자본 보호 critical (hotfix 2026-05-15).
 *
 * ### 배경
 * 자동매매 시그널 생성기는 closing된 boundary candle을 evaluator로 흘리지만,
 * 백테스트/실시간 평가 모두 IndicatorEngine.calculate에 도달하는 candles list 마지막 row가
 * DB에서 마감되지 않은 오늘 1d bar 일 경우 stale close 값을 사용해 BUY/SELL 잘못 발생 risk.
 *
 * frontend 차트 + REST 응답은 [CandleQueryService] 가 Redis `market:candle:session:code:{code}:interval:1d` 합류 완료 (PR #425·#426).
 * strategy-engine IndicatorSource 진입 candles 도 동일 정책 적용해야 sync 일치.
 *
 * ### 합류 패턴 (CandleQueryService line 100~146 1:1 정합)
 * - candles 마지막 row.ts > redisTs → Redis bar add (오늘 row 없음)
 * - candles 마지막 row.ts == redisTs → Redis bar replace (stale close 덮어쓰기)
 * - candles 마지막 row.ts < redisTs → Redis bar add
 *
 * ### 범위
 * - 1d 인터벌만 (1m·3m·5m·30m·1h 등은 boundary closing 시점 명확 — 평가 시 이미 closed)
 * - Redis 부재 / 파싱 실패 시 graceful no-op (DB 그대로 반환)
 *
 * ### 영역 LOCK
 * consumer/application/indicator 모듈 단일. domain port 추가 0건 (옵션 B는 별도 backlog).
 *
 * AI 호출 0 — 룰 5 + 헌법 §6 정합.
 */
@Component
class LiveCandleMerger(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(LiveCandleMerger::class.java)

    /**
     * candles list 마지막 row가 오늘 1d bar이면 Redis 값으로 정규화 (replace 또는 add).
     *
     * @return 정규화된 새 list. 1d 가 아니거나 Redis 부재 시 원본 그대로 반환.
     */
    fun merge(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return candles
        val last = candles.last()
        if (last.interval != CandleInterval.ONE_DAY) return candles

        val code = last.code
        val redisCandle = readCandleToday(code) ?: return candles

        val redisTs = redisCandle.ts
        val lastDbTs = last.ts

        return when {
            redisTs > lastDbTs -> {
                // 오늘 row 가 DB 에 아직 없음 — Redis bar add
                candles + redisCandle.copy(code = code, interval = CandleInterval.ONE_DAY)
            }
            redisTs == lastDbTs -> {
                // DB 마지막 row 가 오늘 1d (stale close) — Redis 값으로 replace
                candles.dropLast(1) + redisCandle.copy(code = code, interval = CandleInterval.ONE_DAY)
            }
            else -> {
                // redisTs < lastDbTs — 비정상 (보통 redis stale, DB 가 더 최신) — graceful 원본 반환
                log.trace(
                    "LiveCandleMerger: redisTs < lastDbTs — skip code={} redisTs={} lastDbTs={}",
                    code,
                    redisTs,
                    lastDbTs,
                )
                candles
            }
        }
    }

    /**
     * Redis `market:candle:session:code:{code}:interval:1d` Hash 읽어 Candle 변환.
     * 파싱 실패·Redis 미응답 시 null (graceful fallback).
     */
    private fun readCandleToday(code: String): Candle? {
        val key = RedisKeyRegistry.Market.candleToday1d(code)
        return try {
            val hash = redisTemplate.opsForHash<String, String>().entries(key)
            if (hash.isEmpty()) return null
            if (hash["seeded"] != "true") {
                log.debug("LiveCandleMerger: unseeded today candle skip code={}", code)
                return null
            }
            parseCandleTodayHash(hash, code)
        } catch (e: Exception) {
            log.debug("LiveCandleMerger Redis read 실패: key={} err={}", key, e.message)
            null
        }
    }

    /**
     * Redis Hash → Candle 변환.
     *
     * Redis Hash 필드 포맷은 [KisWebSocketBridge.updateCandleToday] 와 정합:
     *   - ts: ISO8601 string (예: "2026-05-15T00:00:00+09:00") 또는 epochSecond 문자열
     *   - open, high, low, close: BigDecimal 호환 plain string
     *   - volume: Long string
     */
    private fun parseCandleTodayHash(
        hash: Map<String, String>,
        code: String,
    ): Candle? =
        try {
            val tsStr = hash["ts"] ?: return null
            val ts = parseTs(tsStr) ?: return null
            val open = hash["open"]?.toBigDecimalOrNull() ?: return null
            val high = hash["high"]?.toBigDecimalOrNull() ?: return null
            val low = hash["low"]?.toBigDecimalOrNull() ?: return null
            val close = hash["close"]?.toBigDecimalOrNull() ?: return null
            val volume = hash["volume"]?.toLongOrNull() ?: 0L

            Candle(
                id = null,
                code = code,
                interval = CandleInterval.ONE_DAY,
                ts = ts,
                openRaw = open,
                highRaw = high,
                lowRaw = low,
                closeRaw = close,
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = volume,
                source = "KIS",
            )
        } catch (e: Exception) {
            log.debug("LiveCandleMerger parse 실패: code={} err={}", code, e.message)
            null
        }

    /**
     * ts string 파싱. ISO8601 우선, fallback epochSecond.
     * KisWebSocketBridge.updateCandleToday 는 ISO8601 (LocalDate.atStartOfDay(KST).toInstant().toString()) 포맷 사용.
     */
    private fun parseTs(value: String): KtInstant? =
        try {
            KtInstant.parse(value)
        } catch (_: Exception) {
            value.toLongOrNull()?.let { KtInstant.fromEpochSeconds(it) }
        }

    @Suppress("unused")
    private fun zero(): BigDecimal = BigDecimal.ZERO
}
