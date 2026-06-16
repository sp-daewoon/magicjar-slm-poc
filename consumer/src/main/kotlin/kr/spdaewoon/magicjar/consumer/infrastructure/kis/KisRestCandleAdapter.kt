package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.datetime.Instant as KtInstant

/**
 * KIS REST 캔들 조회 어댑터 (ADR 0019 §7-4 — KIS-first 정책 정합).
 *
 * 지원 interval:
 *   - 1d·1wk·1mo·1y: FHKST03010100 (inquire-daily-itemchartprice) — 4 PERIOD_DIV_CODE 공통 endpoint
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *   - 1m: FHKST03010230 (inquire-time-dailychartprice) — 최대 120건/call, 날짜+시간 cursor, KIS 보관 최대 1년
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * Yahoo Finance 의존성 전면 제거 (2026-05-11 — KIS-only 정책 적용).
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * KIS 500 retry 정책 (§33 — 2026-05-12):
 *   KIS 외부 결함 일시적 부하 통과를 위해 HTTP 500 INTERNAL_SERVER_ERROR 발생 시
 *   최대 3회 exponential backoff (500ms → 1000ms → 2000ms) 재시도.
 *   503/502 등 기타 HTTP 오류는 즉시 실패 (서비스 점검 가능성, 재시도 무의미).
 *   Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미.
 *
 * ⚠ KIS raw 사전 ASC 정규화 매트릭스 (ADR 0086 §11 LOCK — chart-curator skill §5 source-of-truth 정합):
 *   KIS 응답 sort order는 timeframe·endpoint별로 일관되지 않음. adapter layer가 raw 정규화 단일 책임.
 *
 *   | parse 함수 | tr_id | cover timeframe | sort 패턴 | ASC fix |
 *   |-----------|-------|----------------|-----------|---------|
 *   | parseKisDailyResponse | FHKST03010100 | 일봉(D)·주봉(W)·월봉(M)·연봉(Y) 4 PERIOD_DIV_CODE | DESC | PR #537 |
 *   | parseKis1mResponse | FHKST03010230 | 1분봉 (1일치 페이지네이션) | DESC 가능 | PR #537 |
 *
 *   매트릭스 LOCK: 4 timeframe(daily) + 1 timeframe(1m) = **본 어댑터 cover 5 timeframe 전부**가
 *   2 parse 함수 단일 LOCK으로 ASC 정규화 cover. 향후 추가 timeframe(예: 시간외 FHPST02320000)은
 *   별도 parse 함수 추가 + ADR 0086 §11 매트릭스 행 추가 + 본 KDoc 갱신 의무.
 *
 *   ⚠ 2026-06-05 readiness P0-2 — **parse 단위 ASC ≠ fetch 단위 ASC**:
 *     parseKis1mResponse는 페이지 단위로만 ASC 정렬한다. fetchOneMinCandles는 최근일→과거일 +
 *     일자 내 장마감→장시작 역순 페이지네이션으로 페이지를 누적하므로, 페이지 합산 결과(allCandles)는
 *     경계마다 ts가 뒤로 점프하는 비단조 시퀀스가 된다. 따라서 fetch path 3종 모두 **반환 직전 전역
 *     `.distinctBy { ts }.sortedBy { ts }` 정규화 의무** — fetchDailyCandles·fetchOneMinCandlesBetween은
 *     이미 적용, fetchOneMinCandles는 본 fix로 추가(sanity monotonic 41만 줄 폭주 root cause 제거).
 *
 *   evidence: 한국석유(004090) PR #535·#537·#N — KIS DESC 응답 + sanity check reject → 5개월 갭 누적.
 */
