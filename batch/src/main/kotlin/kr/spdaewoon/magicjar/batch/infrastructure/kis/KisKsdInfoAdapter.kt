package kr.spdaewoon.magicjar.batch.infrastructure.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.KisKsdInfoFetcher
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS ksdinfo 6 endpoint 어댑터 (batch 모듈 — T-CA1 PR-29b).
 *
 * KIS open-trading-api raw 출처 (헌법 §3 — 자체 추론 0건):
 *
 *  1. 유상증자 (paidin-capin)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/paidin-capin
 *     tr_id: HHKDB669100C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_paidin_capin/ksdinfo_paidin_capin.py
 *
 *  2. 무상증자 (bonus-issue)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/bonus-issue
 *     tr_id: HHKDB669101C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_bonus_issue/ksdinfo_bonus_issue.py
 *
 *  3. 배당 (dividend)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/dividend
 *     tr_id: HHKDB669102C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_dividend/ksdinfo_dividend.py
 *
 *  4. 합병/분할 (merger-split)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/merger-split
 *     tr_id: HHKDB669104C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_merger_split/ksdinfo_merger_split.py
 *
 *  5. 액면분할/병합 (rev-split)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/rev-split
 *     tr_id: HHKDB669105C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_rev_split/ksdinfo_rev_split.py
 *
 *  6. 자본감소 (cap-dcrs)
 *     URL  : /uapi/domestic-stock/v1/ksdinfo/cap-dcrs
 *     tr_id: HHKDB669106C0
 *     raw  : https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_cap_dcrs/ksdinfo_cap_dcrs.py
 *
 * 공통 query params (raw 정합):
 *   cts    : 연속조회 키 (페이징)
 *   gb1    : 시장구분 (0=전체, 1=KOSPI, 2=KOSDAQ)
 *   f_dt   : 조회 시작일 (YYYYMMDD)
 *   t_dt   : 조회 종료일 (YYYYMMDD)
 *   sht_cd : 종목코드 (빈문자열 = 전체 종목 조회)
 *
 * 공통 응답 필드 (raw 정합):
 *   rt_cd  : "0"=정상, 그 외=에러
 *   msg_cd : 응답 메시지 코드
 *   output : 결과 배열
 *   ctx_area_nk : 다음 연속 조회 키
 *
 * 호출 한도: ADR 0037 Rate Limiter 통과 의무 (Bucket4j — 현재 stub, W3 본격 적용).
 * 인증 실패(rt_cd != "0"): 즉시 실패 + 재시도 금지 (헌법 §에러핸들링 정합).
 */
