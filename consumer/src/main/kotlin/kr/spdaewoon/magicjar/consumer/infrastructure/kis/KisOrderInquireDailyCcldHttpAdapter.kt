package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderInquireDailyCcldPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS 주식일별주문체결조회 어댑터 (ADR 0084 §2-2 P0-2 — 외부 코드 리뷰 결함 #3 fix).
 *
 * ### 배경 (ADR 0084 P0-2)
 * KIS REST 5xx 재시도 후 실패 또는 timeout 시 현행 path는 즉시 REJECTED 마킹.
 * 그러나 KIS는 주문을 실제 접수했을 수 있어 — 클라이언트 ACK 미수신 ≠ KIS 미접수.
 * PENDING_RECONCILE 상태로 보류 → OrderReconciliationWorker가 본 어댑터 호출로 실제 상태 검증.
 *
 * ### raw 출처 (헌법 §3 KIS-first — 2026-05-18 WebFetch verbatim LOCK)
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
 *
 * ### raw 본문 시그니처 (L20 API_URL · L106~L121 tr_id 분기 · L141~L155 params)
 * ```python
 * API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
 *
 * # tr_id 설정 (L106~L121)
 * if env_dv == "real":
 *     if pd_dv == "before":   tr_id = "CTSC9215R"
 *     elif pd_dv == "inner":  tr_id = "TTTC0081R"
 * elif env_dv == "demo":
 *     if pd_dv == "before":   tr_id = "VTSC9215R"
 *     elif pd_dv == "inner":  tr_id = "VTTC0081R"
 *
 * # params (L141~L155 verbatim — 본 baseline은 ODNO 단건 검색이므로 PDNO·INQR_DVSN_1 공란)
 * params = {
 *     "CANO": cano, "ACNT_PRDT_CD": acnt_prdt_cd,
 *     "INQR_STRT_DT": inqr_strt_dt, "INQR_END_DT": inqr_end_dt,
 *     "SLL_BUY_DVSN_CD": sll_buy_dvsn_cd, "PDNO": pdno,
 *     "CCLD_DVSN": ccld_dvsn, "INQR_DVSN": inqr_dvsn, "INQR_DVSN_3": inqr_dvsn_3,
 *     "ORD_GNO_BRNO": ord_gno_brno, "ODNO": odno, "INQR_DVSN_1": inqr_dvsn_1,
 *     "CTX_AREA_FK100": FK100, "CTX_AREA_NK100": NK100,
 * }
 * ```
 *
 * ### 본 baseline 인자 매트릭스 (PENDING_RECONCILE ODNO 단건 검색용)
 * - SLL_BUY_DVSN_CD = "00" (전체 매수+매도)
 * - CCLD_DVSN = "00" (체결+미체결 모두 — KIS 접수 자체 여부가 핵심이므로)
 * - INQR_DVSN = "00" (역순 — 최근 우선)
 * - INQR_DVSN_3 = "00" (전체 — 현금/신용 구분 무관)
 * - ODNO = 검증 대상 KIS 주문번호
 * - PDNO = "" (ODNO 단건 검색이므로 종목 공란)
 *
 * ### 페이지 룰 (raw L172~L201)
 * 본 baseline은 ODNO 단건 검색이므로 첫 페이지(output1[0]) 검사로 충분.
 * 페이징 흡수는 향후 일별 잔체결 backfill 시 별도 메서드로 분리.
 *
 * ### 정보 수집은 모두 실전 키 (사용자 메모리 feedback_kis_key_realonly_for_data)
 * REAL 자격증명 우선 — MOCK fallback (모의 자격증명만 있는 사용자도 검증 가능).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KisOrderInquireDailyCcldHttpAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisOrderInquireDailyCcldPort {
    private val log = LoggerFactory.getLogger(KisOrderInquireDailyCcldHttpAdapter::class.java)

    companion object {
        /**
         * raw L21 verbatim: `API_URL = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"`
         */
        const val ENDPOINT: String = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"

        /** raw L107·L110: env=real + pd=inner (3개월 이내) */
        const val TR_ID_REAL_INNER: String = "TTTC0081R"

        /** raw L114·L117: env=demo + pd=inner (3개월 이내) */
        const val TR_ID_MOCK_INNER: String = "VTTC0081R"

        /** raw L108: env=real + pd=before (3개월 이전) — backfill 용 (본 baseline 미사용) */
        const val TR_ID_REAL_BEFORE: String = "CTSC9215R"

        /** raw L115: env=demo + pd=before (3개월 이전) — backfill 용 (본 baseline 미사용) */
        const val TR_ID_MOCK_BEFORE: String = "VTSC9215R"

        private const val TIMEOUT_SEC = 10L
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    /**
     * PENDING_RECONCILE 검증용 ODNO 단건 검색.
     *
     * 호출 시점은 거래 일자로부터 3개월 이내 — `TTTC0081R`/`VTTC0081R` 사용.
     * 3개월 이전 backfill 필요 시 별도 메서드(`CTSC9215R`/`VTSC9215R`) 합류.
     */
    override fun inquireByOrderNo(
        userId: String,
        mode: TradingMode,
        kisOrderNo: String,
        inquireStartDate: LocalDate,
        inquireEndDate: LocalDate,
    ): OrderInquireResult {
        val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: return OrderInquireResult.Failed("CREDENTIAL_NOT_FOUND")

        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)
        // raw L107·L110·L114·L117 정합 — 3개월 이내 (inner) tr_id LOCK
        val trId = if (mode == TradingMode.REAL) TR_ID_REAL_INNER else TR_ID_MOCK_INNER
        val baseUrl = if (mode == TradingMode.REAL) kisBaseProd else kisBaseVps

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

            // Rate Limit acquire (spec PR #564 §2-2 LOCK — BACKFILL priority — daily ccld reconcile)
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = trId)
            acquiredToken = true

            // raw L141~L155 verbatim — params 매트릭스
            val response =
                webClient
                    .get()
                    .uri { uriBuilder ->
                        uriBuilder
                            .scheme("https")
                            .host(baseUrl.removePrefix("https://").substringBefore(":"))
                            .port(baseUrl.substringAfterLast(":").toIntOrNull() ?: 443)
                            .path(ENDPOINT)
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", cred.accountPrdtCd)
                            .queryParam("INQR_STRT_DT", inquireStartDate.format(DATE_FORMAT))
                            .queryParam("INQR_END_DT", inquireEndDate.format(DATE_FORMAT))
                            .queryParam("SLL_BUY_DVSN_CD", "00")
                            .queryParam("PDNO", "")
                            .queryParam("CCLD_DVSN", "00")
                            .queryParam("INQR_DVSN", "00")
                            .queryParam("INQR_DVSN_3", "00")
                            .queryParam("ORD_GNO_BRNO", "")
                            .queryParam("ODNO", kisOrderNo)
                            .queryParam("INQR_DVSN_1", "")
                            .queryParam("CTX_AREA_FK100", "")
                            .queryParam("CTX_AREA_NK100", "")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(KisOdnoInquireResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    // P0 hotfix 보강 — subscription을 blocking 전용 worker에 둔다.
                    .subscribeOn(Schedulers.boundedElastic())
                    .block()
                    ?: run {
                        kisRateLimiterPort.onSuccess()
                        signalSent = true
                        return OrderInquireResult.Failed("RESPONSE_NULL")
                    }

            kisRateLimiterPort.onSuccess()
            signalSent = true

            if (response.rtCd != "0") {
                log.warn(
                    "KIS inquire-daily-ccld 비정상 응답: trId={} userId={} ODNO={} rt_cd={} msg={}",
                    trId,
                    maskUserId(userId),
                    kisOrderNo,
                    response.rtCd,
                    response.msg1,
                )
                return OrderInquireResult.Failed("RT_CD_${response.rtCd}:${response.msg1}")
            }

            val matched = response.output1?.firstOrNull { it.odno == kisOrderNo }
            if (matched == null) {
                log.info(
                    "KIS inquire-daily-ccld 미발견 (KIS 미접수 확정): trId={} userId={} ODNO={} output1Size={}",
                    trId,
                    maskUserId(userId),
                    kisOrderNo,
                    response.output1?.size ?: 0,
                )
                OrderInquireResult.NotFound
            } else {
                log.info(
                    "KIS inquire-daily-ccld 발견 (PENDING_RECONCILE → ACK·FILLED 검증): trId={} userId={} ODNO={} ordQty={} totCcldQty={} ccldDvsn={}",
                    trId,
                    maskUserId(userId),
                    matched.odno,
                    matched.ordQty,
                    matched.totCcldQty,
                    matched.ccldDvsnName,
                )
                OrderInquireResult.Found(
                    odno = matched.odno,
                    krxFwdgOrdOrgno = matched.ordGnoBrno ?: "",
                    filledQty = matched.totCcldQty?.toIntOrNull() ?: 0,
                    orderQty = matched.ordQty?.toIntOrNull() ?: 0,
                    ccldStatus = matched.ccldDvsnName ?: "",
                )
            }
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "KIS inquire-daily-ccld HTTP 실패: trId={} userId={} ODNO={} status={}",
                trId,
                maskUserId(userId),
                kisOrderNo,
                e.statusCode,
            )
            OrderInquireResult.Failed("HTTP_${e.statusCode.value()}")
        } catch (e: Exception) {
            log.warn(
                "KIS inquire-daily-ccld 예외: trId={} userId={} ODNO={} err={}",
                trId,
                maskUserId(userId),
                kisOrderNo,
                e.message,
            )
            OrderInquireResult.Failed(e.message ?: "UNKNOWN")
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] inquire-daily-ccld(ODNO) acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): trId={} ODNO={}",
                    trId,
                    kisOrderNo,
                )
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}

