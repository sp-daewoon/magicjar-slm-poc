-- =============================================================================
-- magicJar V3 — Watchlist + Position refinements (PR-7 T-W1)
-- ADR 0042·0028·0040 정합 — instrument CHECK 완화(stub) + 인덱스 2건
-- =============================================================================

-- 1. instrument.market·type CHECK 완화 — Watchlist stub row 허용 (ADR 0028·0040)
--    T-IM1(Instrument Master) 진입 시 KIS 마스터 동기화로 정식 데이터 갱신
ALTER TABLE instrument DROP CONSTRAINT chk_instrument_market;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_market
    CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX', 'UNKNOWN'));

ALTER TABLE instrument DROP CONSTRAINT chk_instrument_type;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_type
    CHECK (type IN ('STOCK', 'ETF', 'ETN', 'PREFERRED', 'WARRANT', 'REIT', 'UNKNOWN'));

-- 2. watchlist 정렬 조회 인덱스 (ADR 0040 §2-2 list 진입 화면)
CREATE INDEX IF NOT EXISTS idx_watchlist_user_priority
    ON watchlist (user_id, priority, created_at);

-- 3. position user_id 조회 인덱스 (ADR 0042 Bootstrap STAGE 0-2 upsertAll 후 조회)
CREATE INDEX IF NOT EXISTS idx_position_user
    ON position (user_id);
