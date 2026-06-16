package kr.spdaewoon.magicjar.batch.infrastructure.dart

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * DART CORPCODE.xml 매핑 레포지터리 (batch 모듈 — in-memory cache).
 *
 * DART CORPCODE.xml 출처 (ADR 0018 §4-1 정합):
 *   https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key={key}  (ZIP)
 *   3.5MB ZIP, 내부 CORPCODE.xml — 전체 상장사 corp_code ↔ stock_code 매핑.
 *   6시간 캐시 (external-api-policy.md §4-2 §6시간 캐시 정합).
 *
 * corp_code: DART 8자리 고유 회사코드
 * stock_code: KIS 6자리 종목코드 (빈값 = 비상장)
 *
 * [SSL fix — hotfix/dart-ssl-handshake]
 * Reactor Netty + SslProvider.JDK 조합에서 opendart.fss.or.kr SSL handshake_failure 발생.
 * Root cause: Netty SslContextBuilder가 enabled cipher list를 제한하여 DART 서버
 * (TLS_DHE_RSA_WITH_AES_128_CBC_SHA256)와 공통 cipher 협상 불가.
 * Fix (H3): java.net.http.HttpClient 전환 — JDK 21 TLS 스택 직접 사용으로 동일 환경에서 성공 확인.
 * 참조 endpoint: https://opendart.fss.or.kr/api/corpCode.xml (ADR 0018 §4-1)
 *
 * OPENDART_API_KEY 미발급 시: 빈 map 유지 (graceful degrade — ADR 0018 §4-8).
 */
@Component
class DartCorpCodeRepository(
    @Value("\${OPENDART_API_KEY:}")
    private val dartApiKey: String,
) {
    private val log = LoggerFactory.getLogger(DartCorpCodeRepository::class.java)

    /**
     * java.net.http.HttpClient — JDK 21 TLS 스택 직접 사용.
     * Reactor Netty SslContextBuilder 우회 (cipher list 제한 문제 해결).
     * DART ZIP 다운로드(3.5MB) 대응: connectTimeout 10s, readTimeout 30s.
     */
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** stock_code(6자리) → corp_code(8자리) */
    private val stockToCorpCode = ConcurrentHashMap<String, String>()

    /** corp_code(8자리) → stock_code(6자리) */
    private val corpToStockCode = ConcurrentHashMap<String, String>()

    private var lastLoadedAt: Instant? = null
    private val cacheTtl = Duration.ofHours(6)

    /**
     * 부팅 시 1회 CORPCODE.xml 로드.
     * OPENDART_API_KEY 미발급 시 WARN 로그만 (graceful degrade).
     */
    @PostConstruct
    fun init() {
        if (dartApiKey.isBlank()) {
            log.warn("[DART] OPENDART_API_KEY 미설정 — CorpCode 매핑 로드 skip (DartDisclosureAdapter graceful degrade)")
            return
        }
        loadCorpCodes()
    }

    fun findCorpCodeByStockCode(stockCode: String): String? = stockToCorpCode[stockCode.padStart(6, '0')]

    fun findStockCodeByCorpCode(corpCode: String): String? = corpToStockCode[corpCode]

    /**
     * 6시간 캐시 만료 시 재로드 (CorpActionSyncJob 호출 가능).
     */
    fun refreshIfStale() {
        val now = Instant.now()
        val last = lastLoadedAt
        if (last == null || now.isAfter(last.plus(cacheTtl))) {
            log.info("[DART] CorpCode 캐시 만료 — 재로드 시작")
            loadCorpCodes()
        }
    }

    private fun loadCorpCodes() {
        // DART CORPCODE.xml ZIP 다운로드
        // 출처: https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key={key}
        // java.net.http.HttpClient 사용 — JDK 21 TLS 직접 (Reactor Netty cipher 제한 우회)
        val zipUrl = "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=$dartApiKey"
        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(zipUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                log.error("[DART] CorpCode ZIP 다운로드 실패 — HTTP status=${response.statusCode()}")
                return
            }

            parseCorpCodeZip(response.body())
            lastLoadedAt = Instant.now()
            log.info("[DART] CorpCode 매핑 로드 완료 — 총 ${stockToCorpCode.size}건 (상장사 기준)")
        } catch (e: Exception) {
            log.error("[DART] CorpCode ZIP 로드 실패 — ${e.message}")
        }
    }

    /**
     * CORPCODE.xml ZIP 파싱.
     * XML 구조: <result><list><corp_code>...</corp_code><stock_code>...</stock_code>...</list></result>
     */
    private fun parseCorpCodeZip(bytes: ByteArray) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".xml", ignoreCase = true)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    parseXml(xml)
                    break
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun parseXml(xml: String) {
        val listPattern = Regex("<list>(.*?)</list>", RegexOption.DOT_MATCHES_ALL)
        val corpCodePattern = Regex("<corp_code>(.*?)</corp_code>")
        val stockCodePattern = Regex("<stock_code>(.*?)</stock_code>")

        var count = 0
        for (match in listPattern.findAll(xml)) {
            val block = match.groupValues[1]
            val corpCode =
                corpCodePattern
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.trim() ?: continue
            val stockCode =
                stockCodePattern
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.trim() ?: continue
            if (stockCode.isNotEmpty() && stockCode != " " && stockCode.length == 6) {
                stockToCorpCode[stockCode] = corpCode
                corpToStockCode[corpCode] = stockCode
                count++
            }
        }
        log.debug("[DART] CorpCode XML 파싱 완료 — 상장 매핑 $count 건")
    }
}
