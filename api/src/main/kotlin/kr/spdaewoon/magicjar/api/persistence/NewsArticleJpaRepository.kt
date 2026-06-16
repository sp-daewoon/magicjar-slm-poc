package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * Naver 뉴스 기사 Spring Data JPA Repository (T-NEWS Phase 2 — ADR 0075 §2-1).
 *
 * ADR 0051 default fun 함정 회피 LOCK:
 *   - interface 내부 body 가진 fun 발행 금지
 *   - abstract 선언만 허용 (Spring Data 파생 쿼리 또는 @Query)
 *   - caller class(NewsArticleRepositoryAdapter) 내부 helper로 UPSERT 처리
 *
 * 멱등성 UPSERT:
 *   existsBySourceAndStockCodeAndUrlHash() — 신규 여부 확인 후 save() 조합
 *   (ON CONFLICT source+stock_code+url_hash DO NOTHING 효과 — at-least-once 안전)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
interface NewsArticleJpaRepository : JpaRepository<NewsArticleJpaEntity, Long> {
    /**
     * (source, stock_code, url_hash) 존재 여부 — 멱등성 중복 INSERT 방지.
     * UQ ux_news_article_source_stock_url_hash 정합.
     */
    fun existsBySourceAndStockCodeAndUrlHash(
        source: String,
        stockCode: String,
        urlHash: String,
    ): Boolean

    /**
     * 종목코드 + 발행 시각 범위 조회 (기간 필터 — REST /news).
     * ix_news_article_stock_code_published_at 인덱스 최적화.
     */
    fun findByStockCodeAndPublishedAtBetweenOrderByPublishedAtDesc(
        stockCode: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<NewsArticleJpaEntity>
}
