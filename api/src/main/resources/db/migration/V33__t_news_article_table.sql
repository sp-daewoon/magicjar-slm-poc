-- V33__t_news_article_table.sql
-- T-NEWS Phase 2 — Naver 뉴스 row 영속 테이블 (ADR 0075 §8-3 V33 분리 LOCK)
--
-- V32 disclosure (Phase 1 market-data) 에 이어 V33 news_article 분리:
--   - Phase 1·2 직렬 LOCK (ADR 0075 §8-2) → V32 revert 단위 분리 가능
--   - rollback: DROP TABLE IF EXISTS news_article → V33 단독 revert
--
-- 멱등성: ux_news_article_source_stock_url_hash (source, stock_code, url_hash) UQ
--   동일 URL이 여러 종목에 매핑될 수 있으므로 source × stock × URL 3-tuple 멱등성 (ADR 0075 §2-2).
--   url_hash = SHA-256(originallink) — 64자 hex (backend-core NaverNewsAdapter 계산).
--
-- Kafka at-least-once 재처리 시 ON CONFLICT DO NOTHING 안전.

CREATE TABLE IF NOT EXISTS news_article (
    id            BIGSERIAL    PRIMARY KEY,
    source        VARCHAR(50)  NOT NULL,            -- "NAVER" | "RSS_HANKYUNG" | "RSS_GOOGLE" (Phase 4 잔류)
    stock_code    VARCHAR(20)  NOT NULL,            -- KRX 종목코드 6자리 — 종목 매핑 의무
    title         TEXT         NOT NULL,            -- 뉴스 제목 (Naver <b> 태그 제거 후 영속)
    body_summary  TEXT,                             -- description 패시지 요약 (<b> 태그 제거 후 영속)
    url           VARCHAR(500) NOT NULL,            -- originallink — 멱등성 hash 기반
    url_hash      VARCHAR(64)  NOT NULL,            -- SHA-256(originallink) 64자 hex — 멱등성 UQ KEY
    published_at  TIMESTAMPTZ  NOT NULL,            -- pubDate RFC 822 파싱 → Instant 변환
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sentiment     VARCHAR(20)                       -- "POSITIVE" | "NEGATIVE" | "NEUTRAL" | NULL (T-AI 흡수 backlog §62 — 1차 NULL 고정)
);

-- 멱등성 UQ — source × stock_code × url_hash 3-tuple (ADR 0075 §2-2)
-- 동일 뉴스가 여러 종목 검색결과에 포함될 수 있으므로 3-tuple 로 멱등성 확보
CREATE UNIQUE INDEX IF NOT EXISTS ux_news_article_source_stock_url_hash
    ON news_article(source, stock_code, url_hash);

-- REST 페이징 최적화 — stock_code 종목별 + 발행 시각 내림차순
CREATE INDEX IF NOT EXISTS ix_news_article_stock_code_published_at
    ON news_article(stock_code, published_at DESC);

-- 전체 기간 필터 최적화 — published_at 내림차순
CREATE INDEX IF NOT EXISTS ix_news_article_published_at
    ON news_article(published_at DESC);
