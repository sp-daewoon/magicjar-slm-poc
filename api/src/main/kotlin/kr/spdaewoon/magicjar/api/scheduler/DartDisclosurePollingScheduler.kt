package kr.spdaewoon.magicjar.api.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.infrastructure.dart.DisclosureKafkaProducer
import kr.spdaewoon.magicjar.api.persistence.DisclosureJpaEntity
import kr.spdaewoon.magicjar.api.persistence.DisclosureJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * DART 공시 1분 폴링 스케줄러 (T-NEWS Phase 1 — ADR 0075 §1-3 의제 2-1 채택).
 *
 * DART OPENDART list.json 출처 (헌법 §3 — 자체 추론 0):
 *   API 가이드: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
 *   endpoint  : https://opendart.fss.or.kr/api/list.json
 *   인증      : query parameter `crtfc_key=<40자리>` (header 아님 — 오타 risk LOCK)
 *   한도      : 20,000건/일 hard limit (error 020)
 *
 * 파라미터 (raw 1:1 정합 — ADR 0075 §7-4):
 *   crtfc_key  : 40자 API key (필수) — OPENDART_API_KEY 환경변수
 *   bgn_de     : YYYYMMDD 시작일 (당일)
 *   end_de     : YYYYMMDD 종료일 (당일)
 *   sort       : date
 *   sort_mth   : desc
 *   page_count : 100 (max)
 *
 * 응답 list[] STRING 필드 (raw 1:1 — ADR 0075 §7-4):
 *   corp_cls, corp_name, corp_code, stock_code, report_nm, rcept_no, flr_nm, rcept_dt, rm
 *
 * is_correction 분류 기준 (ADR 0075 §8 핵심 결정 LOCK):
 *   - report_nm.startsWith("[기재정정]") = true → is_correction=true
 *   - rm?.contains("정") = true → is_correction=true
 *
 * 멱등성:
 *   - DB UQ ux_disclosure_rcept_no: existsByRceptNo() 체크 후 save() (이중 보호)
 *   - Kafka: DisclosureKafkaProducer Redis SETNX 24h dedup
 *
 * rate limit:
 *   - Redis ZSET news:ratelimit:dart 분당 60건 (20K/일 ÷ 1440 ≈ 14건, 안전 마진 4배)
 *
 * SSL fix: java.net.http.HttpClient — JDK 21 TLS 스택 직접 사용 (batch DartDisclosureAdapter 동일 패턴).
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합 — T-AI 사이클 분리 LOCK)
 */
