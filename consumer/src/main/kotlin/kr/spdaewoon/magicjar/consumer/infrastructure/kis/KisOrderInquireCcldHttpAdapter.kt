package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.scheduler.Schedulers
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS REST 일별 주문체결조회 어댑터 — ADR 0084 P0-3 (외부 코드 리뷰 결함 #4 재연결 backfill).
 *
 * ### 배경 (ADR 0084 §2-1 P0-3)
 * KIS WS H0STCNI0/H0STCNI9 체결 통보가 재연결 사이 (disconnect → reconnect) 발생 시 손실 risk.
 * 본 어댑터는 [KisWebSocketBridge.onReconnect] hook 직후 호출되어 disconnect 이후 발생한 체결 내역을 backfill.
 * 동일 체결이 WS + REST 양 경로로 들어와도 event_id UNIQUE constraint(Flyway V35)가 중복 INSERT 자동 차단.
 *
 * ### KIS-first raw 출처 (헌법 §3)
 * - WebFetch verbatim (2026-05-18): https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
 * - examples_user 정합: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py (def inquire_daily_ccld)
 *
 * ### raw verbatim 시그니처 (2026-05-18 WebFetch LOCK)
 * ```python
 * API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
 *
 * def inquire_daily_ccld(env_dv, pd_dv, cano, acnt_prdt_cd, inqr_strt_dt, inqr_end_dt,
 *                       sll_buy_dvsn_cd, ccld_dvsn, inqr_dvsn, inqr_dvsn_3, pdno="", ord_gno_brno="",
 *                       odno="", inqr_dvsn_1="", FK100="", NK100="", tr_cont="", excg_id_dvsn_cd="KRX",
 *                       ...):
 *     if env_dv == "real":
 *         if pd_dv == "before":  tr_id = "CTSC9215R"   # 3개월 이전
 *         elif pd_dv == "inner": tr_id = "TTTC0081R"   # 3개월 이내
 *     elif env_dv == "demo":
 *         if pd_dv == "before":  tr_id = "VTSC9215R"
 *         elif pd_dv == "inner": tr_id = "VTTC0081R"
 *
 *     params = {
 *         "CANO": cano,                  # 계좌번호 8자리
 *         "ACNT_PRDT_CD": acnt_prdt_cd,  # 계좌상품코드 ("01")
 *         "INQR_STRT_DT": inqr_strt_dt,  # 조회시작일 YYYYMMDD
 *         "INQR_END_DT": inqr_end_dt,    # 조회종료일 YYYYMMDD
 *         "SLL_BUY_DVSN_CD": sll_buy_dvsn_cd,  # "00"=전체 / "01"=매도 / "02"=매수
 *         "INQR_DVSN": inqr_dvsn,        # "00"=역순 / "01"=정순
 *         "PDNO": pdno,                  # 종목코드 (공백 시 전체)
 *         "CCLD_DVSN": ccld_dvsn,        # "00"=전체 / "01"=체결 / "02"=미체결
 *         "ORD_GNO_BRNO": ord_gno_brno,  # 주문채번지점번호 (공백 시 전체)
 *         "ODNO": odno,                  # 주문번호 (공백 시 전체)
 *         "INQR_DVSN_3": inqr_dvsn_3,    # "00"=전체 / "01"=현금 / ...
 *         "INQR_DVSN_1": inqr_dvsn_1,    # 없음:전체 / "1":ELW / "2":프리보드
 *         "CTX_AREA_FK100": FK100,       # 연속조회 검색조건 (1차 빈 문자열)
 *         "CTX_AREA_NK100": NK100,       # 연속조회 키 (1차 빈 문자열)
 *         "EXCG_ID_DVSN_CD": excg_id_dvsn_cd,  # "KRX" default
 *     }
 * ```
 *
 * ### 본 어댑터 호출 시점
 * - WS 재연결 직후 [KisWebSocketBridge.onReconnect] hook (consumer 모듈 내부 wiring)
 * - 또는 향후 P0-2 [OrderReconciliationWorker] 1분 cron (별도 backend-core 영역 — 본 PR은 backfill path만)
 *
 * ### 본 PR 사용 범위
 * - `fetchTodayExecutions(userId, accountType)` — 당일 체결 내역 조회 (정합성 backfill용 — disconnect 무관 1회 발사)
 * - 향후 backlog §62 — `fetchSinceTimestamp` (disconnect 시각 기점 cursor 조회) 추가 가능
 *
 * ### Rate Limit 영향 (ADR 0037)
 * - WS 재연결 시점만 호출 → 호출 빈도 LOW
 * - timeout 5s + 1회 호출 정합 (재시도는 caller 책임 — KisOrderHttpAdapter와 동일 정책)
 *
 * ### 응답 매핑 (raw `output1` array)
 * 1건당 핵심 필드:
 * - `odno` — 주문번호 (kisOrderNo cross-ref)
 * - `pdno` — 종목코드
 * - `sll_buy_dvsn_cd` — "01"=매도 / "02"=매수
 * - `ord_qty` — 주문수량
 * - `tot_ccld_qty` — 총체결수량 (cumulative)
 * - `avg_prvs` — 평균체결가
 * - `ord_dt` — 주문일자 YYYYMMDD
 * - `ord_tmd` — 주문시각 HHMMSS
 * - `cncl_cfrm_qty` — 취소확정수량
 *
 * 본 어댑터는 `tot_ccld_qty > 0` 한정으로 [KisDailyCcldExecution] 매핑 후 반환.
 *
 * AI 호출 0 — T-O 트랙 정책 정합 (ADR 0068 §8 + ADR 0084 §2-8).
 */
@Component
class KisOrderInquireCcldHttpAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisOrderInquireCcldHttpAdapter::class.java)

    companion object {
        /** raw: `API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"` (2026-05-18 WebFetch LOCK). */
        const val INQUIRE_DAILY_CCLD_PATH: String = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        private const val TIMEOUT_SEC = 10L
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")
        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * 당일 체결 내역 조회 — WS 재연결 backfill용 1회 호출 (체결 한정).
     *
     * tr_id 분기 (raw 87~97):
     *  - REAL: TTTC0081R (3개월 이내 = `pd_dv="inner"`)
     *  - MOCK: VTTC0081R
     *
     * params 분기 (raw 99~115 정합):
     *  - INQR_STRT_DT = INQR_END_DT = 당일 (KST YYYYMMDD)
     *  - SLL_BUY_DVSN_CD = "00" (전체)
     *  - CCLD_DVSN = "01" (체결만 — 미체결 제외)
     *  - INQR_DVSN = "00" (역순 — 최신부터)
     *  - INQR_DVSN_3 = "00" (전체)
     *  - 나머지 = 빈 문자열
     *
     * @return 체결 1건당 [KisDailyCcldExecution] 리스트. 실패 시 empty.
     */
    fun fetchTodayExecutions(
        userId: String,
        accountType: AccountType,
    ): List<KisDailyCcldExecution> {
        val today = LocalDate.now(KST).format(DATE_FMT)
        return fetchExecutions(userId, accountType, today, today)
    }

    /**
     * 임의 기간 체결 내역 조회 (3개월 이내 한정 — tr_id pd_dv="inner" LOCK).
     *
     * @param startDateYyyyMmDd KST YYYYMMDD 시작일
     * @param endDateYyyyMmDd KST YYYYMMDD 종료일
     */
    fun fetchExecutions(
        userId: String,
        accountType: AccountType,
        startDateYyyyMmDd: String,
        endDateYyyyMmDd: String,
    ): List<KisDailyCcldExecution> {
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: run {
                    log.warn(
                        "KIS inquire-daily-ccld credential 부재 — backfill skip: userId={} accountType={}",
                        maskUserId(userId),
                        accountType.name,
                    )
                    return emptyList()
                }

        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)
        val trId = if (accountType == AccountType.REAL) "TTTC0081R" else "VTTC0081R"
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   기존 결함: acquire 호출 후 onSuccess/onTpsExceeded 시그널이 모든 path 누락 → 통계 영구 drift.
        var signalSent = false
        var acquiredToken = false
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            // raw verbatim params 정합 (2026-05-18 WebFetch LOCK).
            // baseUrl + path + query string 직접 합성 — KisRestPriceAdapter 동일 패턴.
            val url =
                buildString {
                    append(baseUrl)
                    append(INQUIRE_DAILY_CCLD_PATH)
                    append("?CANO=").append(cred.accountNumber)
                    append("&ACNT_PRDT_CD=").append(cred.accountPrdtCd)
                    append("&INQR_STRT_DT=").append(startDateYyyyMmDd)
                    append("&INQR_END_DT=").append(endDateYyyyMmDd)
                    append("&SLL_BUY_DVSN_CD=00") // 전체 (raw L99)
                    append("&INQR_DVSN=00") // 역순
                    append("&PDNO=") // 전체
                    append("&CCLD_DVSN=01") // 체결만
                    append("&ORD_GNO_BRNO=")
                    append("&ODNO=")
                    append("&INQR_DVSN_3=00") // 전체
                    append("&INQR_DVSN_1=")
                    append("&CTX_AREA_FK100=")
                    append("&CTX_AREA_NK100=")
                    append("&EXCG_ID_DVSN_CD=KRX")
                }

            // Rate Limit acquire (spec PR #564 §2-2 LOCK — BACKFILL priority — reconcile cron ccld 조회)
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = trId)
            acquiredToken = true

            val response =
                webClient
                    .get()
                    .uri(url)
                    .header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(KisInquireDailyCcldResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    // P0 hotfix 보강: caller(KisExecutionBackfillService)가 reactor event-loop에서
                    // 진입하면 boundedElastic으로 넘기고, WebClient subscription도 blocking 전용 worker에 둔다.
                    .subscribeOn(Schedulers.boundedElastic())
                    .block()
                    ?: run {
                        kisRateLimiterPort.onSuccess()
                        signalSent = true
                        log.warn("KIS inquire-daily-ccld response null: userId={}", maskUserId(userId))
                        return emptyList()
                    }

            kisRateLimiterPort.onSuccess()
            signalSent = true

            if (response.rtCd != "0") {
                log.warn(
                    "KIS inquire-daily-ccld REJECT: userId={} rt_cd={} msg={}",
                    maskUserId(userId),
                    response.rtCd,
                    response.msg1,
                )
                return emptyList()
            }

            val raws = response.output1 ?: emptyList()
            val executions =
                raws.mapNotNull { row -> toExecution(row) }.filter { it.totCcldQty > 0 }

            log.info(
                "KIS inquire-daily-ccld 응답: userId={} accountType={} startDt={} endDt={} 응답row={} 체결row={}",
                maskUserId(userId),
                accountType.name,
                startDateYyyyMmDd,
                endDateYyyyMmDd,
                raws.size,
                executions.size,
            )
            executions
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error(
                "KIS inquire-daily-ccld HTTP 실패: userId={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            emptyList()
        } catch (e: Exception) {
            log.error(
                "KIS inquire-daily-ccld 예외: userId={} err={}",
                maskUserId(userId),
                e.message,
            )
            emptyList()
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] inquire-daily-ccld acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): userId={}",
                    maskUserId(userId),
                )
            }
        }
    }

    /**
     * raw `output1` row → 도메인 [KisDailyCcldExecution] 매핑.
     *
     * raw 응답 필드 (verbatim KIS API 문서 정합):
     *  - `ord_dt` — 주문일자 YYYYMMDD
     *  - `ord_tmd` — 주문시각 HHMMSS
     *  - `odno` — 주문번호
     *  - `pdno` — 종목코드
     *  - `sll_buy_dvsn_cd` — "01"=매도 "02"=매수
     *  - `ord_qty` — 주문수량
     *  - `tot_ccld_qty` — 총체결수량
     *  - `avg_prvs` — 평균체결가
     */
    internal fun toExecution(row: KisInquireDailyCcldOutput1): KisDailyCcldExecution? {
        val odno = row.odno?.trim().orEmpty()
        if (odno.isBlank()) return null
        val totCcldQty = row.totCcldQty?.trim()?.toIntOrNull() ?: 0
        if (totCcldQty <= 0) return null

        val avgPrvs = row.avgPrvs?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val ordDt = row.ordDt?.trim().orEmpty()
        val ordTmd =
            row.ordTmd
                ?.trim()
                ?.padStart(6, '0')
                .orEmpty()
        val executedAt = parseExecutedAt(ordDt, ordTmd)

        return KisDailyCcldExecution(
            kisOrderNo = odno,
            code = row.pdno?.trim().orEmpty(),
            sellBuyCls = row.sllBuyDvsnCd?.trim().orEmpty(),
            ordQty = row.ordQty?.trim()?.toIntOrNull() ?: 0,
            totCcldQty = totCcldQty,
            avgPrice = avgPrvs,
            ordDt = ordDt,
            ordTmd = ordTmd,
            executedAt = executedAt,
        )
    }

    /**
     * YYYYMMDD + HHMMSS → Instant (KST → UTC).
     * 파싱 실패 시 현재시각 fallback (graceful — backfill 안전성 우선).
     */
    internal fun parseExecutedAt(
        ordDt: String,
        ordTmd: String,
    ): Instant =
        try {
            val date = LocalDate.parse(ordDt, DATE_FMT)
            val time = LocalTime.parse(ordTmd, TIME_FMT)
            date.atTime(time).atZone(KST).toInstant()
        } catch (e: Exception) {
            log.warn("KIS inquire-daily-ccld 시각 파싱 실패 — now() fallback: ordDt={} ordTmd={} err={}", ordDt, ordTmd, e.message)
            Instant.now()
        }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}

