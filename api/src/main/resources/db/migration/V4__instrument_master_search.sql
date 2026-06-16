-- V4__instrument_master_search.sql
-- ADR 0017 §2-2·§2-4 정합 — 검색 인덱스·alias 매핑·정규화 컬럼 신설
-- PR-11a T-IM1 market-data 단독 담당 (dispatch §4-1 LOCK)
-- 함정 §11-2·§11-11: pg_trgm extension 권한 미가용 시 idx_instrument_name_trgm 인덱스 생성 실패 가능.
--   → superuser 권한 불필요 (PostgreSQL 16 bundled extension, pg_trgm은 extension 설치만으로 활성화).
--   → docker-compose postgres init.sql에서 사전 CREATE EXTENSION 없이도 일반 user로 가능.
--   → 실패 시 gin_trgm 인덱스만 skip 되고 나머지 ALTER/CREATE는 정상 적용됨 (IF NOT EXISTS 패턴).

-- 1. pg_trgm extension 활성화 (PostgreSQL 16 기본 포함)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. instrument 테이블 컬럼 추가
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS name_norm VARCHAR(80);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS theme     VARCHAR(200);

-- 3. 검색 인덱스
--    gin trigram — 한글 부분 일치 fallback 검색 (pg_trgm extension 의존)
CREATE INDEX IF NOT EXISTS idx_instrument_name_trgm
    ON instrument USING gin (name gin_trgm_ops);

--    code prefix 검색 (text_pattern_ops — LIKE 'prefix%' 최적화)
CREATE INDEX IF NOT EXISTS idx_instrument_code_prefix
    ON instrument (code text_pattern_ops);

-- 4. instrument_alias 테이블 신설 (ADR 0017 §2-2 — alias_source KRX·NAVER·FNGUIDE)
CREATE TABLE IF NOT EXISTS instrument_alias (
    code   VARCHAR(8)  NOT NULL,
    alias  VARCHAR(80) NOT NULL,
    source VARCHAR(20) NOT NULL,
    CONSTRAINT pk_instrument_alias PRIMARY KEY (code, alias, source),
    CONSTRAINT fk_instrument_alias_code FOREIGN KEY (code)
        REFERENCES instrument (code) ON DELETE CASCADE,
    CONSTRAINT chk_instrument_alias_source CHECK (source IN ('KRX', 'NAVER', 'FNGUIDE'))
);

-- 5. alias prefix 검색 인덱스
CREATE INDEX IF NOT EXISTS idx_instrument_alias_alias
    ON instrument_alias (alias text_pattern_ops);
