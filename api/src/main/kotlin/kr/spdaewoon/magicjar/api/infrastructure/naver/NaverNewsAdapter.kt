package kr.spdaewoon.magicjar.api.infrastructure.naver

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.NewsArticleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.NewsArticleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Naver Open API search/news.json 어댑터 (T-NEWS Phase 2 — ADR 0075 §7-5 KIS-first raw 인용).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Naver Open API search/news.json 공식 가이드 raw 인용 (헌법 §3 — 자체 추론 0)
 *   출처    : https://developers.naver.com/docs/serviceapi/search/news/news.md
 *   endpoint: https://openapi.naver.com/v1/search/news.json (JSON · XML 폐기)
 *   HTTP    : HTTPS · GET method only
 *
 * Request headers (필수):
 *   X-Naver-Client-Id     : {애플리케이션 등록 시 발급받은 클라이언트 아이디 값}
 *   X-Naver-Client-Secret : {애플리케이션 등록 시 발급받은 클라이언트 시크릿 값}
 *
 * Query parameters (ADR 0075 §7-5 parameter 표 — raw 1:1):
 *   query   : String  필수  검색어 (UTF-8 encoded)
 *   display : Integer N    1~100 (default 10)
 *   start   : Integer N    1~1000 (default 1)
 *   sort    : String  N    sim 정확도순 (default) · date 날짜순
 *
 * Response items[] 필드 (raw 1:1 매핑 — ADR 0075 §7-5):
 *   title        : String  <b> 태그 포함 — backend-core 영속 전 strip 의무
 *   originallink : String  뉴스 원문 URL — 멱등성 hash 기반
 *   link         : String  네이버 뉴스 URL
 *   description  : String  패시지 요약 · <b> 태그 포함 — strip 의무
 *   pubDate      : String  dateTime RFC 822 (EEE, dd MMM yyyy HH:mm:ss Z 파싱 의무)
 *
 * 한도: 25,000건/일 hard limit (검색 API 공통)
 * 권한 403: 네이버 개발자 센터 > 내 애플리케이션 > API 설정 > 검색 체크 의무
 *
 * Error codes:
 *   SE01 400 Incorrect query request  SE02 400 Invalid display value
 *   SE03 400 Invalid start value      SE04 400 Invalid sort value
 *   SE05 404 Invalid search api       SE06 400 Malformed encoding
 *   SE99 500 System Error
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * graceful skip:
 *   NAVER_OPEN_API_CLIENT_ID 미설정 시 fetch 0 + log INFO "Naver API 키 미등록 — 폴링 skip"
 *   403 응답 시 log ERROR + 24h backoff (재시도 폭주 방지)
 *
 * url_hash: SHA-256(originallink) → 64자 hex (ADR 0075 §2-2 멱등성 KEY)
 * <b> strip: title + description 양쪽 적용 (ADR 0075 §8-3 spec §2-2 결정 §4)
 *
 * secretKey log 금지: MaskingPatternLayout reuse (ADR 0046 §2-5 정합 — @Value 주입, log에 미노출)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Component