/**
 * 1건의 체결 row — KisOrderInquireCcldHttpAdapter 도메인 출력.
 *
 * eventId 합성은 caller(backfill worker)에서 `KisExecutionWsListener.buildEventId(odno, ordTmd, totCcldQty)` 호출하여 정합.
 * 단, totCcldQty는 cumulative 누적이므로 WS event_id (`{odno}:{stckCntgHour}:{cntgQty}`)와 다를 수 있음 →
 * **backfill row는 cumulative 단위 event_id 별도 규칙 적용**: `BACKFILL:{odno}:{ordTmd}` (caller 책임).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8 정합).
 */
data class KisDailyCcldExecution(
    /** KIS ODNO */
    val kisOrderNo: String,
    /** PDNO 종목코드 */
    val code: String,
    /** SLL_BUY_DVSN_CD — "01"=매도 / "02"=매수 */
    val sellBuyCls: String,
    /** 주문수량 */
    val ordQty: Int,
    /** 총체결수량 (cumulative) */
    val totCcldQty: Int,
    /** 평균체결가 */
    val avgPrice: BigDecimal,
    /** 주문일자 YYYYMMDD */
    val ordDt: String,
    /** 주문시각 HHMMSS */
    val ordTmd: String,
    /** 합성 executedAt (KST → UTC) */
    val executedAt: Instant,
)