@Component
class KisKsdInfoAdapter(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseUrl: String,
    @Value("\${magicjar.kis.ksdinfo.app-key:}")
    private val appKey: String,
    @Value("\${magicjar.kis.ksdinfo.app-secret:}")
    private val appSecret: String,
    @Value("\${magicjar.kis.ksdinfo.access-token:}")
    private val accessToken: String,
    @Value("\${magicjar.kis.ksdinfo.disabled:true}")
    private val disabled: Boolean,
) : KisKsdInfoFetcher {
    private val log = LoggerFactory.getLogger(KisKsdInfoAdapter::class.java)
    private val dtf = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val source = "KIS_KSDINFO"

    // =========================================================================
    // KisKsdInfoFetcher 구현
    // =========================================================================

    /**
     * 배당 조회 — tr_id: HHKDB669102C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/dividend
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_dividend/ksdinfo_dividend.py
     */
    override fun fetchDividend(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchDividend skip (magicjar.kis.ksdinfo.disabled=true)")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/dividend",
            trId = "HHKDB669102C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parseDividend(node) }
    }

    /**
     * 액면분할/병합 조회 — tr_id: HHKDB669105C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/rev-split
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_rev_split/ksdinfo_rev_split.py
     */
    override fun fetchRevSplit(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchRevSplit skip")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/rev-split",
            trId = "HHKDB669105C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parseRevSplit(node) }
    }

    /**
     * 합병/분할 조회 — tr_id: HHKDB669104C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/merger-split
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_merger_split/ksdinfo_merger_split.py
     */
    override fun fetchMergerSplit(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchMergerSplit skip")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/merger-split",
            trId = "HHKDB669104C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parseMergerSplit(node) }
    }

    /**
     * 무상증자 조회 — tr_id: HHKDB669101C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/bonus-issue
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_bonus_issue/ksdinfo_bonus_issue.py
     */
    override fun fetchBonusIssue(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchBonusIssue skip")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/bonus-issue",
            trId = "HHKDB669101C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parseBonusIssue(node) }
    }

    /**
     * 유상증자 조회 — tr_id: HHKDB669100C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/paidin-capin
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_paidin_capin/ksdinfo_paidin_capin.py
     */
    override fun fetchPaidinCapin(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchPaidinCapin skip")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/paidin-capin",
            trId = "HHKDB669100C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parsePaidinCapin(node) }
    }

    /**
     * 자본감소 조회 — tr_id: HHKDB669106C0
     * URL: /uapi/domestic-stock/v1/ksdinfo/cap-dcrs
     * raw: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_cap_dcrs/ksdinfo_cap_dcrs.py
     */
    override fun fetchCapDcrs(
        code: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (disabled) {
            log.warn("[KisKsdInfo] ksdinfo disabled — fetchCapDcrs skip")
            return emptyList()
        }
        return fetchPaged(
            path = "/uapi/domestic-stock/v1/ksdinfo/cap-dcrs",
            trId = "HHKDB669106C0",
            code = code,
            from = from,
            to = to,
        ) { node -> parseCapDcrs(node) }
    }

    // =========================================================================
    // 공통 페이징 조회 (cts 연속조회)
    // =========================================================================

    private fun fetchPaged(
        path: String,
        trId: String,
        code: String?,
        from: LocalDate,
        to: LocalDate,
        parser: (JsonNode) -> List<CorpAction>,
    ): List<CorpAction> {
        val result = mutableListOf<CorpAction>()
        var cts = ""
        var pageCount = 0
        val maxPages = 10 // 안전 상한 (무한 루프 방지)

        do {
            val response = callKsdInfo(path, trId, code, from, to, cts)
            val rtCd = response.path("rt_cd").asText("")
            if (rtCd != "0") {
                // 인증 실패·에러: 즉시 실패, 재시도 금지 (헌법 §에러핸들링)
                log.error("[KisKsdInfo] $trId 에러 rt_cd=$rtCd msg=${response.path("msg1").asText()}")
                break
            }
            val output = response.path("output")
            if (output.isArray) {
                result.addAll(parser(output))
            }
            cts = response.path("ctx_area_nk").asText("").trim()
            pageCount++
        } while (cts.isNotEmpty() && pageCount < maxPages)

        log.info("[KisKsdInfo] $trId 조회 완료 — code=${code ?: "전체"} from=$from to=$to pages=$pageCount 건수=${result.size}")
        return result
    }

    private fun callKsdInfo(
        path: String,
        trId: String,
        code: String?,
        from: LocalDate,
        to: LocalDate,
        cts: String,
    ): JsonNode {
        val uri =
            "$kisBaseUrl$path" +
                "?cts=$cts" +
                "&gb1=0" + // 전체 시장
                "&f_dt=${from.format(dtf)}" +
                "&t_dt=${to.format(dtf)}" +
                "&sht_cd=${code ?: ""}"

        // Rate Limit acquire (D-2 합류 — BACKFILL priority — ksdinfo는 cron 기반 backfill path)
        // try 안 진입 + signalSent flag로 drift 차단 (D-1 패턴 정합).
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = trId)
            val body =
                webClient
                    .get()
                    .uri(uri)
                    .header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", trId)
                    .header("content-type", "application/json; charset=utf-8")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() ?: "{}"
            kisRateLimiterPort.onSuccess()
            signalSent = true
            objectMapper.readTree(body)
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error("[KisKsdInfo] HTTP 에러 $trId status=${e.statusCode} body=${e.responseBodyAsString.take(200)}")
            objectMapper.createObjectNode()
        } catch (e: Exception) {
            log.error("[KisKsdInfo] 조회 실패 $trId ${e.message}")
            objectMapper.createObjectNode()
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] ksdinfo acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): trId={}",
                    trId,
                )
            }
        }
    }

    // =========================================================================
    // 파서 (각 endpoint output 배열 → CorpAction 도메인)
    // raw 필드명은 KIS open-trading-api Python 예제와 1:1 정합
    // =========================================================================

    /**
     * 배당 파서 (HHKDB669102C0 dividend).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, stck_dvdn_dt : 배당락일, cas_dvdn_amt : 현금배당금,
     *   stk_dvdn_rt : 주식배당률, dvdn_knd_cd : 배당종류코드
     */
    private fun parseDividend(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("stck_dvdn_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                val cashAmt =
                    node
                        .path("cas_dvdn_amt")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull()
                val stockRt =
                    node
                        .path("stk_dvdn_rt")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull()
                val type =
                    if ((stockRt ?: BigDecimal.ZERO) > BigDecimal.ZERO) {
                        CorpActionType.STOCK_DIVIDEND
                    } else {
                        CorpActionType.CASH_DIVIDEND
                    }
                CorpAction(
                    instrumentCode = instrCode,
                    type = type,
                    exDate = exDate,
                    cashDividend = cashAmt,
                    ratio = stockRt,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    /**
     * 액면분할/병합 파서 (HHKDB669105C0 rev-split).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, stk_splt_dt : 분할기준일,
     *   bfr_face_val : 분할 전 액면가, aft_face_val : 분할 후 액면가
     *   분할비율 = bfr_face_val / aft_face_val (예: 5000/100 = 50 → 1:50 분할)
     */
    private fun parseRevSplit(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("stk_splt_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                val bfrFace =
                    node
                        .path("bfr_face_val")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull() ?: BigDecimal.ONE
                val aftFace =
                    node
                        .path("aft_face_val")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull() ?: BigDecimal.ONE
                val ratio = if (aftFace > BigDecimal.ZERO) bfrFace.divide(aftFace, 8, java.math.RoundingMode.HALF_UP) else BigDecimal.ONE
                val type = if (ratio > BigDecimal.ONE) CorpActionType.STOCK_SPLIT else CorpActionType.REVERSE_SPLIT
                CorpAction(
                    instrumentCode = instrCode,
                    type = type,
                    exDate = exDate,
                    ratio = ratio,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    /**
     * 합병/분할 파서 (HHKDB669104C0 merger-split).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, mrg_dt : 합병기준일,
     *   mrg_knd_cd : 합병종류코드 (1=합병, 2=분할합병, 3=분할)
     */
    private fun parseMergerSplit(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("mrg_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                val kindCd = node.path("mrg_knd_cd").asText("1").trim()
                val type = if (kindCd == "3") CorpActionType.SPINOFF else CorpActionType.MERGER
                CorpAction(
                    instrumentCode = instrCode,
                    type = type,
                    exDate = exDate,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    /**
     * 무상증자 파서 (HHKDB669101C0 bonus-issue).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, grts_stck_dt : 무상증자 기준일,
     *   grts_rt : 무상증자율 (예: 10 → 10%)
     */
    private fun parseBonusIssue(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("grts_stck_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                val ratio =
                    node
                        .path("grts_rt")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull()
                        ?.divide(BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP)
                CorpAction(
                    instrumentCode = instrCode,
                    type = CorpActionType.BONUS_ISSUE,
                    exDate = exDate,
                    ratio = ratio,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    /**
     * 유상증자 파서 (HHKDB669100C0 paidin-capin).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, paidin_capin_dt : 유상증자 기준일,
     *   new_stck_cnt : 신주 발행 수
     */
    private fun parsePaidinCapin(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("paidin_capin_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                CorpAction(
                    instrumentCode = instrCode,
                    type = CorpActionType.RIGHTS_OFFERING,
                    exDate = exDate,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    /**
     * 자본감소 파서 (HHKDB669106C0 cap-dcrs).
     * 주요 output 필드 (raw 정합):
     *   sht_cd : 종목코드, cap_dcrs_dt : 자본감소 기준일,
     *   cap_dcrs_rt : 감소율
     */
    private fun parseCapDcrs(output: JsonNode): List<CorpAction> =
        output.mapNotNull { node ->
            runCatching {
                val instrCode =
                    node
                        .path("sht_cd")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val exDateStr = node.path("cap_dcrs_dt").asText("").trim()
                val exDate = parseDate(exDateStr) ?: return@mapNotNull null
                val ratio =
                    node
                        .path("cap_dcrs_rt")
                        .asText("0")
                        .trim()
                        .toBigDecimalOrNull()
                CorpAction(
                    instrumentCode = instrCode,
                    type = CorpActionType.CAPITAL_DECREASE,
                    exDate = exDate,
                    ratio = ratio,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.getOrNull()
        }

    // =========================================================================
    // 유틸
    // =========================================================================

    private fun parseDate(yyyymmdd: String): LocalDate? =
        runCatching {
            if (yyyymmdd.length == 8 && yyyymmdd != "00000000") {
                LocalDate.parse(yyyymmdd, dtf)
            } else {
                null
            }
        }.getOrNull()
}