@Component
class DartDisclosurePollingScheduler(
    private val disclosureJpaRepository: DisclosureJpaRepository,
    private val disclosureKafkaProducer: DisclosureKafkaProducer,
    private val objectMapper: ObjectMapper,
    @Value("\${OPENDART_API_KEY:}")
    private val dartApiKey: String,
) {
    private val log = LoggerFactory.getLogger(DartDisclosurePollingScheduler::class.java)
    private val dtf = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * java.net.http.HttpClient — JDK 21 TLS 스택 직접 사용.
     * Reactor Netty SslContextBuilder 우회 (batch DartDisclosureAdapter 동일 fix — hotfix/dart-ssl-handshake).
     */
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    // DART OPENDART list.json endpoint
    // 출처: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
    private val dartListUrl = "https://opendart.fss.or.kr/api/list.json"

    /**
     * DART 공시 1분 폴링 (ADR 0075 §1-3 의제 2-1 — cron 1분 LOCK).
     *
     * cron: 매 1분 (0초) 실행 — Asia/Seoul 시간대 기준.
     * 장중(09:00~18:00) + 장외(공시 가능 시간대 포함) 전체 운영.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    fun poll() {
        if (dartApiKey.isBlank()) {
            log.info("[DartDisclosurePolling] OPENDART_API_KEY 미설정 — 폴링 skip (graceful degrade)")
            return
        }

        val today = LocalDate.now()
        val todayStr = today.format(dtf)

        log.debug("[DartDisclosurePolling] 1분 폴링 시작 — bgn_de={}", todayStr)

        val items = fetchPage(todayStr) ?: return

        var newCount = 0
        var skipCount = 0

        for (item in items) {
            val rceptNo = item.path("rcept_no").asText("").trim()
            if (rceptNo.isBlank()) continue

            if (disclosureJpaRepository.existsByRceptNo(rceptNo)) {
                skipCount++
                continue
            }

            val entity = buildEntity(item)
            runCatching {
                disclosureJpaRepository.save(entity)
                // Kafka publish — Redis SETNX 24h dedup 내부 처리
                disclosureKafkaProducer.publish(entity)
                newCount++
            }.onFailure { ex ->
                // UQ 충돌(race condition) — skip (멱등성 보장)
                log.debug("[DartDisclosurePolling] 저장 skip (UQ 충돌?) rceptNo={} err={}", rceptNo, ex.message)
                skipCount++
            }
        }

        log.info("[DartDisclosurePolling] 1분 폴링 완료 — 신규={}건 skip={}건", newCount, skipCount)
    }

    // =========================================================================
    // 내부 구현
    // =========================================================================

    /**
     * DART list.json 단일 페이지 조회 (당일, 최대 100건).
     *
     * endpoint: https://opendart.fss.or.kr/api/list.json
     * 출처    : https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
     *
     * @return list 배열 노드, 오류 시 null
     */
    private fun fetchPage(todayStr: String): List<JsonNode>? {
        val params =
            buildString {
                append("?crtfc_key=$dartApiKey")
                append("&bgn_de=$todayStr")
                append("&end_de=$todayStr")
                append("&sort=date")
                append("&sort_mth=desc")
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
                log.error("[DartDisclosurePolling] HTTP 에러 status={}", response.statusCode())
                return null
            }

            val root = objectMapper.readTree(response.body())
            val status = root.path("status").asText("")
            if (status == "013") {
                // 조회 결과 없음 (오늘 공시 0건)
                log.debug("[DartDisclosurePolling] 공시 없음 status=013")
                return emptyList()
            }
            if (status != "000") {
                log.warn("[DartDisclosurePolling] list.json 에러 status={} message={}", status, root.path("message").asText())
                return null
            }

            val list = root.path("list")
            if (!list.isArray) return emptyList()
            list.toList()
        } catch (ex: Exception) {
            log.error("[DartDisclosurePolling] 조회 실패 err={}", ex.message)
            null
        }
    }

    /**
     * DART list item → DisclosureJpaEntity 변환.
     *
     * is_correction 분류 (ADR 0075 §8 핵심 결정 LOCK):
     *   - report_nm.startsWith("[기재정정]") = true → is_correction=true
     *   - rm?.contains("정") = true → is_correction=true
     *
     * url 합성: https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}
     */
    private fun buildEntity(node: JsonNode): DisclosureJpaEntity {
        val corpCode = node.path("corp_code").asText("").trim()
        val corpName = node.path("corp_name").asText("").trim()
        val stockCode =
            node
                .path("stock_code")
                .asText("")
                .trim()
                .ifEmpty { null }
        val rceptNo = node.path("rcept_no").asText("").trim()
        val rceptDt = node.path("rcept_dt").asText("").trim()
        val reportNm = node.path("report_nm").asText("").trim()
        val flrNm =
            node
                .path("flr_nm")
                .asText("")
                .trim()
                .ifEmpty { null }
        val rm =
            node
                .path("rm")
                .asText("")
                .trim()
                .ifEmpty { null }
        val url = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=$rceptNo"

        val isCorrection =
            reportNm.startsWith("[기재정정]") ||
                rm?.contains("정") == true

        return DisclosureJpaEntity(
            corpCode = corpCode,
            stockCode = stockCode,
            rceptNo = rceptNo,
            rceptDt = rceptDt,
            corpName = corpName,
            reportNm = reportNm,
            flrNm = flrNm,
            rm = rm,
            url = url,
            isCorrection = isCorrection,
            ingestedAt = Instant.now(),
        )
    }
}
