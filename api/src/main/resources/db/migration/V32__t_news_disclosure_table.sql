-- V32__t_news_disclosure_table.sql
-- T-NEWS Phase 1 — DART 공시 row 영속 테이블 (ADR 0075 §2-1 Phase 1 LOCK)
--
-- Phase 1 disclosure 테이블 (DART 공시 — 정정공시 분류 우선).
-- Phase 2 news_article 테이블은 V33 (backend-core Phase 2 직렬 — ADR 0075 §8-3).
--
-- 멱등성: ux_disclosure_rcept_no (rcept_no UQ) — ON CONFLICT DO NOTHING (DART rcept_no unique 보장).
-- 인덱스: stock_code × ingested_at DESC (REST 페이징 최적화) + rcept_dt DESC (날짜 필터).

CREATE TABLE IF NOT EXISTS disclosure (
    id            BIGSERIAL    PRIMARY KEY,
    corp_code     VARCHAR(8)   NOT NULL,            -- DART corp_code 8자리 (KRX 종목코드 != DART corp_code)
    stock_code    VARCHAR(20),                      -- KRX 종목코드 6자리 (NULL 허용 — 비상장 공시 대응)
    rcept_no      VARCHAR(20)  NOT NULL,            -- DART 접수번호 — 멱등성 KEY
    rcept_dt      VARCHAR(8)   NOT NULL,            -- 접수일자 YYYYMMDD (DART raw 매핑 — 변환 없이 그대로)
    corp_name     VARCHAR(200) NOT NULL,            -- 회사명
    report_nm     VARCHAR(500) NOT NULL,            -- 보고서명 ("[기재정정]" prefix → is_correction=true)
    flr_nm        VARCHAR(100),                     -- 공시제출인명 (nullable)
    rm            VARCHAR(50),                      -- 비고 (정정·연결·첨부 등 — nullable)
    url           VARCHAR(500) NOT NULL,            -- DART 공시 본문 URL (rcept_no 기반 합성)
    is_correction BOOLEAN      NOT NULL DEFAULT FALSE, -- report_nm "[기재정정]" or rm contains "정"
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 멱등성 UQ — DART rcept_no는 공식 unique 보장 (ADR 0075 §2-2)
CREATE UNIQUE INDEX IF NOT EXISTS ux_disclosure_rcept_no
    ON disclosure(rcept_no);

-- REST 페이징 최적화 — stock_code 종목별 + 수집 시각 내림차순
CREATE INDEX IF NOT EXISTS ix_disclosure_stock_code_ingested_at
    ON disclosure(stock_code, ingested_at DESC);

-- 날짜 범위 필터 최적화 — rcept_dt YYYYMMDD 내림차순
CREATE INDEX IF NOT EXISTS ix_disclosure_rcept_dt
    ON disclosure(rcept_dt DESC);