/**
 * KIS inquire-daily-ccld 응답 DTO.
 *
 * raw 응답 본문 구조:
 * ```json
 * {
 *   "rt_cd": "0",
 *   "msg_cd": "...",
 *   "msg1": "...",
 *   "ctx_area_fk100": "...",
 *   "ctx_area_nk100": "...",
 *   "output1": [ { "ord_dt": "...", "odno": "...", ... }, ... ],
 *   "output2": { ... cumulative summary ... }
 * }
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisInquireDailyCcldResponse(
    @JsonProperty("rt_cd")
    val rtCd: String? = null,
    @JsonProperty("msg_cd")
    val msgCd: String? = null,
    @JsonProperty("msg1")
    val msg1: String? = null,
    @JsonProperty("ctx_area_fk100")
    val ctxAreaFk100: String? = null,
    @JsonProperty("ctx_area_nk100")
    val ctxAreaNk100: String? = null,
    @JsonProperty("output1")
    val output1: List<KisInquireDailyCcldOutput1>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisInquireDailyCcldOutput1(
    @JsonProperty("ord_dt")
    val ordDt: String? = null,
    @JsonProperty("ord_tmd")
    val ordTmd: String? = null,
    @JsonProperty("odno")
    val odno: String? = null,
    @JsonProperty("pdno")
    val pdno: String? = null,
    @JsonProperty("sll_buy_dvsn_cd")
    val sllBuyDvsnCd: String? = null,
    @JsonProperty("ord_qty")
    val ordQty: String? = null,
    @JsonProperty("tot_ccld_qty")
    val totCcldQty: String? = null,
    @JsonProperty("avg_prvs")
    val avgPrvs: String? = null,
    @JsonProperty("cncl_cfrm_qty")
    val cnclCfrmQty: String? = null,
)
