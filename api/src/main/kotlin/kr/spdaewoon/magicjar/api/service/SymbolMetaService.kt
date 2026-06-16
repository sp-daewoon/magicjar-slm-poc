package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SymbolDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 종목 메타 + 현재가 조립 서비스 (spec §3-2).
 *
 * 2026-06-13 가격 source LOCK 정정 (사용자 dogfooding evidence — 현대건설):
 * - Redis quote:last 는 실시간 보조 캐시다. TTL/read gate 를 5분으로 제한하고 stale quote 는 가격·등락 source 에서 제외한다.
 * - quote 가 신선하면 WS/실시간 tick 을 최우선 사용한다.
 * - quote 가 없거나 오래됐으면 KIS PriceMetrics(J/KRX 스냅샷, 5분 meta cache)를 candle 보다 우선한다.
 * - KIS 도 실패했을 때만 DB candle 최신 row 로 fallback 한다.
 *
 * 데이터 우선순위:
 * 0. instrument 테이블 1순위: name·market·industry(sector)
 * 1. 신선한 Redis quote:last → 장중/시간외 실시간 tick source.
 * 2. KIS PriceMetrics(J/KRX) → 상세 진입/장외/장전 스냅샷 source (5분 TTL cache).
 * 3. candle DB 1d 최신 row → KIS 실패 시 fallback.
 * 4. KIS 호출 실패 + candle 부재 시 503.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class SymbolMetaService(
    private val redisTemplate: StringRedisTemplate,
    private val priceMetricsPort: KisRestPriceMetricsPort,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SymbolMetaService::class.java)

    private val metaCacheTtl: Duration = Duration.ofMinutes(5)

    companion object {
        /**
         * stale 시간 경과 임계 — asOf 가 현재 시각 기준 본 임계보다 오래되면 stale=true.
         * QA 첫 임무 evidence (2026-05-21 23:33 KST): asOf=15:19 KST 8시간 stale인데 stale=false 응답 결함.
         */
        internal val staleThreshold: Duration = Duration.ofMinutes(30)

        /** quote:last 가격 source 신뢰 임계. Redis TTL 과 같은 5분 — 초과 시 quote 는 없는 값처럼 취급한다. */
        internal val quoteFreshThreshold: Duration = Duration.ofMinutes(5)

        /**
         * NXT 야간 일봉 휴리스틱 임계 — 극저거래량 row만 제외한다.
         * 30% 임계는 LG전자 2026-05-21 정상 일봉(전일 대비 26.97%)까지 버려
         * 전일종가가 하루 더 밀리는 회귀를 만들었다. 명백한 5% 미만 row만 보수적으로 skip.
         */
        internal val nxtVolumeRatioThreshold: BigDecimal = BigDecimal("0.05")

        /** KRX 정규장 운영시간 (KST) — 본 시간 외 candle 최신 row 가 의심 분포면 fallback. */
        internal val krxOpen: LocalTime = LocalTime.of(9, 0)
        internal val krxClose: LocalTime = LocalTime.of(15, 30)
        internal val kst: ZoneId = ZoneId.of("Asia/Seoul")
    }

    fun getSymbol(code: String): SymbolDto {
        // 0. instrument 테이블 1순위 (PR-11b 정정 — design §11-8)
        // market='UNKNOWN' 또는 부재 → KIS price metrics fallback
        val instrumentEntity =
            try {
                instrumentJpaRepository.findById(code).orElse(null)
            } catch (e: Exception) {
                log.debug("instrument 조회 실패: code={} err={}", code, e.message)
                null
            }
        val instrumentName = instrumentEntity?.name?.takeIf { instrumentEntity.market != "UNKNOWN" }
        val instrumentMarket = instrumentEntity?.market?.takeIf { it != "UNKNOWN" }
        val instrumentSector = instrumentEntity?.sector
        // PR-21b: 신규 KIS-first 필드 추출 (V5 sync 전: null/false graceful)
        val instrumentType = instrumentEntity?.type?.takeIf { it != "UNKNOWN" }
        val instrumentSecuritiesGroup = instrumentEntity?.securitiesGroup
        val instrumentTradingHalt = instrumentEntity?.tradingHalt ?: false
        val instrumentManagementStock = instrumentEntity?.managementStock ?: false
        val instrumentIndustryMedium = instrumentEntity?.industryMedium
        val instrumentMarketCapPrev = instrumentEntity?.marketCapPrev
        val instrumentBasePrice = instrumentEntity?.basePrice?.toPlainString()
        val instrumentPrevVolume = instrumentEntity?.prevVolume

        // 1. candle DB 1d 최근 5 row (ts DESC) → NXT 야간 일봉 휴리스틱 제외
        //    현재 REST 일봉 수집은 J(KRX) 단일로 잠겨 있지만, 과거 UN/NXT 잔재 row와 운영 전환 중 오염을 방어한다.
        //    QA 첫 임무 evidence (2026-05-21): PR #597 휴리스틱 `curVol * 10 < nextVol` 회귀.
        //    SK하이닉스 2026-05-21 candle close=1,918,000 vol=4,850,226 (NXT 의심) ↔ baseline 2026-05-19 vol=7.8M.
        //    현행: 5% 미만 극저거래량 row만 제외 — `curVol < nextVol * 0.05`.
        //    근본 해결(candle venue 컬럼 도입)은 backlog §opt-B (P1).
        val rawCandles =
            try {
                candleJpaRepository.findByCodeAndIntervalLatest(code, "1d", PageRequest.of(0, 5))
            } catch (e: Exception) {
                log.warn("candle 1d 조회 실패: code={} err={}", code, e.message)
                emptyList()
            }
        val normalCandles = filterNxtNightCandles(rawCandles, code)
        val candleLatest = normalCandles.getOrNull(0)
        val candlePrev = normalCandles.getOrNull(1)

        // 장중 가드 (2026-06-10 실측 결함 fix): 오늘 1d 봉은 R2(20:05) 적재 전까지 DB에 없으므로
        // 장중 candleLatest = "어제 봉"이다. 이를 당일 시/고/저/거래량·전일종가 source로 쓰면
        // 헤더가 전일값으로 표시된다(캔들 차트는 #882 seed 경로로 당일 정상 — 헤더만 어긋나는 증상).
        // → candleLatest가 '오늘(KST)' 봉일 때만 당일 source 자격, 아니면 KIS 당일 metrics 우선.
        val kstZone = java.time.ZoneId.of("Asia/Seoul")
        val candleLatestIsToday =
            candleLatest?.ts?.atZone(kstZone)?.toLocalDate() == java.time.LocalDate.now(kstZone)
        // 당일 봉 자격이 있는 candle만 시/고/저/거래량에 사용
        val intradayCandle = if (candleLatestIsToday) candleLatest else null

        // 2. Redis quote:last:{code} — 장중 실시간 변동 (NXT 오염 검사 LOCK)
        //    source ∈ {"live", "live-unified", "ws", "tick", ...} 정규장 출처만 허용. "nxt-*" 등 NXT 오염 source는 무시.
        val quoteJson = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code))
        val quoteMap: Map<String, Any?> =
            if (quoteJson != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(quoteJson, Map::class.java) as Map<String, Any?>
                } catch (e: Exception) {
                    log.warn("quote:last 파싱 실패: code={} err={}", code, e.message)
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        val rawQuoteSource = quoteMap["source"]?.toString() ?: "polling"
        val rawQuoteChangeMetrics = resolveQuoteChangeMetrics(quoteMap)
        val isNxtTainted = rawQuoteSource.contains("nxt", ignoreCase = true) && rawQuoteChangeMetrics == null
        val rawQuoteTs = if (isNxtTainted) null else quoteMap["ts"]?.toString()
        val rawQuoteAsOf = parseQuoteTs(rawQuoteTs)
        val staleFromCache =
            if (isNxtTainted) false else quoteMap["stale"]?.toString()?.toBooleanStrictOrNull() ?: false
        val quoteFresh = !isNxtTainted && !staleFromCache && isQuoteFresh(rawQuoteAsOf)
        val quoteChangeMetrics = rawQuoteChangeMetrics.takeIf { quoteFresh }
        val quoteCurrentPrice = if (quoteFresh) quoteMap["price"]?.toString() else null
        val quoteTs = rawQuoteTs.takeIf { quoteFresh }

        // 2. Redis meta:{code} 5분 cache — PER·PBR·시총 등
        val metaJson = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.symbolMeta(code))
        val metrics: PriceMetrics? =
            if (metaJson != null) {
                try {
                    objectMapper.readValue(metaJson, PriceMetrics::class.java)
                } catch (e: Exception) {
                    log.debug("meta cache 파싱 실패: code={}", code)
                    null
                }
            } else {
                null
            }

        // 3. KIS REST 호출 — meta cache miss 시 (PER·PBR·시총·종목명 등)
        // instrument 테이블에 정확한 데이터가 있어도 PER·PBR 등은 KIS price metrics 필요
        val resolvedMetrics: PriceMetrics? =
            metrics ?: run {
                val userId = currentUserId()
                if (userId != null) {
                    try {
                        val fresh = priceMetricsPort.fetchPriceMetrics(userId, code)
                        // Redis meta cache 저장 (5분 TTL)
                        try {
                            redisTemplate.opsForValue().set(
                                RedisKeyRegistry.Market.symbolMeta(code),
                                objectMapper.writeValueAsString(fresh),
                                metaCacheTtl,
                            )
                        } catch (e: Exception) {
                            log.warn("meta cache 저장 실패: code={} err={}", code, e.message)
                        }
                        fresh
                    } catch (e: Exception) {
                        log.warn("KIS price metrics 실패: code={} err={}", code, e.message)
                        null
                    }
                } else {
                    log.warn("userId 없음 — KIS price metrics 호출 불가: code={}", code)
                    null
                }
            }

        // 가격 LOCK 우선순위:
        //  1. 신선한 Redis quote:last with priceChange/changeRate/changeSign (가격·등락 단일 source)
        //  2. 신선한 Redis quote:last price only
        //  3. KIS PriceMetrics(J/KRX, meta 5분 cache) — 상세 진입/장외/장전 권위 스냅샷
        //  4. candle DB 1d 최신 close (KIS 실패 시 fallback)
        val candleCurrentPrice = candleLatest?.closeRaw?.toPlainString()
        val currentPrice =
            quoteChangeMetrics?.price?.toPlainString()
                ?: quoteCurrentPrice
                ?: resolvedMetrics?.currentPrice?.toPlainString()
                ?: candleCurrentPrice

        val price =
            currentPrice
                ?: resolvedMetrics?.previousClose?.toPlainString()
                ?: throw org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "종목 데이터를 가져올 수 없습니다",
                )

        // 전일종가: tick 등락 필드가 있으면 tick 기준으로 복원, 없으면 candle DB 기준.
        // candleLatest가 오늘 봉이면 2번째 row(어제)가 전일종가, 어제 봉이면 그 자신의 close가 전일종가
        // (기존 candlePrev 고정은 장중에 '그제 종가'를 전일종가로 쓰던 결함).
        val prevCloseFromCandle = if (candleLatestIsToday) candlePrev?.closeRaw else candleLatest?.closeRaw
        val prevClose = quoteChangeMetrics?.previousClose ?: resolvedMetrics?.previousClose ?: prevCloseFromCandle
        val priceDecimal = price.toBigDecimalOrNull()

        // 전일대비·등락률 — quote/KIS/candle 순서로 선택된 price·previousClose 기준 계산.
        val change =
            if (quoteChangeMetrics != null) {
                quoteChangeMetrics.signedChange.toPlainString()
            } else if (priceDecimal != null && prevClose != null) {
                (priceDecimal - prevClose).toPlainString()
            } else {
                resolvedMetrics?.priceChange?.toPlainString() ?: "0"
            }
        val changeRate =
            if (quoteChangeMetrics != null) {
                quoteChangeMetrics.signedChangeRate
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
            } else if (priceDecimal != null && prevClose != null && prevClose != BigDecimal.ZERO) {
                (priceDecimal - prevClose)
                    .divide(prevClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
            } else {
                resolvedMetrics?.changeRate?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00"
            }

        // stale 판정은 "버린 quote"가 아니라 실제 선택한 source 기준이다.
        // stale quote 를 버리고 KIS snapshot 을 썼다면 stale=false, KIS 도 실패해 candle fallback 이면 stale=true.
        val usedCandleFallback = quoteCurrentPrice == null && quoteChangeMetrics == null && resolvedMetrics == null
        val stale =
            when {
                quoteFresh -> false
                resolvedMetrics != null -> false
                else -> staleFromCache || usedCandleFallback || (rawQuoteAsOf != null && isQuoteExpired(rawQuoteAsOf))
            }
        val source =
            when {
                quoteFresh -> rawQuoteSource
                resolvedMetrics != null -> "polling"
                else -> "candle-1d"
            }

        // name·market·industry: instrument 테이블 1순위 (design §11-8)
        // instrument 부재 또는 market='UNKNOWN'이면 KIS price metrics fallback
        val resolvedName = instrumentName ?: resolvedMetrics?.name
        val resolvedMarket = instrumentMarket ?: resolvedMetrics?.market
        val resolvedIndustry = instrumentSector ?: resolvedMetrics?.industry

        return SymbolDto(
            code = code,
            name = resolvedName,
            market = resolvedMarket,
            industry = resolvedIndustry,
            currentPrice = price,
            previousClose = prevClose?.toPlainString() ?: "0",
            change = change,
            changeRate = changeRate,
            // 고·저·시·거래량: KIS snapshot 우선. KIS 부재 시에만 '오늘' 1d 봉(intradayCandle)을 fallback 으로 쓴다.
            // 어제 봉 fallback은 두지 않는다 — 전일값을 당일로 표시하던 결함(2026-06-10) 재발 방지, 부재 시 null(정직 표시).
            high = resolvedMetrics?.high?.toPlainString() ?: intradayCandle?.highRaw?.toPlainString(),
            low = resolvedMetrics?.low?.toPlainString() ?: intradayCandle?.lowRaw?.toPlainString(),
            open = resolvedMetrics?.open?.toPlainString() ?: intradayCandle?.openRaw?.toPlainString(),
            volume = resolvedMetrics?.volume ?: intradayCandle?.volume,
            marketCap = resolvedMetrics?.marketCap?.toPlainString(),
            per = resolvedMetrics?.per?.toPlainString(),
            pbr = resolvedMetrics?.pbr?.toPlainString(),
            eps = resolvedMetrics?.eps?.toPlainString(),
            bps = resolvedMetrics?.bps?.toPlainString(),
            asOf =
                quoteTs ?: java.time.Instant
                    .now()
                    .toString(),
            source = source,
            stale = stale,
            // PR-21b: KIS-first instrument 신규 필드 (V5 sync 전: null/false graceful)
            type = instrumentType,
            securitiesGroup = instrumentSecuritiesGroup,
            tradingHalt = instrumentTradingHalt,
            managementStock = instrumentManagementStock,
            industryMedium = instrumentIndustryMedium,
            marketCapPrev = instrumentMarketCapPrev,
            basePrice = instrumentBasePrice,
            prevVolume = instrumentPrevVolume,
        )
    }

    fun currentUserId(): String? =
        try {
            userAccountJpaRepository.findAll().firstOrNull()?.userId
        } catch (e: Exception) {
            log.warn("userId 조회 실패: {}", e.message)
            null
        }

    /**
     * NXT 야간 일봉 필터 — 최신 row volume 이 직전 row volume 의 NXT_VOLUME_RATIO_THRESHOLD(5%) 미만이면 skip.
     *
     * 추가 가드 (QA 첫 임무 §6 옵션 A-3):
     *   candle ts(KST 변환) 이 KRX 정규장 운영시간 외(09:00 ~ 15:30 KST)에 있고
     *   직전 정상 row(vol >= 100K) 와 비교해도 NXT 의심이면 skip.
     *
     * @param candles ts DESC 정렬 raw candle row (최신 → 과거)
     * @param code 종목 코드 (로그용)
     * @return NXT 야간 row 제거한 정상 candle list (ts DESC 유지)
     */
    internal fun filterNxtNightCandles(
        candles: List<CandleJpaEntity>,
        code: String,
    ): List<CandleJpaEntity> {
        val validCandles = candles.filter { it.closeRaw != null && (it.volume ?: 0L) > 0L }
        return buildList {
            for (i in validCandles.indices) {
                val cur = validCandles[i]
                val next = validCandles.getOrNull(i + 1)
                if (isNxtNightSuspect(cur, next)) {
                    log.info(
                        "NXT 야간 candle skip: code={} ts={} vol={} nextVol={}",
                        code,
                        cur.ts,
                        cur.volume,
                        next?.volume,
                    )
                } else {
                    add(cur)
                }
            }
        }
    }

    /**
     * 단일 candle row 가 NXT 야간 row 인지 판정.
     *  - next 부재면 비교 불가 → 정상 처리.
     *  - next.volume 이 0 이면 비교 무의미 → 정상 처리.
     *  - cur.volume < next.volume * 0.05 이면 NXT 의심 (PR #597 회귀 fix — 5% 임계).
     */
    private fun isNxtNightSuspect(
        cur: CandleJpaEntity,
        next: CandleJpaEntity?,
    ): Boolean {
        if (next == null) return false
        val curVol = cur.volume ?: 0L
        val nextVol = next.volume ?: 0L
        if (nextVol <= 0L) return false
        val curVolBd = BigDecimal.valueOf(curVol)
        val nextVolBd = BigDecimal.valueOf(nextVol)
        val threshold = nextVolBd.multiply(nxtVolumeRatioThreshold)
        return curVolBd < threshold
    }

    /**
     * quote:last.ts (ISO8601 String) → Instant 파싱. 부재·실패 시 null.
     */
    private fun parseQuoteTs(ts: String?): Instant? =
        ts?.let {
            try {
                Instant.parse(it)
            } catch (e: Exception) {
                log.debug("quote ts 파싱 실패: ts={} err={}", it, e.message)
                null
            }
        }

    /**
     * asOf 가 현재 시각 기준 STALE_THRESHOLD(30분) 보다 오래됐는지 판정.
     * asOf 부재 시 false (다른 stale 분기에서 처리).
     */
    internal fun isAsOfStale(
        asOf: Instant?,
        now: Instant = Instant.now(),
    ): Boolean = asOf?.let { it.isBefore(now.minus(staleThreshold)) } ?: false

    internal fun isQuoteFresh(
        asOf: Instant?,
        now: Instant = Instant.now(),
    ): Boolean = asOf?.let { !isQuoteExpired(it, now) } ?: false

    internal fun isQuoteExpired(
        asOf: Instant,
        now: Instant = Instant.now(),
    ): Boolean = asOf.isBefore(now.minus(quoteFreshThreshold))

    /**
     * KRX 정규장 운영시간(KST 09:00 ~ 15:30) 내인지 판정.
     * candle ts 가 일봉 자정 정규화(00:00 KST)면 본 판정은 false.
     * 향후 분봉·체결 시계열에서 운영시간 가드 시 재사용.
     */
    internal fun isKrxRegularHours(
        ts: Instant,
        zone: ZoneId = kst,
    ): Boolean {
        val localTime = ts.atZone(zone).toLocalTime()
        return !localTime.isBefore(krxOpen) && !localTime.isAfter(krxClose)
    }
}