@Component
@Primary
class KisRestCandleAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val instrumentRepository: ConsumerInstrumentJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val chartCandleProperties: ChartCandleProperties,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisRestPort,
    KisOneMinuteCandleRangePort,
    KisDailyCandleRangePort {
    private val log = LoggerFactory.getLogger(KisRestCandleAdapter::class.java)

    companion object {
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        private const val TR_ID_DAILY = "FHKST03010100"
        private const val ENDPOINT_DAILY = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"

        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
        private const val TR_ID_1MIN = "FHKST03010230"
        private const val ENDPOINT_1MIN = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"

        private const val SOURCE = "KIS"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val KST_ZONE = ZoneId.of("Asia/Seoul")
        private const val ONE_MINUTE_INITIAL_CURSOR_HOUR = "200000"
        private val ONE_MINUTE_MARKET_OPEN = LocalTime.of(8, 0, 0)
        private val ONE_MINUTE_MARKET_CLOSE = LocalTime.of(20, 0, 0)

        /**
         * KIS HTTP 500 exponential backoff 대기 시간 (ms).
         * §33: 3회 재시도 — 500ms → 1000ms → 2000ms.
         * Rate Limit (ADR 0037): retry는 500 발생 시에만 발동 — 초당 10건 한도 영향 미미.
         */
        private val RETRY_BACKOFF_MS = listOf(500L, 1000L, 2000L)
        private const val RETRY_MAX_ATTEMPTS = 3

        /**
         * KIS inquire-daily-itemchartprice (FHKST03010100) 1회 호출 최대 봉 수.
         *
         * KIS-first raw spec (헌법 §3 의무 인용):
         *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
         *   docstring: "한 번의 호출에 최대 100건"
         *
         * 4 PERIOD_DIV_CODE(D/W/M/Y) 공통 한도. 페이지네이션 미적용 시 30년 backfill 의도가
         * 최근 100건(일봉 약 5개월·주봉 약 2년·월봉 약 8년·연봉 100년)으로 잘림 → regression.
         */
        const val KIS_PAGE_LIMIT: Int = 100

        /**
         * 페이지네이션 안전 한도 default fallback (ADR 0093 §3-1 LOCK · application.yml 미설정 시).
         *
         * **권위 변경 (2026-05-20 backend-core Phase 3 — ADR 0093)**:
         *   기존 `const val MAX_PAGES = 6` 단일 hardcode → [ChartCandleProperties.getMaxPagesFor] timeframe별 매트릭스.
         *
         *   매트릭스 (`magicjar.candle.max-pages-by-interval` — spec PR #609 §5):
         *     1d=50(5000봉·약 20년) / 1wk=20(2000봉·약 38년) / 1mo=12(1200봉·약 100년) / 1y=6(600봉)
         *     1m=48(4800봉) / 5m·15m·30m·1h=38(3800봉)
         *
         *   본 const `DEFAULT_MAX_PAGES`는 회귀 안전 fallback (config 부재 시) — chart-curator
         *   페이지네이션 회귀 가드 테스트 호환성 LOCK.
         *
         * Rate Limit (ADR 0037 초당 10건): 일봉 50 pages × ~100ms = 5초 — 사용자 1명 5초 점유.
         * USER_PATH priority 합류 시 다른 종목 진입 사용자에 영향 미미 (ADR 0037 §2-1 LOCK).
         */
        const val DEFAULT_MAX_PAGES: Int = 6

        @Deprecated(
            message = "ADR 0093 §3-1 LOCK — timeframe별 매트릭스 ChartCandleProperties.getMaxPagesFor 사용. 본 상수는 default fallback 시그니처 유지용.",
            replaceWith = ReplaceWith("DEFAULT_MAX_PAGES"),
        )
        const val MAX_PAGES: Int = DEFAULT_MAX_PAGES

        /** CandleInterval → KIS period_div_code 매핑 (FHKST03010100) */
        private fun toPeriodDivCode(interval: CandleInterval): String =
            when (interval) {
                CandleInterval.ONE_DAY -> "D"
                CandleInterval.ONE_WEEK -> "W"
                CandleInterval.ONE_MONTH -> "M"
                CandleInterval.ONE_YEAR -> "Y"
                else -> throw IllegalArgumentException("KIS REST 미지원 interval: ${interval.code}")
            }
    }

    private data class OneMinuteCursorStep(
        val earliestTime: LocalTime,
        val nextHour: String?,
    )

    /**
     * 다음 페이지 cursor 산정 — `oldestDate - 1day` 단순 패턴 (PR 2cbd4e8 검증된 패턴 1:1).
     *
     * 페이지 경계 중복 방지 — 이전 페이지의 가장 오래된 row가 이미 수집됐으므로
     * 다음 페이지 cursor는 그 row의 직전 영업일이 시작. 단순화하여 1일 차감
     * (영업일 정확도 불필요 — distinctBy boundary dedup이 방어).
     *
     * 함정 #N (보수 페르소나 — 4 timeframe 공통 적용 검증):
     *   - 일봉(D): cursor = lastDate - 1day → KIS가 그 이전 일봉 100건 반환
     *   - 주봉(W): cursor = lastDate - 1day → KIS가 그 이전 주봉 100건 반환 (윈도우 자동 산정)
     *   - 월봉(M)/연봉(Y): 동일 — KIS endpoint는 fid_input_date_2 이전 데이터만 반환하므로
     *     1day 차감으로도 충분히 새 윈도우 확보. 더 큰 차감은 데이터 누락 위험.
     */
    private fun nextCursor(oldestDate: LocalDate): LocalDate = oldestDate.minusDays(1)

    /**
     * ADR 0105 Phase 1 — 1m backfill 대상 거래일 산정.
     *
     * KIS `FHKST03010230`은 날짜+시간 cursor로 과거 분봉을 가져온다. 실제 휴장일은 응답 0건으로
     * 방어하고, 여기서는 주말만 제외해 property 기반 lookback 범위를 안정적으로 만든다.
     */
    private fun oneMinuteTargetDays(anchorDate: LocalDate = LocalDate.now(KST_ZONE)): List<LocalDate> {
        val lookbackTradingDays = chartCandleProperties.oneMinute.normalizedLookbackTradingDays()
        val maxCalendarScanDays = (lookbackTradingDays * 2).coerceAtLeast(lookbackTradingDays + 14)
        val targetDays = mutableListOf<LocalDate>()
        var current = anchorDate
        var checked = 0
        while (targetDays.size < lookbackTradingDays && checked < maxCalendarScanDays) {
            val dow = current.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                targetDays.add(current)
            }
            current = current.minusDays(1)
            checked++
        }
        return targetDays
    }

    private fun oneMinuteMaxPagesPerDay(): Int = chartCandleProperties.oneMinute.normalizedMaxPagesPerDay()

    private fun nextOneMinuteCursor(earliestCandle: Candle): OneMinuteCursorStep {
        val earliestTime =
            java.time.Instant
                .ofEpochSecond(earliestCandle.ts.epochSeconds)
                .atZone(KST_ZONE)
                .toLocalTime()
        if (!earliestTime.isAfter(ONE_MINUTE_MARKET_OPEN)) {
            return OneMinuteCursorStep(earliestTime = earliestTime, nextHour = null)
        }

        val nextTime = earliestTime.minus(1, ChronoUnit.MINUTES)
        return OneMinuteCursorStep(
            earliestTime = earliestTime,
            nextHour = "%02d%02d%02d".format(nextTime.hour, nextTime.minute, nextTime.second),
        )
    }

    /**
     * 종목코드 → KIS fid_cond_mrkt_div_code 결정 — **J(KRX 본시장) 단일** (ADR 0118 amend · 2026-06-09).
     *
     * KIS raw spec (헌법 §3 의무 인용):
     *   FHKST03010230: fid_cond_mrkt_div_code = "J:주식, NX:NXT, UN:통합"
     *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *   FHKST03010100: fid_cond_mrkt_div_code = "J:KRX, NX:NXT, UN:통합"
     *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * ## ★ 근본 원인 — UN/NX 채널 stale 동결 (2026-06-09 REAL 키 raw 실측 evidence)
     *   라이브 dogfooding: 036930·047040·098460 일봉이 2026-02-11 에 동결(주봉도 2/02 동결)되었으나,
     *   같은 종목 1분봉은 오늘(2026-06-09 13:40) 실시간 체결 정상 유입 → 거래정지/데이터부재 아님.
     *   REAL 키로 FHKST03010100 을 **채널별 raw 호출**한 실측:
     *
     *   | code   | J(KRX)            | NX(NXT)        | UN(통합)        |
     *   |--------|-------------------|----------------|-----------------|
     *   | 036930 | **20260609 정상** | 20260211 stale | 20260211 stale  |
     *   | 047040 | **20260609 정상** | -              | 20260211 stale  |
     *   | 098460 | **20260609 정상** | -              | 20260211 stale  |
     *   | 138040 | 20260609          | -              | 20260609        |
     *   | 455180 | 20260609          | -              | 0 rows          |
     *
     *   직전 구현(ADR 0091)은 KOSPI200/KOSDAQ150 멤버에 `["UN","J"]`(UN 우선)을 썼다. KIS 측 결함으로
     *   UN(통합) 일봉이 일부 종목에서 2/11 에 동결되어 있는데, fallback 은 "UN 0건"일 때만 J 로 가므로
     *   UN 이 stale 8행을 주면 그대로 채택하고 J(항상 최신)를 영영 안 본다 → 일부 종목 일봉 영구 동결.
     *
     * ## 결정 (사용자 결재 2026-06-09) — J 단일 primary, UN/NX deprecated
     *   - **항상 `listOf("J")` 반환.** UN/NX 분기·fallback 전면 제거.
     *   - 사유: UN/NX 모두 KIS 측 stale, J(KRX 본시장)만 전 종목 항상 최신·정상. 일봉 OHLC 는 본시장
     *     권위로 충분.
     *   - trade-off 수용: 일봉 거래량에서 NXT 분 제외(본시장 거래량만). 정합성·신뢰성 우선.
     *
     * ADR 0063("market-level UN")·ADR 0091("종목 단위 UN allow-list")은 ADR 0118 amend 로 deprecated.
     */
    private fun resolveMrktDivCode(code: String): String = resolveMrktDivCodeCandidates(code).first()

    /**
     * J(KRX 본시장) 단일 채널 — ADR 0118 amend (상단 실측 evidence 표 참조).
     *
     * 반환은 항상 단일 원소 `["J"]`. fetch 루프(fetchCandles·fetchDailyCandlesInRange 등)의
     * UN→J fallback 분기는 단일 후보이므로 첫 시도에서 종료(no-op fallback). 향후 NXT 권위가
     * 필요해지면(KIS 측 UN/NX stale 해소 후) 본 함수만 교체 + ADR amend 의무.
     *
     * @param code 종목코드 (현재 미사용 — 전 종목 J 고정. 시그니처는 호출부 무변경 LOCK 위해 유지).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun resolveMrktDivCodeCandidates(code: String): List<String> = listOf("J")

    override fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle> {
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val candles =
                if (interval == CandleInterval.ONE_MIN) {
                    fetchOneMinCandles(code, mrktDivCode)
                } else {
                    fetchDailyCandles(code, interval, mrktDivCode)
                }
            if (candles.isNotEmpty() || index == mrktDivCodes.lastIndex) {
                return candles
            }
            log.warn(
                "KIS candle UN 응답 0건 — J fallback 재시도: code={} interval={} mrktDivCode={}",
                code,
                interval.code,
                mrktDivCode,
            )
        }
        return emptyList()
    }

    override fun fetchOneMinuteCandlesBetween(
        code: String,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<Candle> {
        if (from.isAfter(to)) return emptyList()
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val candles = fetchOneMinCandlesBetween(code, mrktDivCode, from, to)
            if (candles.isNotEmpty() || index == mrktDivCodes.lastIndex) {
                return candles
            }
            log.warn(
                "KIS 1m range UN 응답 0건 — J fallback 재시도: code={} from={} to={} mrktDivCode={}",
                code,
                from,
                to,
                mrktDivCode,
            )
        }
        return emptyList()
    }

    /**
     * KIS 1d range 조회 (FHKST03010100, period_div_code=D) — **BACKFILL 버킷** (ADR 0118 R-BF).
     *
     * [fetchDailyCandles] (INGESTION 버킷·cursor=today 고정)와 분리된 R-BF 캠페인 전용 경로.
     * 동일 endpoint·페이지네이션 알고리즘이되 (a) 시작 경계를 호출자 clamp([from])로 명시,
     * (b) acquire 를 BACKFILL 버킷으로 고정(§5-1 LOCK — USER_PATH·INGESTION 침범 0),
     * (c) rate-limit/credential 결함을 [KisDailyRangeUnavailableException]으로 fail-closed 전파한다.
     *
     * KIS-first raw 출처 (헌법 §3):
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * 페이지네이션: cursor=to 부터 역방향, 응답 oldest-1day 로 advance, [from] 도달 또는 응답<100 시 종료.
     * mrkt_div 후보(ADR 0091): UN 우선(NXT 통합 후보) + J fallback.
     */
    override fun fetchDailyCandlesInRange(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        if (from.isAfter(to)) return emptyList()
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val candles = fetchDailyCandlesInRangeForMrktDiv(code, mrktDivCode, from, to)
            if (candles.isNotEmpty() || index == mrktDivCodes.lastIndex) {
                return candles
            }
            log.warn(
                "KIS 1d range(BACKFILL) UN 응답 0건 — J fallback 재시도: code={} from={} to={} mrktDivCode={}",
                code,
                from,
                to,
                mrktDivCode,
            )
        }
        return emptyList()
    }

    /**
     * R-BF 1d range fetch — 단일 mrkt_div 페이지네이션 (BACKFILL 버킷).
     *
     * 결함 전파 (fail-closed):
     *   - REAL credential 부재·토큰 발급 실패·rate-limit(EGW00201/429)·HTTP 오류 → [KisDailyRangeUnavailableException]
     *     (캠페인이 retry/circuit/이연 판단 — 종목 완료 마킹 금지).
     *   - rt_cd=0 정상 응답이나 해당 구간 0건(상장 이전·거래 부재) → 빈 list 반환(uncovered, 예외 아님).
     */
    private fun fetchDailyCandlesInRangeForMrktDiv(
        code: String,
        mrktDivCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: throw KisDailyRangeUnavailableException("KIS 1d range(BACKFILL): REAL credential 없음 — code=$code")
        val userId = cred.id.userId
        if (userId.isBlank()) {
            throw KisDailyRangeUnavailableException("KIS 1d range(BACKFILL): credential userId 빈 문자열 — code=$code DB 결함")
        }
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                throw KisDailyRangeUnavailableException("KIS 1d range(BACKFILL): 토큰 발급 실패 — code=$code", e)
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)
            val startStr = from.format(DATE_FMT)

            val accumulator = mutableListOf<Candle>()
            var cursor: LocalDate = to
            // R-BF 페이지 안전 한도 — 1d 매트릭스(50) 재사용. 10년(~2,450 거래일)/100 ≈ 25 페이지면 충분.
            val maxPages = chartCandleProperties.getMaxPagesFor(CandleInterval.ONE_DAY.code)

            for (page in 0 until maxPages) {
                if (cursor.isBefore(from)) break

                val pageCandles =
                    fetchDailyRangeSinglePageBackfill(
                        code = code,
                        mrktDivCode = mrktDivCode,
                        cursor = cursor,
                        startStr = startStr,
                        accessToken = accessToken,
                        appKeyStr = appKeyStr,
                        secretKeyStr = secretKeyStr,
                    )
                if (pageCandles.isEmpty()) break

                // [from]..[to] 경계 내 봉만 누적 (KIS 가 startStr 이전을 일부 반환할 수 있음).
                accumulator.addAll(
                    pageCandles.filter { c ->
                        val d =
                            java.time.Instant
                                .ofEpochSecond(c.ts.epochSeconds)
                                .atZone(KST_ZONE)
                                .toLocalDate()
                        !d.isBefore(from) && !d.isAfter(to)
                    },
                )

                if (pageCandles.size < KIS_PAGE_LIMIT) break

                val oldestEpoch = pageCandles.minOf { it.ts.epochSeconds }
                val oldestDate =
                    java.time.Instant
                        .ofEpochSecond(oldestEpoch)
                        .atZone(KST_ZONE)
                        .toLocalDate()
                val newCursor = nextCursor(oldestDate)
                if (!newCursor.isBefore(cursor)) break // advance 부재 가드
                if (newCursor.isBefore(from)) break
                cursor = newCursor
            }

            accumulator
                .distinctBy { it.ts.epochSeconds }
                .sortedBy { it.ts.epochSeconds }
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * R-BF 1d 단일 페이지 KIS 호출 (BACKFILL 버킷) — rate-limit/HTTP 결함은 예외 전파(fail-closed).
     *
     * 정상 rt_cd=0 + 0건 응답은 빈 list(uncovered). EGW00201/429/HTTP 오류는 [KisDailyRangeUnavailableException].
     */
    private fun fetchDailyRangeSinglePageBackfill(
        code: String,
        mrktDivCode: String,
        cursor: LocalDate,
        startStr: String,
        accessToken: String,
        appKeyStr: String,
        secretKeyStr: String,
    ): List<Candle> {
        val cursorStr = cursor.format(DATE_FMT)
        var signalSent = false
        var acquired = false
        try {
            // §5-1 LOCK — BACKFILL(BG) 버킷 고정. USER_PATH·INGESTION 절대 침범 0.
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = TR_ID_DAILY)
            acquired = true
            val body =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                            .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                            .path(ENDPOINT_DAILY)
                            // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
                            .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                            .queryParam("fid_input_iscd", code)
                            .queryParam("fid_input_date_1", startStr)
                            .queryParam("fid_input_date_2", cursorStr)
                            .queryParam("fid_period_div_code", "D")
                            .queryParam("fid_org_adj_prc", "0")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", TR_ID_DAILY)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block()
            if (body == null) {
                kisRateLimiterPort.onSuccess()
                signalSent = true
                return emptyList()
            }
            val parsed = parseKisDailyResponse(body, code, CandleInterval.ONE_DAY)
            kisRateLimiterPort.onSuccess()
            signalSent = true
            return parsed
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                throw KisDailyRangeUnavailableException(
                    "KIS 1d range(BACKFILL) Rate Limit — code=$code cursor=$cursorStr status=${e.statusCode.value()}",
                    e,
                )
            }
            log.warn(
                "KIS 1d range(BACKFILL) HTTP 오류: code={} cursor={} status={}",
                code,
                cursorStr,
                e.statusCode.value(),
            )
            throw KisDailyRangeUnavailableException(
                "KIS 1d range(BACKFILL) HTTP 오류 — code=$code cursor=$cursorStr status=${e.statusCode.value()}",
                e,
            )
        } catch (e: KisDailyRangeUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.warn("KIS 1d range(BACKFILL) 오류: code={} cursor={} err={}", code, cursorStr, e.message)
            throw KisDailyRangeUnavailableException("KIS 1d range(BACKFILL) 오류 — code=$code cursor=$cursorStr", e)
        } finally {
            if (acquired && !signalSent) {
                log.warn(
                    "[KisRateLimit] 1d range(BACKFILL) acquire 후 통계 시그널 미발행 — drift risk: code={} cursor={}",
                    code,
                    cursorStr,
                )
            }
        }
    }

    private fun oneMinuteRangeTargetDays(
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<LocalDate> {
        val startDate = from.atZone(KST_ZONE).toLocalDate()
        val endDate = to.atZone(KST_ZONE).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var current = endDate
        while (!current.isBefore(startDate)) {
            val dow = current.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                days.add(current)
            }
            current = current.minusDays(1)
        }
        return days
    }

    private fun oneMinuteRangeMaxPages(
        from: java.time.Instant,
        to: java.time.Instant,
    ): Int {
        val minutes = Duration.between(from, to).toMinutes().coerceAtLeast(0L) + 1L
        val estimatedPages = ((minutes + 119L) / 120L + 1L).toInt()
        return estimatedPages.coerceAtLeast(1).coerceAtMost(oneMinuteMaxPagesPerDay())
    }

    private fun formatHourCursor(time: LocalTime): String = "%02d%02d%02d".format(time.hour, time.minute, time.second)

    private fun oneMinuteRangeFetchUnavailable(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw IllegalStateException(message, cause)

    private fun assertOneMinuteRangeResponseOk(
        body: Map<*, *>,
        code: String,
        dateStr: String,
        currentHour: String,
    ) {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd == "0") return

        val msgCd = body["msg_cd"]?.toString()
        val msg = body["msg1"]?.toString() ?: body["message"]?.toString()
        if (msgCd == "EGW00201" || msg?.contains("초당 거래건수") == true) {
            kisRateLimiterPort.onTpsExceeded()
        }
        oneMinuteRangeFetchUnavailable(
            "KIS 1m range candle rt_cd != 0 — code=$code date=$dateStr hour=$currentHour " +
                "rt_cd=$rtCd msg_cd=$msgCd msg=$msg",
        )
    }

    private fun maxInstant(
        a: java.time.Instant,
        b: java.time.Instant,
    ): java.time.Instant = if (a.isAfter(b)) a else b

    private fun minInstant(
        a: java.time.Instant,
        b: java.time.Instant,
    ): java.time.Instant = if (a.isBefore(b)) a else b

    private fun fetchOneMinCandlesBetween(
        code: String,
        mrktDivCode: String,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    oneMinuteRangeFetchUnavailable("KIS REST 1m range candle: REAL credential 없음 — code=$code")
                }

        val userId = cred.id.userId
        if (userId.isBlank()) {
            oneMinuteRangeFetchUnavailable("KIS REST 1m range candle: credential userId 빈 문자열 — code=$code DB 결함")
        }
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                oneMinuteRangeFetchUnavailable("KIS REST 1m range candle: 토큰 발급 실패 — code=$code", e)
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val allCandles = mutableListOf<Candle>()
            val seenTs = mutableSetOf<Long>()
            val targetDays = oneMinuteRangeTargetDays(from, to)

            for (targetDate in targetDays) {
                val dayStart = targetDate.atStartOfDay(KST_ZONE).toInstant()
                val dayEnd =
                    targetDate
                        .plusDays(1)
                        .atStartOfDay(KST_ZONE)
                        .minusNanos(1)
                        .toInstant()
                val dayFrom = maxInstant(from, dayStart)
                val dayTo = minInstant(to, dayEnd)
                if (dayFrom.isAfter(dayTo)) continue

                val dayToTime = dayTo.atZone(KST_ZONE).toLocalTime()
                if (dayToTime.isBefore(ONE_MINUTE_MARKET_OPEN)) {
                    log.debug("KIS 1m range fetch 장시작 전 범위 skip: code={} date={} to={}", code, targetDate, dayTo)
                    continue
                }

                val dateStr = targetDate.format(DATE_FMT)
                val cursorTime =
                    if (dayToTime.isAfter(ONE_MINUTE_MARKET_CLOSE)) {
                        ONE_MINUTE_MARKET_CLOSE
                    } else {
                        dayToTime
                    }
                var currentHour = formatHourCursor(cursorTime.truncatedTo(ChronoUnit.SECONDS))
                val maxPages = oneMinuteRangeMaxPages(dayFrom, dayTo)
                var pageCount = 0
                var dayDone = false

                while (!dayDone && pageCount < maxPages) {
                    val parsed =
                        fetchOneMinRangeSinglePage(
                            code = code,
                            targetDate = targetDate,
                            dateStr = dateStr,
                            currentHour = currentHour,
                            mrktDivCode = mrktDivCode,
                            accessToken = accessToken,
                            appKeyStr = appKeyStr,
                            secretKeyStr = secretKeyStr,
                        )
                    pageCount++

                    if (parsed.isEmpty()) {
                        dayDone = true
                        continue
                    }

                    for (candle in parsed) {
                        val epochSec = candle.ts.epochSeconds
                        if (epochSec >= dayFrom.epochSecond && epochSec <= dayTo.epochSecond && seenTs.add(epochSec)) {
                            allCandles.add(candle)
                        }
                    }

                    val earliest = parsed.minByOrNull { it.ts.epochSeconds }
                    val latest = parsed.maxByOrNull { it.ts.epochSeconds }
                    if (earliest == null || latest == null || latest.ts.epochSeconds < dayFrom.epochSecond) {
                        dayDone = true
                        continue
                    }

                    val cursorStep = nextOneMinuteCursor(earliest)
                    if (cursorStep.nextHour == null || earliest.ts.epochSeconds <= dayFrom.epochSecond) {
                        dayDone = true
                    } else {
                        currentHour = cursorStep.nextHour
                    }
                }

                log.info(
                    "KIS 1m range 일자 완료: code={} date={} pages={} maxPages={} total={}",
                    code,
                    dateStr,
                    pageCount,
                    maxPages,
                    allCandles.size,
                )
            }

            allCandles
                .distinctBy { it.ts.epochSeconds }
                .sortedBy { it.ts.epochSeconds }
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun fetchOneMinRangeSinglePage(
        code: String,
        targetDate: LocalDate,
        dateStr: String,
        currentHour: String,
        mrktDivCode: String,
        accessToken: String,
        appKeyStr: String,
        secretKeyStr: String,
    ): List<Candle> {
        var lastException: WebClientResponseException? = null
        for (attempt in 0 until RETRY_MAX_ATTEMPTS) {
            if (attempt > 0) {
                val waitMs = RETRY_BACKOFF_MS[attempt - 1]
                log.warn(
                    "KIS 1m range candle 500 오류 — {}ms backoff 후 재시도({}/{}): code={} date={} hour={}",
                    waitMs,
                    attempt + 1,
                    RETRY_MAX_ATTEMPTS,
                    code,
                    dateStr,
                    currentHour,
                )
                Thread.sleep(waitMs)
            }

            var signalSent = false
            var acquired = false
            try {
                kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = TR_ID_1MIN)
                acquired = true

                val body =
                    webClient
                        .get()
                        .uri { builder ->
                            builder
                                .scheme("https")
                                .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                .path(ENDPOINT_1MIN)
                                .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                .queryParam("fid_input_iscd", code)
                                .queryParam("fid_input_hour_1", currentHour)
                                .queryParam("fid_input_date_1", dateStr)
                                .queryParam("fid_pw_data_incu_yn", "N")
                                .queryParam("fid_fake_tick_incu_yn", "")
                                .build()
                        }.header("authorization", "Bearer $accessToken")
                        .header("appkey", appKeyStr)
                        .header("appsecret", secretKeyStr)
                        .header("tr_id", TR_ID_1MIN)
                        .header("custtype", "P")
                        .retrieve()
                        .bodyToMono(Map::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .block()

                if (body == null) {
                    oneMinuteRangeFetchUnavailable(
                        "KIS 1m range candle 빈 body — code=$code date=$dateStr hour=$currentHour",
                    )
                }
                assertOneMinuteRangeResponseOk(body, code, dateStr, currentHour)
                val parsed = parseKis1mResponse(body, code, targetDate)
                kisRateLimiterPort.onSuccess()
                signalSent = true
                return parsed
            } catch (e: WebClientResponseException) {
                val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
                if (errorClass is KisErrorClass.RateLimit) {
                    kisRateLimiterPort.onTpsExceeded()
                    signalSent = true
                    log.warn(
                        "KIS 1m range candle Rate Limit — 1초 backoff 후 빈 결과: code={} date={} hour={} status={}",
                        code,
                        dateStr,
                        currentHour,
                        e.statusCode.value(),
                    )
                    Thread.sleep(1000L)
                    oneMinuteRangeFetchUnavailable(
                        "KIS 1m range candle Rate Limit — code=$code date=$dateStr hour=$currentHour " +
                            "status=${e.statusCode.value()}",
                        e,
                    )
                }
                if (e.statusCode.value() == 500 && attempt < RETRY_MAX_ATTEMPTS - 1) {
                    lastException = e
                    continue
                }
                log.warn(
                    "KIS 1m range candle 실패: code={} date={} hour={} status={}",
                    code,
                    dateStr,
                    currentHour,
                    e.statusCode.value(),
                )
                oneMinuteRangeFetchUnavailable(
                    "KIS 1m range candle 실패 — code=$code date=$dateStr hour=$currentHour " +
                        "status=${e.statusCode.value()}",
                    e,
                )
            } catch (e: Exception) {
                log.warn("KIS 1m range candle 오류: code={} date={} hour={} err={}", code, dateStr, currentHour, e.message)
                oneMinuteRangeFetchUnavailable(
                    "KIS 1m range candle 오류 — code=$code date=$dateStr hour=$currentHour",
                    e,
                )
            } finally {
                if (acquired && !signalSent) {
                    log.warn(
                        "[KisRateLimit] 1m range acquire 후 통계 시그널 미발행 — drift risk: code={} date={} hour={}",
                        code,
                        dateStr,
                        currentHour,
                    )
                }
            }
        }
        log.warn(
            "KIS 1m range candle 500 retry 소진({}/{}) — 빈 결과 반환: code={} date={} hour={}",
            RETRY_MAX_ATTEMPTS,
            RETRY_MAX_ATTEMPTS,
            code,
            dateStr,
            currentHour,
        )
        if (lastException != null) {
            log.debug("마지막 예외: {}", lastException.message)
        }
        oneMinuteRangeFetchUnavailable(
            "KIS 1m range candle 500 retry 소진 — code=$code date=$dateStr hour=$currentHour",
            lastException,
        )
    }

    /**
     * 1m 분봉 조회 (FHKST03010230) — 일자당 페이지네이션으로 전수 수집.
     *
     * raw spec:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *
     * 1 call = 최대 120건. 정규장 9:00~15:30 ≈ 390건 → 일자당 ~4 call 필요.
     * NXT 통합 08:00~20:00 ≈ 720건 → 일자당 ~6 call 필요.
     *
     * 페이지네이션 알고리즘 (KIS-first raw fetch 의무 — 헌법 §3):
     *   1. fid_input_hour_1="200000" (통합 장마감) → 해당 시각 이전 최신 120건 반환 (내림차순)
     *   2. 응답 목록의 마지막 row(가장 이른 시각)의 stck_cntg_hour - 1분 → 다음 fid_input_hour_1
     *   3. 가장 이른 ts 가 09:00 KST 이전이면 해당 일자 종료
     *   4. 응답 0건 또는 일자당 max-pages-per-day call 초과 시 안전 종료
     *
     * Rate Limit (ADR 0037): 초당 10건. 50ms 마진 유지.
     * ADR 0105 Phase 1: 기본 최근 6개월 거래일, 최대 1년 거래일을 property로 제어.
     *
     * in-memory dedup: (code, interval, ts epoch second) Set으로 중복 경계 row 방지.
     */
    private fun fetchOneMinCandles(
        code: String,
        mrktDivCode: String,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS REST 1m candle: REAL credential 없음 — code={}", code)
                    return emptyList()
                }

        val userId = cred.id.userId
        // P0 hotfix 2026-05-20 — DB row 의 userId 빈 문자열 방어 (Redis `kis:token::REAL` cache pollution 차단)
        if (userId.isBlank()) {
            log.warn("KIS REST 1m candle: credential userId 빈 문자열 — code={} DB 결함 (cache pollution 차단)", code)
            return emptyList()
        }
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                log.warn("KIS REST 1m candle: 토큰 발급 실패 — code={}: {}", code, e.message)
                return emptyList()
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val allCandles = mutableListOf<Candle>()
            // in-memory dedup: ts epoch second 기준 — 페이지 경계 중복 방지
            val seenTs = mutableSetOf<Long>()

            log.debug(
                "KIS 1m fetch market 분기: code={} market={} mrktDivCode={}",
                code,
                instrumentRepository
                    .findById(code)
                    .map {
                        it.market
                    }.orElse("UNKNOWN"),
                mrktDivCode,
            )

            val targetDays = oneMinuteTargetDays()
            val maxPagesPerDay = oneMinuteMaxPagesPerDay()

            for (targetDate in targetDays) {
                val dateStr = targetDate.format(DATE_FMT)
                // 일자별 페이지네이션 시작: 통합 장마감(20:00) 기준 역순 백워크
                // raw: FHKST03010230 fid_input_hour_1 = 이 시각 이전 120건 반환
                var currentHour = ONE_MINUTE_INITIAL_CURSOR_HOUR
                var pageCount = 0
                var dayDone = false

                while (!dayDone && pageCount < maxPagesPerDay) {
                    // D-1 try-finally drift 차단 패턴 (T-D-1): 페이지 단위 signalSent flag.
                    var pageSignalSent = false
                    var pageAcquired = false
                    try {
                        // Rate Limit acquire (spec PR #564 §2-2 LOCK — BACKFILL priority)
                        // ADR 0037 초당 10건 한도. 1m 페이지네이션 backfill path → BACKFILL 우선순위
                        kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = TR_ID_1MIN)
                        pageAcquired = true

                        val body =
                            webClient
                                .get()
                                .uri { builder ->
                                    builder
                                        .scheme("https")
                                        .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                        .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                        .path(ENDPOINT_1MIN)
                                        // fid_cond_mrkt_div_code: ADR 0091 resolver — 종목 단위 UN allow-list + J fallback.
                                        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
                                        .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                        // fid_input_iscd: 종목코드
                                        .queryParam("fid_input_iscd", code)
                                        // fid_input_hour_1: 이 시각 이전 최신 120건 반환 (페이지네이션 커서)
                                        // raw spec: fid_input_hour_1 HHMMSS — 해당 시각 이전 데이터 반환
                                        .queryParam("fid_input_hour_1", currentHour)
                                        // fid_input_date_1: 조회 날짜 (yyyyMMdd)
                                        .queryParam("fid_input_date_1", dateStr)
                                        // fid_pw_data_incu_yn: N (과거 데이터 포함 여부)
                                        .queryParam("fid_pw_data_incu_yn", "N")
                                        // fid_fake_tick_incu_yn: 허봉 포함 여부 (빈 문자열 = 미포함, KIS spec 6번째 파라미터 필수 전송)
                                        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
                                        .queryParam("fid_fake_tick_incu_yn", "")
                                        .build()
                                }.header("authorization", "Bearer $accessToken")
                                .header("appkey", appKeyStr)
                                .header("appsecret", secretKeyStr)
                                .header("tr_id", TR_ID_1MIN)
                                .header("custtype", "P")
                                .retrieve()
                                .bodyToMono(Map::class.java)
                                .timeout(Duration.ofSeconds(10))
                                .block()

                        pageCount++

                        if (body == null) {
                            log.debug("KIS 1m fetch 빈 body: code={} date={} hour={}", code, dateStr, currentHour)
                            dayDone = true
                        } else {
                            val parsed = parseKis1mResponse(body, code, targetDate)
                            if (parsed.isEmpty()) {
                                // 응답 0건 = 해당 시각 이전 데이터 없음 → 일자 완료
                                log.debug(
                                    "KIS 1m fetch 응답 0건 — 일자 완료: code={} date={} hour={} page={}",
                                    code,
                                    dateStr,
                                    currentHour,
                                    pageCount,
                                )
                                dayDone = true
                            } else {
                                // dedup 적용 후 추가
                                var newCount = 0
                                for (candle in parsed) {
                                    val epochSec = candle.ts.epochSeconds
                                    if (seenTs.add(epochSec)) {
                                        allCandles.add(candle)
                                        newCount++
                                    }
                                }
                                log.debug(
                                    "KIS 1m fetch page 완료: code={} date={} hour={} page={} fetched={} new={}",
                                    code,
                                    dateStr,
                                    currentHour,
                                    pageCount,
                                    parsed.size,
                                    newCount,
                                )

                                // 다음 페이지 커서: 응답 목록에서 가장 이른 stck_cntg_hour - 1분
                                // KIS 응답은 내림차순(최신→과거) — 마지막 row가 가장 이른 시각
                                val earliestCandle = parsed.minByOrNull { it.ts.epochSeconds }
                                if (earliestCandle != null) {
                                    val cursorStep = nextOneMinuteCursor(earliestCandle)
                                    if (cursorStep.nextHour == null) {
                                        log.debug(
                                            "KIS 1m fetch 장시작 도달 — 일자 완료: code={} date={} earliestTime={}",
                                            code,
                                            dateStr,
                                            cursorStep.earliestTime,
                                        )
                                        dayDone = true
                                    } else {
                                        currentHour = cursorStep.nextHour
                                    }
                                } else {
                                    dayDone = true
                                }
                            }
                        }

                        // KIS API rate limit 준수: ADR 0037 — 초당 10건 한도 (KisRateLimiterPort.acquire 합류 — spec PR #564 §2-2)
                        // sleep 마진 제거 — Bucket4j blocking polling이 적시 진입 보장
                        kisRateLimiterPort.onSuccess()
                        pageSignalSent = true
                    } catch (e: WebClientResponseException) {
                        val statusCode = e.statusCode.value()
                        // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
                        val errorClass = classifyKisError(statusCode, e.responseBodyAsString)
                        if (errorClass is KisErrorClass.RateLimit) {
                            // Rate Limit (HTTP 429 또는 EGW00201) → onTpsExceeded + 1초 backoff (spec PR #564 §2-2 LOCK)
                            kisRateLimiterPort.onTpsExceeded()
                            pageSignalSent = true
                            log.warn(
                                "KIS 1m candle Rate Limit — 1초 backoff, 일자 건너뜀: code={} date={} hour={} status={}",
                                code,
                                dateStr,
                                currentHour,
                                statusCode,
                            )
                            Thread.sleep(1000L)
                            dayDone = true
                        } else if (statusCode == 500) {
                            // KIS 500 INTERNAL_SERVER_ERROR — §33 exponential backoff 3회 재시도
                            // 배경: dogfooding 2026-05-11 PARTIAL_FAIL 패턴 (1회 retry 불충분)
                            // Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미
                            var retriedSuccessfully = false
                            for (attempt in 0 until RETRY_MAX_ATTEMPTS - 1) {
                                val waitMs = RETRY_BACKOFF_MS[attempt]
                                log.warn(
                                    "KIS 1m candle 500 오류 — {}ms backoff 후 재시도({}/{}): code={} date={} hour={}",
                                    waitMs,
                                    attempt + 2,
                                    RETRY_MAX_ATTEMPTS,
                                    code,
                                    dateStr,
                                    currentHour,
                                )
                                Thread.sleep(waitMs)
                                // D-1: 500 retry inner try drift 차단 (T-D-1).
                                var retrySignalSent = false
                                var retryAcquired = false
                                try {
                                    // Rate Limit acquire 재진입 (spec PR #564 §2-2 — retry path BACKFILL)
                                    kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = TR_ID_1MIN)
                                    retryAcquired = true
                                    val retryBody =
                                        webClient
                                            .get()
                                            .uri { builder ->
                                                builder
                                                    .scheme("https")
                                                    .host(
                                                        kisBaseProd
                                                            .removePrefix("https://")
                                                            .substringBefore(":"),
                                                    ).port(
                                                        kisBaseProd
                                                            .substringAfterLast(":")
                                                            .toIntOrNull() ?: 9443,
                                                    ).path(ENDPOINT_1MIN)
                                                    .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                                    .queryParam("fid_input_iscd", code)
                                                    .queryParam("fid_input_hour_1", currentHour)
                                                    .queryParam("fid_input_date_1", dateStr)
                                                    .queryParam("fid_pw_data_incu_yn", "N")
                                                    .queryParam("fid_fake_tick_incu_yn", "")
                                                    .build()
                                            }.header("authorization", "Bearer $accessToken")
                                            .header("appkey", appKeyStr)
                                            .header("appsecret", secretKeyStr)
                                            .header("tr_id", TR_ID_1MIN)
                                            .header("custtype", "P")
                                            .retrieve()
                                            .bodyToMono(Map::class.java)
                                            .timeout(Duration.ofSeconds(10))
                                            .block()
                                    pageCount++
                                    kisRateLimiterPort.onSuccess()
                                    retrySignalSent = true
                                    if (retryBody == null) {
                                        log.debug(
                                            "KIS 1m retry 빈 body: code={} date={} hour={} attempt={}/{}",
                                            code,
                                            dateStr,
                                            currentHour,
                                            attempt + 2,
                                            RETRY_MAX_ATTEMPTS,
                                        )
                                        dayDone = true
                                    } else {
                                        val parsed = parseKis1mResponse(retryBody, code, targetDate)
                                        if (parsed.isEmpty()) {
                                            dayDone = true
                                        } else {
                                            for (candle in parsed) {
                                                if (seenTs.add(candle.ts.epochSeconds)) allCandles.add(candle)
                                            }
                                            log.info(
                                                "KIS 1m candle 500 retry 성공: code={} date={} hour={} attempt={}/{} fetched={}",
                                                code,
                                                dateStr,
                                                currentHour,
                                                attempt + 2,
                                                RETRY_MAX_ATTEMPTS,
                                                parsed.size,
                                            )
                                            val earliestCandle =
                                                parsed.minByOrNull { it.ts.epochSeconds }
                                            if (earliestCandle != null) {
                                                val cursorStep = nextOneMinuteCursor(earliestCandle)
                                                if (cursorStep.nextHour == null) {
                                                    dayDone = true
                                                } else {
                                                    currentHour = cursorStep.nextHour
                                                }
                                            } else {
                                                dayDone = true
                                            }
                                        }
                                    }
                                    retriedSuccessfully = true
                                    break
                                } catch (retryEx: WebClientResponseException) {
                                    // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일.
                                    val retryClass =
                                        classifyKisError(retryEx.statusCode.value(), retryEx.responseBodyAsString)
                                    if (retryClass is KisErrorClass.RateLimit) {
                                        kisRateLimiterPort.onTpsExceeded()
                                        retrySignalSent = true
                                    }
                                    if (retryEx.statusCode.value() == 500 &&
                                        attempt < RETRY_MAX_ATTEMPTS - 2
                                    ) {
                                        continue // 재시도 횟수 남음
                                    }
                                    log.warn(
                                        "KIS 1m candle 500 retry 소진({}/{}) — 일자 건너뜀: code={} date={} hour={} status={}",
                                        attempt + 2,
                                        RETRY_MAX_ATTEMPTS,
                                        code,
                                        dateStr,
                                        currentHour,
                                        retryEx.statusCode.value(),
                                    )
                                    dayDone = true
                                    retriedSuccessfully = true
                                    break
                                } catch (retryEx: Exception) {
                                    log.warn(
                                        "KIS 1m candle retry 오류: code={} date={} hour={} err={}",
                                        code,
                                        dateStr,
                                        currentHour,
                                        retryEx.message,
                                    )
                                    dayDone = true
                                    retriedSuccessfully = true
                                    break
                                } finally {
                                    if (retryAcquired && !retrySignalSent) {
                                        log.warn(
                                            "[KisRateLimit] 1m retry acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} date={}",
                                            code,
                                            dateStr,
                                        )
                                    }
                                }
                            }
                            if (!retriedSuccessfully) dayDone = true
                        } else {
                            log.warn(
                                "KIS 1m candle HTTP 오류: code={} date={} hour={} status={}",
                                code,
                                dateStr,
                                currentHour,
                                statusCode,
                            )
                            dayDone = true
                        }
                    } catch (e: Exception) {
                        log.warn(
                            "KIS 1m candle 오류: code={} date={} hour={} err={}",
                            code,
                            dateStr,
                            currentHour,
                            e.message,
                        )
                        dayDone = true
                    } finally {
                        if (pageAcquired && !pageSignalSent) {
                            log.warn(
                                "[KisRateLimit] 1m page acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} date={}",
                                code,
                                dateStr,
                            )
                        }
                    }
                }

                if (pageCount >= maxPagesPerDay && !dayDone) {
                    log.warn(
                        "KIS 1m fetch MAX_PAGES 도달 안전 종료: code={} date={} pages={}",
                        code,
                        dateStr,
                        pageCount,
                    )
                }

                log.info(
                    "KIS 1m backfill 일자 완료: code={} date={} pages={} total={}",
                    code,
                    dateStr,
                    pageCount,
                    allCandles.size,
                )
            }

            // ⚠ 전역 ASC 정규화 LOCK (2026-06-05 readiness P0-2 fix — sanity monotonic 폭주 root cause).
            //
            // 배경(2026-06-04 실측): admin 1m 백필 8종목 연속 실행 시 `CandleBackfillWorker sanity 실패 monotonic`
            //   WARN 418,567줄 발생. parseKis1mResponse는 페이지 단위로만 ASC 정렬하지만, 본 함수는
            //   oneMinuteTargetDays()가 최근일→과거일 역순으로 반환하고 각 일자 내부도 장마감(20:00)→장시작(09:00)
            //   역방향 페이지네이션이므로, allCandles는 [day0 page0 ASC][day0 page1(더 이른 시각) ASC][day1 …]
            //   형태로 페이지·일자 경계마다 ts가 뒤로 점프하는 비단조 시퀀스. 햇징 룰 §1 sanity가 ASC를 강제하므로
            //   row마다 monotonic WARN 폭발.
            //
            // fetchDailyCandles(일봉 path)·fetchOneMinCandlesBetween(range path)는 이미 반환 직전
            //   `.distinctBy { ts }.sortedBy { ts }`로 전역 ASC 정규화하지만, 본 1m fetch path만 누락되어 있었다.
            //   동일 정규화를 적용해 어댑터 layer가 ASC source-of-truth 단일 책임을 진다(ADR 0086 §11 정합).
            //   진짜 비단조 KIS 응답이 아니라 페이지 합산 순서 결함이므로 정렬 후 monotonic 위반은 0이 되어야 한다.
            val normalized =
                allCandles
                    .distinctBy { it.ts.epochSeconds }
                    .sortedBy { it.ts.epochSeconds }
            log.info(
                "KIS 1m backfill 전체 완료: code={} total={} (전역 ASC 정규화 — raw_accumulated={})",
                code,
                normalized.size,
                allCandles.size,
            )
            normalized
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 일봉·주봉·월봉·연봉 조회 (FHKST03010100) — **cursor 페이지네이션** (PR 2cbd4e8 검증된 패턴 1:1 재적용).
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * 페이지네이션 알고리즘 (PR 2cbd4e8 검증된 패턴 — 한국석유 004090 1년 backfill 243봉 GREEN):
     *   1. `cursor = today` 부터 역순 백워크 (외부 loop `for page in 0 until MAX_PAGES`)
     *   2. `fetchDailyCandlesSinglePage(code, interval, cursor)` — 1회 KIS 호출 (최대 100봉 응답)
     *   3. 응답 < KIS_PAGE_LIMIT(100) 이면 종료 (마지막 페이지 — 더 이상 과거 데이터 없음)
     *   4. 응답 0건 또는 cursor advance 실패 시 종료 (infinite loop guard)
     *   5. 다음 cursor = `oldestDate - 1day` → 다음 페이지
     *   6. distinctBy boundary dedup (페이지 경계 중복 ts 방어) + sortedBy ASC (PR #537 정규화 보존)
     *
     * regression 원인 evidence (PR #587 진단):
     *   PR 915699b (2026-05-11) Yahoo→KIS-only 재작성 시 페이지네이션 algorithm regression.
     *   결과: 30년 backfill 의도 → KIS 100건 한도로 잘림 (일봉 약 5개월만 수집).
     *
     * §33: HTTP 500 발생 시 exponential backoff 3회 재시도 (500ms → 1000ms → 2000ms) — single-page 내부.
     *
     * Rate Limit (ADR 0037 초당 10건): 6 calls × ~100ms = 600ms — 한도 영향 미미.
     *
     * ASC 정규화 LOCK (PR #537 보존):
     *   각 page 내부에서 parseKisDailyResponse가 이미 ASC 정규화 → 외부 sortedBy는 페이지 합산 후
     *   global ASC 보장. 이중 보강 — page 1 oldest + page 2 newest 사이 단조성 회귀 가드.
     *
     * 4 timeframe(D/W/M/Y) cover: fid_period_div_code 분기로 단일 함수가 4 PERIOD_DIV_CODE 처리
     *   (toPeriodDivCode 매핑). MAX_PAGES=6 × 100봉:
     *     - 일봉(D): 600봉 ≈ 약 2.5년 cover
     *     - 주봉(W): 600봉 ≈ 약 11년 cover
     *     - 월봉(M): 600봉 ≈ 약 50년 cover
     *     - 연봉(Y): 600봉 ≈ 600년 cover (사실상 무제한)
     */
    private fun fetchDailyCandles(
        code: String,
        interval: CandleInterval,
        mrktDivCode: String,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS REST candle: REAL credential 없음 — code={} interval={}", code, interval.code)
                    return emptyList()
                }

        val userId = cred.id.userId
        // P0 hotfix 2026-05-20 — DB row 의 userId 빈 문자열 방어 (Redis `kis:token::REAL` cache pollution 차단)
        if (userId.isBlank()) {
            log.warn(
                "KIS REST candle: credential userId 빈 문자열 — code={} interval={} DB 결함 (cache pollution 차단)",
                code,
                interval.code,
            )
            return emptyList()
        }
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                log.warn("KIS REST candle: 토큰 발급 실패 — code={}: {}", code, e.message)
                return emptyList()
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            // market 분기 — KIS raw spec: J=KRX주식(KOSPI+KOSDAQ), UN=통합(KRX+NXT).
            // ADR 0091: market 단위 UN 금지, 종목 단위 후보만 UN 우선 + J fallback.
            // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
            val periodDivCode = toPeriodDivCode(interval)

            // PR 2cbd4e8 cursor 페이지네이션 (1:1 재적용 — 한국석유 검증된 패턴).
            // ADR 0093 §3-1 LOCK — timeframe별 매트릭스 (1d=50, 1wk=20, 1mo=12, 1y=6 — property 외부화).
            // 기존 hardcode `MAX_PAGES = 6` 폐기 → ChartCandleProperties.getMaxPagesFor(interval.code).
            val accumulator = mutableListOf<Candle>()
            var cursor: LocalDate = LocalDate.now(KST_ZONE)
            // 30년 backfill 의도 — minimum 시작일 가드 (cursor가 이보다 더 과거로 가면 종료).
            val minStartDate: LocalDate = LocalDate.now(KST_ZONE).minusYears(30)
            val maxPages = chartCandleProperties.getMaxPagesFor(interval.code)

            for (page in 0 until maxPages) {
                if (cursor.isBefore(minStartDate)) {
                    log.debug(
                        "KIS REST candle 페이지네이션 minStartDate 도달 — 종료: code={} interval={} page={} cursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                    )
                    break
                }

                val pageCandles =
                    fetchDailyCandlesSinglePage(
                        code = code,
                        interval = interval,
                        periodDivCode = periodDivCode,
                        mrktDivCode = mrktDivCode,
                        cursor = cursor,
                        minStartDate = minStartDate,
                        accessToken = accessToken,
                        appKeyStr = appKeyStr,
                        secretKeyStr = secretKeyStr,
                    )

                if (pageCandles.isEmpty()) {
                    log.debug(
                        "KIS REST candle 페이지 0건 — 종료: code={} interval={} page={} cursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                    )
                    break
                }

                accumulator.addAll(pageCandles)

                // 마지막 페이지 — KIS_PAGE_LIMIT 미만 응답이면 더 이상 과거 데이터 없음 (PR 2cbd4e8 시나리오 6b).
                if (pageCandles.size < KIS_PAGE_LIMIT) {
                    log.debug(
                        "KIS REST candle 페이지 < {} — 마지막 페이지 종료: code={} interval={} page={} fetched={}",
                        KIS_PAGE_LIMIT,
                        code,
                        interval.code,
                        page,
                        pageCandles.size,
                    )
                    break
                }

                // 다음 cursor 산정 — 응답의 가장 오래된 ts - 1day.
                val oldestEpoch = pageCandles.minOf { it.ts.epochSeconds }
                val oldestDate =
                    java.time.Instant
                        .ofEpochSecond(oldestEpoch)
                        .atZone(KST_ZONE)
                        .toLocalDate()
                val newCursor = nextCursor(oldestDate)

                // infinite loop guard — cursor가 advance 안 하면 종료 (PR 2cbd4e8 시나리오 6 동일 가드).
                if (!newCursor.isBefore(cursor)) {
                    log.warn(
                        "KIS REST candle 페이지네이션 cursor advance 실패 — 종료: code={} interval={} page={} cursor={} newCursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                        newCursor,
                    )
                    break
                }
                if (newCursor.isBefore(minStartDate)) {
                    log.debug(
                        "KIS REST candle 페이지네이션 newCursor < minStartDate — 종료: code={} interval={} page={} newCursor={}",
                        code,
                        interval.code,
                        page,
                        newCursor,
                    )
                    break
                }
                cursor = newCursor
            }

            // 페이지 경계 중복 방어 (distinctBy boundary dedup) + ASC 정규화 (PR #537 보존).
            // 각 page의 parseKisDailyResponse가 이미 ASC 정렬했으므로 외부 sortedBy는 페이지 합산 후 global ASC 보장.
            val result =
                accumulator
                    .distinctBy { it.ts.epochSeconds }
                    .sortedBy { it.ts.epochSeconds }

            log.info(
                "KIS REST candle 페이지네이션 완료: code={} interval={} pages_consumed={} total={} (raw_accumulated={})",
                code,
                interval.code,
                (accumulator.size + KIS_PAGE_LIMIT - 1) / KIS_PAGE_LIMIT,
                result.size,
                accumulator.size,
            )

            result
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 단일 페이지 KIS 호출 — 1회 호출 최대 KIS_PAGE_LIMIT(100) 봉 응답.
     *
     * PR 2cbd4e8 `fetchCandlesSinglePage` 패턴 1:1 — 외부 페이지네이션 loop와 분리하여 단일 책임.
     *
     * §33 retry 정책 적용 (HTTP 500 exponential backoff 3회):
     *   - 500 발생 시 RETRY_BACKOFF_MS[0..2] = 500ms → 1000ms → 2000ms 대기 후 재시도
     *   - 429 발생 시 onTpsExceeded + 1초 backoff 후 빈 결과
     *   - 기타 HTTP 오류 / 일반 예외 시 즉시 빈 결과 (외부 loop가 break)
     *
     * @param cursor fid_input_date_2 — 이 시각 이전 100건 반환
     * @param minStartDate fid_input_date_1 — 30년 전 (충분한 마진)
     */
    private fun fetchDailyCandlesSinglePage(
        code: String,
        interval: CandleInterval,
        periodDivCode: String,
        mrktDivCode: String,
        cursor: LocalDate,
        minStartDate: LocalDate,
        accessToken: String,
        appKeyStr: String,
        secretKeyStr: String,
    ): List<Candle> {
        val cursorStr = cursor.format(DATE_FMT)
        val startStr = minStartDate.format(DATE_FMT)

        // §33 exponential backoff retry — 500 발생 시 최대 3회
        // Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미
        var lastException: WebClientResponseException? = null
        for (attempt in 0 until RETRY_MAX_ATTEMPTS) {
            if (attempt > 0) {
                val waitMs = RETRY_BACKOFF_MS[attempt - 1]
                log.warn(
                    "KIS daily candle 500 오류 — {}ms backoff 후 재시도({}/{}): code={} interval={} cursor={}",
                    waitMs,
                    attempt + 1,
                    RETRY_MAX_ATTEMPTS,
                    code,
                    interval.code,
                    cursorStr,
                )
                Thread.sleep(waitMs)
            }
            // D-1 try-finally drift 차단 패턴 (T-D-1): attempt 단위 signalSent flag.
            var attemptSignalSent = false
            var attemptAcquired = false
            try {
                // Rate Limit acquire (spec PR #564 §2-2 LOCK — INGESTION priority)
                // ADR 0037 초당 10건 한도. 일봉/주봉/월봉/연봉 정기 갱신 path → INGESTION 우선순위
                kisRateLimiterPort.acquire(KisCallPriority.INGESTION, timeoutMillis = 3000L, trId = TR_ID_DAILY)
                attemptAcquired = true

                val body =
                    webClient
                        .get()
                        .uri { builder ->
                            builder
                                .scheme("https")
                                .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                .path(ENDPOINT_DAILY)
                                .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                .queryParam("fid_input_iscd", code)
                                .queryParam("fid_input_date_1", startStr)
                                .queryParam("fid_input_date_2", cursorStr)
                                .queryParam("fid_period_div_code", periodDivCode)
                                .queryParam("fid_org_adj_prc", "0")
                                .build()
                        }.header("authorization", "Bearer $accessToken")
                        .header("appkey", appKeyStr)
                        .header("appsecret", secretKeyStr)
                        .header("tr_id", TR_ID_DAILY)
                        .header("custtype", "P")
                        .retrieve()
                        .bodyToMono(Map::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .block()
                        ?: run {
                            kisRateLimiterPort.onSuccess()
                            attemptSignalSent = true
                            return emptyList()
                        }
                if (attempt > 0) {
                    log.info(
                        "KIS daily candle 500 retry 성공: code={} interval={} cursor={} attempt={}/{}",
                        code,
                        interval.code,
                        cursorStr,
                        attempt + 1,
                        RETRY_MAX_ATTEMPTS,
                    )
                }
                kisRateLimiterPort.onSuccess()
                attemptSignalSent = true
                return parseKisDailyResponse(body, code, interval)
            } catch (e: WebClientResponseException) {
                // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
                if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                    // Rate Limit (HTTP 429 또는 EGW00201) → onTpsExceeded + 1초 backoff (spec PR #564 §2-2 LOCK)
                    kisRateLimiterPort.onTpsExceeded()
                    attemptSignalSent = true
                    log.warn(
                        "KIS REST candle Rate Limit — 1초 backoff 후 빈 결과: code={} interval={} cursor={} status={}",
                        code,
                        interval.code,
                        cursorStr,
                        e.statusCode.value(),
                    )
                    Thread.sleep(1000L)
                    return emptyList()
                }
                if (e.statusCode.value() == 500 && attempt < RETRY_MAX_ATTEMPTS - 1) {
                    lastException = e
                    continue // 재시도 횟수 남음 — backoff 후 재시도
                }
                log.warn(
                    "KIS REST candle 실패: code={} interval={} cursor={} status={}",
                    code,
                    interval.code,
                    cursorStr,
                    e.statusCode,
                )
                return emptyList()
            } catch (e: Exception) {
                log.warn(
                    "KIS REST candle 오류: code={} interval={} cursor={} err={}",
                    code,
                    interval.code,
                    cursorStr,
                    e.message,
                )
                return emptyList()
            } finally {
                if (attemptAcquired && !attemptSignalSent) {
                    log.warn(
                        "[KisRateLimit] daily candle attempt acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} cursor={}",
                        code,
                        cursorStr,
                    )
                }
            }
        }
        // retry 소진 (500 3회 모두 실패)
        log.warn(
            "KIS daily candle 500 retry 소진({}/{}) — 빈 결과 반환: code={} interval={} cursor={}",
            RETRY_MAX_ATTEMPTS,
            RETRY_MAX_ATTEMPTS,
            code,
            interval.code,
            cursorStr,
        )
        if (lastException != null) {
            log.debug("마지막 예외: {}", lastException.message)
        }
        return emptyList()
    }

    /**
     * FHKST03010230 1m 응답 파싱 — 1분봉 단일 timeframe.
     *
     * output2[]: stck_bsop_date (yyyyMMdd) + stck_cntg_hour (HHmmss) → KST timestamp
     * stck_oprc·stck_hgpr·stck_lwpr·stck_prpr(현재가=종가)·cntg_vol
     *
     * KIS raw spec:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *
     * ⚠ KIS 응답 정렬 함정 (P0 fix, 2026-05-19 — chart-curator + ADR 0086 §11 정합):
     *   `inquire-time-dailychartprice`는 페이지네이션 커서(fid_input_hour_1) 역방향 백워크로
     *   페이지별 DESC 가능. parseKisDailyResponse와 동일한 햇징 룰 §1 monotonic risk → ASC 정규화 의무.
     *   본 함수 단일 LOCK으로 1m timeframe ASC 정규화 cover.
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseKis1mResponse(
        body: Map<*, *>,
        code: String,
        targetDate: LocalDate,
    ): List<Candle> {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            log.debug(
                "KIS 1m rt_cd != 0: code={} date={} rt_cd={} msg={}",
                code,
                targetDate,
                rtCd,
                body["msg1"],
            )
            return emptyList()
        }
        val output2 = body["output2"] as? List<*> ?: return emptyList()
        val candles = mutableListOf<Candle>()
        for (row in output2) {
            val item = row as? Map<*, *> ?: continue
            // stck_bsop_date: yyyyMMdd, stck_cntg_hour: HHmmss
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val hourStr = item["stck_cntg_hour"]?.toString()?.trim() ?: continue
            val close = item["stck_prpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume =
                item["cntg_vol"]?.toString()?.trim()?.toLongOrNull()
                    ?: item["acml_vol"]?.toString()?.trim()?.toLongOrNull()
                    ?: 0L

            val ts =
                runCatching {
                    val ld =
                        if (dateStr.length == 8) {
                            LocalDate.parse(dateStr, DATE_FMT)
                        } else {
                            targetDate
                        }
                    // HHmmss → LocalTime 파싱
                    val lt =
                        LocalTime.of(
                            hourStr.substring(0, 2).toInt(),
                            hourStr.substring(2, 4).toInt(),
                            hourStr.substring(4, 6).toInt(),
                        )
                    val epochSecond = ld.atTime(lt).atZone(KST_ZONE).toEpochSecond()
                    KtInstant.fromEpochSeconds(epochSecond)
                }.getOrNull() ?: continue

            candles.add(
                Candle(
                    code = code,
                    interval = CandleInterval.ONE_MIN,
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
                    source = SOURCE,
                ),
            )
        }
        // KIS DESC → ASC 정규화 (P0 fix 2026-05-19 chart-curator) — parseKisDailyResponse와 동일.
        return candles.sortedBy { it.ts.epochSeconds }
    }

    /**
     * FHKST03010100 일봉/주봉/월봉/연봉 응답 파싱 — **4 PERIOD_DIV_CODE 공통 처리**.
     *
     * 본 함수는 `inquire-daily-itemchartprice` (FHKST03010100)이 fid_period_div_code 분기로
     * 4 timeframe을 단일 endpoint·단일 응답 schema로 처리하는 KIS 공식 매트릭스 정합:
     *
     * | PERIOD_DIV_CODE | timeframe | CandleInterval | 호출 경로 |
     * |-----------------|-----------|----------------|----------|
     * | D | 일봉 | ONE_DAY | fetchDailyCandles → toPeriodDivCode |
     * | W | 주봉 | ONE_WEEK | fetchDailyCandles → toPeriodDivCode |
     * | M | 월봉 | ONE_MONTH | fetchDailyCandles → toPeriodDivCode |
     * | Y | 연봉 | ONE_YEAR | fetchDailyCandles → toPeriodDivCode |
     *
     * KIS raw spec — 4 timeframe 응답 schema 동일(output2[] · stck_bsop_date · OHLCV):
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * ⚠ KIS 응답 정렬 함정 (P0 fix, 2026-05-19 — chart-curator + ADR 0086 §11 정합):
     *   KIS `inquire-daily-itemchartprice`는 응답 output2[]를 **최신→과거 DESC 순서**로 반환 (4 PERIOD_DIV_CODE 공통).
     *   햇징 룰 §1 (이중 검증) monotonic이 ASC 강제이므로,
     *   정규화 누락 시 idx 1+ 전부 reject → insertedCount=0 → 한국석유 evidence 5개월 갭 누적.
     *   따라서 본 파서가 `sortedBy { it.ts.epochSeconds }` ASC 정규화 책임 (chart-curator skill §5 source-of-truth + ADR 0086 §11 KIS raw 사전 정규화).
     *   KIS 공식 example도 `sort_values('stck_bsop_date', ascending=True)` 패턴 적용 (KIS-first 정합).
     *
     * ASC 정규화 적용 범위 — 본 함수 단일 LOCK으로 4 timeframe 전부 cover:
     *   - 일봉(D): 한국석유 PR #535·#537 evidence — 정규화 필수
     *   - 주봉(W): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *   - 월봉(M): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *   - 연봉(Y): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseKisDailyResponse(
        body: Map<*, *>,
        code: String,
        interval: CandleInterval,
    ): List<Candle> {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            log.warn("KIS REST candle rt_cd != 0: code={} rt_cd={} msg={}", code, rtCd, body["msg1"])
            return emptyList()
        }
        val output2 = body["output2"] as? List<*> ?: return emptyList()
        val candles = mutableListOf<Candle>()
        for (row in output2) {
            val item = row as? Map<*, *> ?: continue
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val close = item["stck_clpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume = item["acml_vol"]?.toString()?.trim()?.toLongOrNull() ?: 0L

            // dateStr = "20240507" → epoch second (KST 00:00:00)
            val ts =
                runCatching {
                    val ld = LocalDate.parse(dateStr, DATE_FMT)
                    val epochSecond = ld.atStartOfDay(KST_ZONE).toEpochSecond()
                    KtInstant.fromEpochSeconds(epochSecond)
                }.getOrNull() ?: continue

            candles.add(
                Candle(
                    code = code,
                    interval = interval,
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
                    source = SOURCE,
                ),
            )
        }
        // KIS DESC → ASC 정규화 (P0 fix 2026-05-19 chart-curator).
        // 햇징 룰 §1 monotonic이 ASC 강제이므로 adapter layer가 source-of-truth로 정규화.
        val sorted = candles.sortedBy { it.ts.epochSeconds }
        log.debug("KIS REST candle 파싱: code={} interval={} count={} (ASC 정규화)", code, interval.code, sorted.size)
        return sorted
    }

    override fun fetchPrice(
        userId: String,
        code: String,
    ): kr.spdaewoon.magicjar.domain.market.tick.TickEvent =
        throw NotImplementedError(
            "KisRestCandleAdapter는 fetchPrice 미지원. KisRestPriceAdapter 사용. " +
                "후속 minor PR에서 KisRestPort 인터페이스 분리 (KisRestCandlePort + KisRestPricePort) 예정.",
        )
}
