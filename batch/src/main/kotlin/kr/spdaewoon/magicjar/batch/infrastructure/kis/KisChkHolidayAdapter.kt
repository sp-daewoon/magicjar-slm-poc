package kr.spdaewoon.magicjar.batch.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS chk-holiday 어댑터 (ADR 0013 §2-2 — KIS-first raw fetch 의무).
 *
 * KIS-first raw 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py
 *
 * endpoint: /uapi/domestic-stock/v1/quotations/chk-holiday
 * tr_id: CTCA0903R
 * params:
 *   BASS_DT (YYYYMMDD, 필수) — 조회 기준일
 *   CTX_AREA_FK — 연속조회 검색조건
 *   CTX_AREA_NK — 연속조회 키
 * 응답 output:
 *   bass_dt       — 기준일자 (YYYYMMDD)
 *   wday_dvsn_cd  — 요일구분코드
 *   bzdy_yn       — 영업일여부 Y/N
 *   tr_day_yn     — 거래일여부 Y/N
 *   opnd_yn       — 개장일여부 Y/N
 *   sttl_day_yn   — 결제일여부 Y/N
 *
 * 호출 정책 (ADR 0013 §3-7 §1):
 *   - 1일 1회 권장 (KIS 명시: "당사 원장서비스와 연관")
 *   - sync-at-boot 1회 + cron 03:00 daily = 1일 최대 2회 (재기동 시 +1)
 *   - 연속조회(CTX_AREA_FK/NK)로 약 1년치 일괄 fetch
 */
@Component
class KisChkHolidayAdapter(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) {
    private val log = LoggerFactory.getLogger(KisChkHolidayAdapter::class.java)

    companion object {
        // KIS-first raw — chk_holiday.py 직접 인용
        private const val TR_ID = "CTCA0903R"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/chk-holiday"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private const val SOURCE = "KIS_CHK_HOLIDAY"
        private const val MAX_PAGES = 30 // 약 1년 = 365건 / 1페이지 = 20건 내외 → 18 페이지
    }

    /**
     * 지정 기간(YYYYMMDD 기준)의 영업일 정보 fetch.
     * 연속조회로 페이징 처리 — 결과를 MarketCalendarEntry 목록으로 반환.
     *
     * @param bassDate 조회 기준 시작일 (KIS는 BASS_DT 기준 이후 데이터 반환)
     * @param appKey KIS appkey (사용자 credential에서 복호화해 전달)
     * @param secretKey KIS appsecret
     * @param accessToken KIS OAuth2 access_token (Bearer)
     */
    fun fetchHolidays(
        bassDate: LocalDate,
        appKey: String,
        secretKey: String,
        accessToken: String,
    ): List<MarketCalendarEntry> {
        val results = mutableListOf<MarketCalendarEntry>()
        var ctxAreaFk = ""
        var ctxAreaNk = ""
        var page = 0

        while (page < MAX_PAGES) {
            page++
            val response =
                fetchPage(
                    bassDate = bassDate.format(DATE_FMT),
                    ctxAreaFk = ctxAreaFk,
                    ctxAreaNk = ctxAreaNk,
                    appKey = appKey,
                    secretKey = secretKey,
                    accessToken = accessToken,
                ) ?: break

            val entries = parseResponse(response)
            results.addAll(entries)
            log.debug("chk-holiday page={} entries={}", page, entries.size)

            // 연속조회 헤더 파싱
            val trCont = response["tr_cont"]?.toString() ?: ""
            if (trCont != "M" && trCont != "F") break // 마지막 페이지

            @Suppress("UNCHECKED_CAST")
            val ctx = response["ctx_area_fk"] as? String ?: ""
            ctxAreaFk = ctx
            @Suppress("UNCHECKED_CAST")
            val ctxNk = response["ctx_area_nk"] as? String ?: ""
            ctxAreaNk = ctxNk

            if (entries.isEmpty()) break
        }

        log.info("chk-holiday 완료: bassDate={} total={}", bassDate, results.size)
        return results
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchPage(
        bassDate: String,
        ctxAreaFk: String,
        ctxAreaNk: String,
        appKey: String,
        secretKey: String,
        accessToken: String,
    ): Map<*, *>? {
        val host = kisBaseProd.removePrefix("https://").substringBefore(":")
        val port = kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443

        // Rate Limit acquire (D-2 합류 — BACKFILL priority — 1일 1회 cron이라 risk 낮지만 페이징 burst 가능)
        // try 안 진입 + signalSent flag로 drift 차단 (D-1 패턴 정합).
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = TR_ID)
            val body =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(host)
                            .port(port)
                            .path(ENDPOINT)
                            .queryParam("BASS_DT", bassDate)
                            .apply {
                                if (ctxAreaFk.isNotEmpty()) queryParam("CTX_AREA_FK", ctxAreaFk)
                                if (ctxAreaNk.isNotEmpty()) queryParam("CTX_AREA_NK", ctxAreaNk)
                            }.build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(15))
                    .block()
            kisRateLimiterPort.onSuccess()
            signalSent = true
            body
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn("chk-holiday HTTP 오류: bassDate={} status={}", bassDate, e.statusCode)
            null
        } catch (e: Exception) {
            log.warn("chk-holiday 오류: bassDate={} err={}", bassDate, e.message)
            null
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] chk-holiday acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): bassDate={}",
                    bassDate,
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResponse(body: Map<*, *>): List<MarketCalendarEntry> {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            log.warn("chk-holiday rt_cd != 0: rt_cd={} msg={}", rtCd, body["msg1"])
            return emptyList()
        }

        val output = body["output"] as? List<*> ?: return emptyList()
        val entries = mutableListOf<MarketCalendarEntry>()

        for (row in output) {
            val item = row as? Map<*, *> ?: continue
            val bassdt = item["bass_dt"]?.toString()?.trim() ?: continue
            val bzdyYn = item["bzdy_yn"]?.toString()?.trim()?.uppercase() ?: "N"

            val date =
                runCatching {
                    LocalDate.parse(bassdt, DATE_FMT)
                }.getOrNull() ?: continue

            val isBusinessDay = bzdyYn == "Y"
            // bzdy_yn=N 이면서 주말이 아니면 공휴일로 간주
            val isWeekend =
                date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                    date.dayOfWeek == java.time.DayOfWeek.SUNDAY
            val isHoliday = !isBusinessDay && !isWeekend

            entries.add(
                MarketCalendarEntry(
                    date = date,
                    isBusinessDay = isBusinessDay,
                    isHoliday = isHoliday,
                    holidayName = null, // KIS chk-holiday는 holiday_name 미제공
                    source = SOURCE,
                ),
            )
        }
        return entries
    }
}