/**
 * KIS inquire-daily-ccld ODNO 단건 검증용 응답 DTO (P0-2 reconciliation 경로 전용).
 *
 * ### P0-3 [KisInquireDailyCcldResponse]와 분리 사유
 * - P0-3 (a7ea63e — main) baseline은 backfill 체결 list 한정(CCLD_DVSN=01) — 필드 매트릭스 limited
 * - P0-2 (본 어댑터)는 ODNO 단건 검증(CCLD_DVSN=00, 체결+미체결 모두) — 추가 필드 필요
 *   - `ord_gno_brno` (KIS 정정/취소 의무 동반 헤더)
 *   - `ccld_dvsn_name` (체결구분명 — ACK/FILLED 분기 metadata)
 *   - `cncl_yn` (취소 여부)
 *   - `sll_buy_dvsn_cd_name`·`prdt_name`·`ord_dvsn_name` (운영 audit 가시성)
 * - 동일 endpoint(inquire-daily-ccld) 호출이나 caller 요구 필드 매트릭스가 다르므로 DTO 분리.
 *
 * raw 본문에 output1 필드 이름 명시: examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
 * + examples_user 정합 (def inquire_daily_ccld) — 본 baseline은 ODNO 단건 검색이므로 output1 첫 매칭 row만 사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisOdnoInquireResponse(
    @JsonProperty("rt_cd") val rtCd: String? = null,
    @JsonProperty("msg_cd") val msgCd: String? = null,
    @JsonProperty("msg1") val msg1: String? = null,
    @JsonProperty("output1") val output1: List<KisOdnoInquireOutput1>? = null,
    @JsonProperty("ctx_area_fk100") val ctxAreaFk100: String? = null,
    @JsonProperty("ctx_area_nk100") val ctxAreaNk100: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisOdnoInquireOutput1(
    @JsonProperty("ord_dt") val ordDt: String? = null,
    @JsonProperty("ord_gno_brno") val ordGnoBrno: String? = null,
    @JsonProperty("odno") val odno: String = "",
    @JsonProperty("orgn_odno") val orgnOdno: String? = null,
    @JsonProperty("ord_dvsn_name") val ordDvsnName: String? = null,
    @JsonProperty("sll_buy_dvsn_cd") val sllBuyDvsnCd: String? = null,
    @JsonProperty("sll_buy_dvsn_cd_name") val sllBuyDvsnCdName: String? = null,
    @JsonProperty("pdno") val pdno: String? = null,
    @JsonProperty("prdt_name") val prdtName: String? = null,
    @JsonProperty("ord_qty") val ordQty: String? = null,
    @JsonProperty("ord_unpr") val ordUnpr: String? = null,
    @JsonProperty("tot_ccld_qty") val totCcldQty: String? = null,
    @JsonProperty("avg_prvs") val avgPrvs: String? = null,
    @JsonProperty("cncl_yn") val cnclYn: String? = null,
    @JsonProperty("tot_ccld_amt") val totCcldAmt: String? = null,
    @JsonProperty("ccld_dvsn_name") val ccldDvsnName: String? = null,
)