class NaverNewsAdapter(
    private val newsArticleJpaRepository: NewsArticleJpaRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${naver.open-api.client-id:}") private val clientId: String,
    @Value("\${naver.open-api.client-secret:}") private val clientSecret: String,
) {
    private val log = LoggerFactory.getLogger(NaverNewsAdapter::class.java)

    companion object {
        /** Naver Open API search/news.json endpoint (ADR 0075 §7-5 raw 인용) */
        private const val NAVER_NEWS_ENDPOINT = "https://openapi.naver.com/v1/search/news.json"

        /** display: 종목당 최대 20건 (분당 17건 한도 × 30종목 = 510건/분 상한 — Watchlist 종목당 1건 cron 조정 정합) */
        private const val DISPLAY = 20

        /** sort=date 최신순 (ADR 0075 §9-2 시나리오 정합 — 신규 기사 우선) */
        private const val SORT = "date"

        /** HTTP 연결 timeout */
        private val HTTP_TIMEOUT = Duration.ofSeconds(5)

        /** 소스 식별자 (ADR 0075 §5-3 NewsItem.source LOCK) */
        const val SOURCE = "NAVER"

        /** <b> 태그 제거 regex (검색어 강조 태그 strip — ADR 0075 spec §2-2 결정 4) */
        private val B_TAG_REGEX = Regex("</?b>", RegexOption.IGNORE_CASE)

        /**
         * pubDate RFC 822 파싱 포매터 (ADR 0075 spec §2-2 결정 4).
         *
         * Naver pubDate 형식: "Mon, 26 May 2026 07:50:00 +0900"
         * Java SimpleDateFormat "EEE, dd MMM yyyy HH:mm:ss Z" — RFC 822 +0900 완전 호환.
         * DateTimeFormatter는 +0900 (RFC 822 zone) 미지원 → SimpleDateFormat 우회.
         */
        private val RFC_822_PATTERN = "EEE, dd MMM yyyy HH:mm:ss Z"

        /** SHA-256 MessageDigest algorithm */
        private const val SHA_256 = "SHA-256"
    }

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build()

    /**
     * 종목코드 + 종목명으로 Naver 뉴스 검색 후 news_article 영속.
     *
     * 1. API 키 미설정 → graceful skip (log INFO)
     * 2. Rate limit 초과 → skip + log WARN (NaverNewsRateLimiter 책임 — 호출 전 acquireSlot)
     * 3. HTTP 200 → items[] 파싱 → url_hash 계산 → <b> strip → 멱등성 check → save
     * 4. HTTP 403 → log ERROR (검색 API 권한 없음)
     * 5. 저장된 신규 entity 목록 반환 (NewsKafkaProducer publish 용)
     *
     * @param stockCode  KRX 종목코드 6자리
     * @param query      검색어 (종목명 — 예: "삼성전자")
     * @return 신규 저장된 NewsArticleJpaEntity 목록 (Kafka publish 대상)
     */
    fun fetchAndSave(
        stockCode: String,
        query: String,
    ): List<NewsArticleJpaEntity> {
        // API 키 미설정 graceful skip
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.info("[NaverNewsAdapter] Naver API 키 미등록 — 폴링 skip (stockCode={})", stockCode)
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "$NAVER_NEWS_ENDPOINT?query=$encodedQuery&display=$DISPLAY&sort=$SORT"

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret)
                .header("Accept", "application/json")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            handleResponse(response, stockCode, query)
        } catch (ex: Exception) {
            log.error(
                "[NaverNewsAdapter] HTTP 호출 실패 — stockCode={} query={} err={}",
                stockCode,
                query,
                ex.message,
            )
            emptyList()
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun handleResponse(
        response: HttpResponse<String>,
        stockCode: String,
        query: String,
    ): List<NewsArticleJpaEntity> {
        when (response.statusCode()) {
            200 -> {
                log.debug(
                    "[NaverNewsAdapter] 200 OK — stockCode={} query={}",
                    stockCode,
                    query,
                )
                return parseAndSave(response.body(), stockCode)
            }
            403 -> {
                log.error(
                    "[NaverNewsAdapter] 403 Forbidden — 검색 API 권한 없음. " +
                        "네이버 개발자 센터 > 내 애플리케이션 > API 설정 > '검색' 체크 의무 (ADR 0075 §7-5). " +
                        "stockCode={} query={}",
                    stockCode,
                    query,
                )
                return emptyList()
            }
            else -> {
                log.warn(
                    "[NaverNewsAdapter] HTTP {} — stockCode={} query={} body={}",
                    response.statusCode(),
                    stockCode,
                    query,
                    response.body().take(200),
                )
                return emptyList()
            }
        }
    }

    private fun parseAndSave(
        body: String,
        stockCode: String,
    ): List<NewsArticleJpaEntity> {
        val root: JsonNode =
            try {
                objectMapper.readTree(body)
            } catch (ex: Exception) {
                log.error("[NaverNewsAdapter] JSON 파싱 실패 — stockCode={} err={}", stockCode, ex.message)
                return emptyList()
            }

        val items = root.path("items")
        if (!items.isArray) {
            log.warn("[NaverNewsAdapter] items 필드 없음 — stockCode={}", stockCode)
            return emptyList()
        }

        val saved = mutableListOf<NewsArticleJpaEntity>()

        for (item in items) {
            val originalLink = item.path("originallink").asText("").trim()
            if (originalLink.isBlank()) continue

            val urlHash = sha256Hex(originalLink)

            // 멱등성 체크 — 이미 존재하면 skip
            if (newsArticleJpaRepository.existsBySourceAndStockCodeAndUrlHash(SOURCE, stockCode, urlHash)) {
                log.debug("[NaverNewsAdapter] dedup skip — stockCode={} urlHash={}", stockCode, urlHash)
                continue
            }

            val title = stripBTag(item.path("title").asText("").trim())
            val bodySummary = stripBTag(item.path("description").asText("").trim()).ifBlank { null }
            val pubDateStr = item.path("pubDate").asText("").trim()
            val publishedAt = parsePubDate(pubDateStr) ?: Instant.now()

            val entity =
                NewsArticleJpaEntity(
                    source = SOURCE,
                    stockCode = stockCode,
                    title = title,
                    bodySummary = bodySummary,
                    url = originalLink,
                    urlHash = urlHash,
                    publishedAt = publishedAt,
                )

            saved.add(newsArticleJpaRepository.save(entity))
            log.debug(
                "[NaverNewsAdapter] 저장 — stockCode={} urlHash={} title={}",
                stockCode,
                urlHash,
                title.take(40),
            )
        }

        log.info(
            "[NaverNewsAdapter] {} 처리 완료 — stockCode={} items={} 신규={}",
            SOURCE,
            stockCode,
            items.size(),
            saved.size,
        )

        return saved
    }

    /**
     * <b> 태그 제거 + HTML entity decode (ADR 0075 spec §2-2 결정 4).
     *
     * 예: "<b>삼성전자</b> 반도체" → "삼성전자 반도체"
     * hotfix 2026-05-15: Naver 응답 title·description 에 HTML entity 잔존 (&quot; &amp; &lt; &gt; &#39;).
     * frontend 가 raw 표시 시 entity 그대로 노출되어 사용자 본대로 깨짐 — backend 영속 전 decode 의무.
     */
    fun stripBTag(input: String): String =
        input
            .replace(B_TAG_REGEX, "")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&") // &amp; 는 마지막 (다른 entity 가 &amp; 로 시작하지 않음)

    /**
     * pubDate RFC 822 파싱 → Instant (ADR 0075 spec §2-2 결정 4).
     *
     * Naver pubDate 형식: "Mon, 26 May 2026 07:50:00 +0900"
     * SimpleDateFormat lenient=true 사용 — RFC 822 +0900 (4자리 숫자 zone) 완전 호환.
     * DateTimeFormatter.RFC_1123_DATE_TIME은 +0900 미지원 → SimpleDateFormat 우회.
     */
    fun parsePubDate(pubDate: String): Instant? {
        if (pubDate.isBlank()) return null
        return try {
            val sdf =
                java.text.SimpleDateFormat(RFC_822_PATTERN, Locale.ENGLISH).apply {
                    isLenient = true // RFC 822 +0900 zone 파싱을 위해 lenient 필요
                }
            val date = sdf.parse(pubDate)
            date?.toInstant()
        } catch (ex: Exception) {
            log.warn("[NaverNewsAdapter] pubDate 파싱 실패 — pubDate='{}' err={}", pubDate, ex.message)
            null
        }
    }

    /**
     * SHA-256 해시 계산 → 64자 hex (ADR 0075 §2-2 url_hash 멱등성 KEY).
     *
     * 예: "https://n.news.naver.com/..." → "a3f5b1...64자"
     * MessageDigest.getInstance("SHA-256") 표준 적용 (spec §2-2 결정 5)
     */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance(SHA_256)
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
