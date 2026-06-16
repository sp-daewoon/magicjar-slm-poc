package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Naver 뉴스 기사 JPA 엔티티 (T-NEWS Phase 2 — ADR 0075 §2-1).
 *
 * 테이블: news_article (V33__t_news_article_table.sql)
 * 멱등성: ux_news_article_source_stock_url_hash (source, stock_code, url_hash) UQ
 * 인덱스: ix_news_article_stock_code_published_at + ix_news_article_published_at
 *
 * Naver Open API search/news.json 출처 (헌법 §3 — 자체 추론 0):
 *   API 가이드: https://developers.naver.com/docs/serviceapi/search/news/news.md
 *   endpoint  : https://openapi.naver.com/v1/search/news.json
 *   인증      : HTTP header X-Naver-Client-Id + X-Naver-Client-Secret
 *   한도      : 25,000건/일 hard limit
 *
 * items[] 필드 raw 1:1 매핑 (ADR 0075 §7-5):
 *   title        → title         (<b> 태그 strip 후 영속)
 *   originallink → url           (멱등성 hash 기반)
 *   originallink → url_hash      (SHA-256 64자 hex)
 *   description  → body_summary  (<b> 태그 strip 후 영속)
 *   pubDate      → published_at  (RFC 822 파싱 → Instant)
 *
 * sentiment: NULL 고정 (T-AI 흡수 backlog §62 — INSERT 시 null, T-AI 사이클에서 UPDATE)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합 — sentiment NULL 고정)
 */
@Entity
@Table(name = "news_article")
class NewsArticleJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "source", length = 50, nullable = false)
    val source: String,
    @Column(name = "stock_code", length = 20, nullable = false)
    val stockCode: String,
    @Column(name = "title", columnDefinition = "TEXT", nullable = false)
    val title: String,
    @Column(name = "body_summary", columnDefinition = "TEXT", nullable = true)
    val bodySummary: String?,
    @Column(name = "url", length = 500, nullable = false)
    val url: String,
    @Column(name = "url_hash", length = 64, nullable = false)
    val urlHash: String,
    @Column(name = "published_at", nullable = false)
    val publishedAt: Instant,
    @Column(name = "ingested_at", nullable = false, updatable = false)
    val ingestedAt: Instant = Instant.now(),
    @Column(name = "sentiment", length = 20, nullable = true)
    val sentiment: String? = null, // T-AI 사이클 분리 — 1차 NULL 고정
) {
    protected constructor() : this(
        source = "",
        stockCode = "",
        title = "",
        bodySummary = null,
        url = "",
        urlHash = "",
        publishedAt = Instant.EPOCH,
    )
}
