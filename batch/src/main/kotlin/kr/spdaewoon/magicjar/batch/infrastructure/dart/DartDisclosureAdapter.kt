package kr.spdaewoon.magicjar.batch.infrastructure.dart

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.DartDisclosureFetcher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * DART OPENDART 공시 목록 어댑터 (batch 모듈 — T-CA1 PR-29b).
 *
 * DART OPENDART list.json 출처 (헌법 §3 — 자체 추론 0):
 *   API 가이드: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
 *   endpoint  : https://opendart.fss.or.kr/api/list.json
 *   encoding  : UTF-8
 *
 * 파라미터 (raw 1:1 정합):
 *   crtfc_key      : 40자 API key (필수) — OPENDART_API_KEY 환경변수
 *   corp_code      : 8자 고유회사코드 (선택 — 빈값=전체)
 *   bgn_de, end_de : YYYYMMDD 조회기간
 *   pblntf_ty      : B=주요사항보고서 (액면분할·합병·증자), D=지분공시 (배당)
 *   page_no        : default 1
 *   page_count     : default 10, 최대 100
 *
 * 응답 list 필드 (raw 1:1):
 *   corp_name, corp_code, stock_code, report_nm, rcept_no, rcept_dt, flr_nm, rm
 *
 * 한도: 1일 10,000건 (external-api-policy.md §4-1).
 * corp_code ↔ stock_code 매핑: DartCorpCodeRepository (in-memory).
 * 본문 상세(document.xml) fetch: 비범위 (ADR 0018 §4-7 — list만).
 *
 * [SSL fix — hotfix/dart-ssl-handshake]
 * Reactor Netty + SslProvider.JDK 조합에서 opendart.fss.or.kr SSL handshake_failure 발생.
 * Fix (H3): java.net.http.HttpClient 전환 — JDK 21 TLS 스택 직접 사용.
 *
 * OPENDART_API_KEY 미발급 시: WARN 로그 + empty 반환 (graceful degrade — ADR 0018 §4-8).
 */
