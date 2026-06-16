package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.NewsItem
import kr.spdaewoon.magicjar.api.controller.SymbolNewsListResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.NewsArticleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 종목별 Naver 뉴스 조회 서비스 (T-NEWS Phase 2 — ADR 0075 §5).
 *
 * use case:
 *   1. stockCode 유효성 검증 (InstrumentJpaRepository)
 *   2. from/to 기간 검증 (90일 한도 — ADR 0075 §5-2)
 *   3. news_article 테이블 페이징 조회 → NewsItem 매핑
 *   4. 반환: SymbolNewsListResponse (stockCode, items, page, size, totalElements, totalPages)
 *
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 *   뉴스 없을 경우 items: [] + totalElements: 0 그대로 반환
 *   Naver 키 미등록 시 items: [] (NaverNewsAdapter graceful skip → DB row 0)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Service
class SymbolNewsService(
    private val newsArticleJpaRepository: NewsArticleJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
) {
    private val log = LoggerFactory.getLogger(SymbolNewsService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    companion object {
        private const val MAX_RANGE_DAYS = 90L
    }

    /**
     * 종목별 뉴스 리스트 조회.
     *
     * @param stockCode  KIS 종목코드 6자리
     * @param from       조회 시작 (기본값: T-7일)
     * @param to         조회 종료 (기본값: now)
     * @param page       페이지 번호 (0-based)
     * @param size       페이지 크기 (1~100)
     */
    fun getNews(
        stockCode: String,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        page: Int,
        size: Int,
    ): SymbolNewsListResponse {
        // 종목 존재 여부 검증
        if (!instrumentJpaRepository.existsByCode(stockCode)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $stockCode")
        }

        val now = OffsetDateTime.now(kst)
        val effectiveTo = to ?: now
        val effectiveFrom = from ?: now.minusDays(7)

        // 기간 검증
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from이 to보다 클 수 없습니다")
        }
        val rangeDays =
            java.time.temporal.ChronoUnit.DAYS
                .between(effectiveFrom, effectiveTo)
        if (rangeDays > MAX_RANGE_DAYS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기간은 최대 90일입니다")
        }

        val fromInstant: Instant = effectiveFrom.toInstant()
        val toInstant: Instant = effectiveTo.toInstant()

        val pageable = PageRequest.of(page, size)

        val resultPage =
            newsArticleJpaRepository
                .findByStockCodeAndPublishedAtBetweenOrderByPublishedAtDesc(
                    stockCode,
                    fromInstant,
                    toInstant,
                    pageable,
                )

        log.debug(
            "[SymbolNewsService] 조회 — stockCode={} totalElements={}",
            stockCode,
            resultPage.totalElements,
        )

        return SymbolNewsListResponse(
            stockCode = stockCode,
            items =
                resultPage.content.map { entity ->
                    NewsItem(
                        id = entity.id ?: 0L,
                        source = entity.source,
                        title = entity.title,
                        bodySummary = entity.bodySummary,
                        url = entity.url,
                        publishedAt = OffsetDateTime.ofInstant(entity.publishedAt, kst),
                        sentiment = entity.sentiment, // NULL 고정 (T-AI 사이클)
                    )
                },
            page = resultPage.number,
            size = resultPage.size,
            totalElements = resultPage.totalElements,
            totalPages = resultPage.totalPages,
        )
    }
}
