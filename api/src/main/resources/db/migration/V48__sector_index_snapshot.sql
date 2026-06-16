-- ============================================================================
-- V48 — sector_index_snapshot (ADR 0100 §2-7 LOCK · 2026-05-24 R0 amend V41 → V48 shift)
-- ============================================================================
-- T-MARKET Phase 1 — KRX 21 섹터 1분 snapshot 영속.
-- publisher: batch MarketIndexSyncJob (1분 cron · FHPUP02140000 결과 분해 후 21건 일괄 insert)
-- consumer : R1 backend-core SectorRankingComposer (todayTop / longTop 합산 input)
--
-- 코드 매트릭스 (KIS FID_INPUT_ISCD 카탈로그 — IndexCode.KOSPI_SECTORS 정합):
--   0002=음식료 · 0003=섬유의복 · 0004=종이목재 · 0005=화학 · 0006=의약품
--   0007=비금속광물 · 0008=철강금속 · 0009=기계 · 0010=전기전자 · 0011=의료정밀
--   0012=운수장비 · 0013=유통업 · 0014=전기가스업 · 0015=건설업 · 0016=운수창고
--   0017=통신업 · 0018=금융업 · 0019=은행 · 0020=증권 · 0021=보험 · 0022=서비스업
-- ============================================================================
CREATE TABLE sector_index_snapshot (
    id           BIGSERIAL    PRIMARY KEY,
    sector_code  VARCHAR(16)  NOT NULL,           -- KIS 업종 코드 (예: 0018=금융업)
    sector_name  VARCHAR(64)  NOT NULL,           -- 사용자 노출 한국어명 (IndexCode 카탈로그 정합)
    ts           TIMESTAMPTZ  NOT NULL,
    price        NUMERIC(15,4) NOT NULL,
    change_rate  NUMERIC(8,4)  NOT NULL,          -- 전일 대비 %
    trade_value  NUMERIC(20,2) NULL,              -- 거래대금 (원) — KIS acml_tr_pbmn 등. nullable
    raw_payload  JSONB         NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sector_index_snapshot_code_ts UNIQUE (sector_code, ts)
);

CREATE INDEX idx_sector_index_snapshot_code_ts ON sector_index_snapshot (sector_code, ts DESC);
CREATE INDEX idx_sector_index_snapshot_ts       ON sector_index_snapshot (ts DESC);

COMMENT ON TABLE  sector_index_snapshot              IS 'T-MARKET KRX 21 섹터 1분 snapshot (ADR 0100 §2-7 LOCK)';
COMMENT ON COLUMN sector_index_snapshot.sector_code  IS 'KIS 업종 코드 raw (0002~0022 KOSPI 표준 분류)';
COMMENT ON COLUMN sector_index_snapshot.sector_name  IS '사용자 노출 한국어명 (IndexCode.KOSPI_SECTORS 카탈로그 정합)';
COMMENT ON COLUMN sector_index_snapshot.change_rate  IS 'KIS BSTP_NMIX_PRDY_CTRT · 전일 대비 %';
COMMENT ON COLUMN sector_index_snapshot.trade_value  IS 'KIS acml_tr_pbmn 등 거래대금 (원). nullable';