@Component
class DartDisclosureAdapter(
    private val objectMapper: ObjectMapper,
    private val dartCorpCodeRepository: DartCorpCodeRepository,
    @Value("\${OPENDART_API_KEY:}")
    private val dartApiKey: String,
) : DartDisclosureFetcher {
    private val log = LoggerFactory.getLogger(DartDisclosureAdapter::class.java)
    private val dtf = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * java.net.http.HttpClient — JDK 21 TLS 스택 직접 사용.
     * Reactor Netty SslContextBuilder 우회 (cipher list 제한 문제 해결).
     */
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    // DART OPENDART list.json endpoint
    // 출처: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
    private val dartListUrl = "https://opendart.fss.or.kr/api/list.json"

    private val source = "DART"

    override fun fetchByCode(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (dartApiKey.isBlank()) {
            log.warn("[DART] OPENDART_API_KEY 미설정 — fetchByCode skip (graceful degrade)")
            return emptyList()
        }
        val corpCode = dartCorpCodeRepository.findCorpCodeByStockCode(code)
        if (corpCode == null) {
            log.warn("[DART] stock_code=$code 에 해당하는 corp_code 없음 — skip")
            return emptyList()
        }
        return fetchDisclosures(corpCode, from, to)
    }

    override fun fetchRecent(
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        if (dartApiKey.isBlank()) {
            log.warn("[DART] OPENDART_API_KEY 미설정 — fetchRecent skip (graceful degrade)")
            return emptyList()
        }
        // 전체 종목 — corp_code 빈값으로 호출 (DART 전체 조회)
        return fetchDisclosures(corpCode = null, from = from, to = to)
    }

    // =========================================================================
    // 내부 구현
    // =========================================================================

    private fun fetchDisclosures(
        corpCode: String?,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        val result = mutableListOf<CorpAction>()
        // pblntf_ty=B (주요사항보고서) + D (지분공시) 순차 조회
        for (pblntfTy in listOf("B", "D")) {
            result.addAll(fetchPage(corpCode, from, to, pblntfTy))
        }
        log.info("[DART] 공시 조회 완료 — corpCode=${corpCode ?: "전체"} from=$from to=$to 건수=${result.size}")
        return result
    }

    /**
     * 단일 pblntf_ty 페이지 조회 (최대 100건 단일 페이지).
     *
     * endpoint: https://opendart.fss.or.kr/api/list.json
     * 출처: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
     *
     * java.net.http.HttpClient 사용 — JDK 21 TLS 직접 (Reactor Netty cipher 제한 우회).
     */
    private fun fetchPage(
        corpCode: String?,
        from: LocalDate,
        to: LocalDate,
        pblntfTy: String,
    ): List<CorpAction> {
        val params =
            buildString {
                append("?crtfc_key=$dartApiKey")
                if (!corpCode.isNullOrBlank()) append("&corp_code=$corpCode")
                append("&bgn_de=${from.format(dtf)}")
                append("&end_de=${to.format(dtf)}")
                append("&pblntf_ty=$pblntfTy")
                append("&page_no=1&page_count=100")
            }

        return try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$dartListUrl$params"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json; charset=UTF-8")
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (response.statusCode() != 200) {
                log.error("[DART] HTTP 에러 status=${response.statusCode()} body=${response.body().take(200)}")
                return emptyList()
            }

            val root = objectMapper.readTree(response.body())
            val status = root.path("status").asText("")
            if (status != "000") {
                log.warn("[DART] list.json 에러 status=$status message=${root.path("message").asText()}")
                return emptyList()
            }
            val list = root.path("list")
            if (!list.isArray) return emptyList()
            parseDisclosureList(list, pblntfTy)
        } catch (e: Exception) {
            log.error("[DART] 조회 실패 ${e.message}")
            emptyList()
        }
    }

    /**
     * DART list 배열 파싱 → CorpAction 후보 목록.
     *
     * report_nm 패턴으로 corp_action type 추정 (DART 공시 본문 미fetch — list만, ADR 0018 §4-7).
     * 매칭 안 되는 공시 유형은 skip.
     *
     * 응답 필드 (raw 1:1, 출처: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001):
     *   corp_name, corp_code, stock_code, report_nm, rcept_no, rcept_dt, flr_nm, rm
     */
    private fun parseDisclosureList(
        list: JsonNode,
        pblntfTy: String,
    ): List<CorpAction> =
        list.mapNotNull { node ->
            runCatching {
                val stockCode =
                    node
                        .path("stock_code")
                        .asText("")
                        .trim()
                        .ifEmpty { return@mapNotNull null }
                val reportNm = node.path("report_nm").asText("").trim()
                val rcptDt = node.path("rcept_dt").asText("").trim()
                val exDate =
                    runCatching { LocalDate.parse(rcptDt, dtf) }.getOrNull() ?: return@mapNotNull null

                val type = inferCorpActionType(reportNm, pblntfTy) ?: return@mapNotNull null

                CorpAction(
                    instrumentCode = stockCode,
                    type = type,
                    exDate = exDate,
                    source = source,
                    rawPayload = objectMapper.writeValueAsString(node),
                )
            }.also { r ->
                if (r.isFailure) {
                    log.debug("[DART] 파싱 실패 — node=${node.toString().take(100)} err=${r.exceptionOrNull()?.message}")
                }
            }.getOrNull()
        }

    /**
     * report_nm 기반 corp_action type 추정 (DART 본문 미fetch 한계 인정 — ADR 0018 §4-7).
     *
     * pblntf_ty=B 주요사항보고서 키워드:
     *   "합병" → MERGER, "분할" → SPINOFF, "액면분할"/"주식분할" → STOCK_SPLIT
     *   "액면병합"/"주식병합" → REVERSE_SPLIT, "유상증자" → RIGHTS_OFFERING
     *   "무상증자" → BONUS_ISSUE, "자본감소"/"감자" → CAPITAL_DECREASE
     * pblntf_ty=D 지분공시:
     *   "배당" → CASH_DIVIDEND
     */
    private fun inferCorpActionType(
        reportNm: String,
        pblntfTy: String,
    ): CorpActionType? {
        if (pblntfTy == "D" && reportNm.contains("배당")) return CorpActionType.CASH_DIVIDEND
        if (reportNm.contains("합병")) return CorpActionType.MERGER
        if (reportNm.contains("액면분할") || reportNm.contains("주식분할")) return CorpActionType.STOCK_SPLIT
        if (reportNm.contains("액면병합") || reportNm.contains("주식병합")) return CorpActionType.REVERSE_SPLIT
        if (reportNm.contains("분할") && !reportNm.contains("액면") && !reportNm.contains("주식분할")) {
            return CorpActionType.SPINOFF
        }
        if (reportNm.contains("유상증자")) return CorpActionType.RIGHTS_OFFERING
        if (reportNm.contains("무상증자")) return CorpActionType.BONUS_ISSUE
        if (reportNm.contains("자본감소") || reportNm.contains("감자")) return CorpActionType.CAPITAL_DECREASE
        if (reportNm.contains("상장폐지")) return CorpActionType.DELISTING_NOTICE
        return null
    }
}
