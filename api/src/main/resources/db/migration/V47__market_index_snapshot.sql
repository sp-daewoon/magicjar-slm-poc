-- ============================================================================
-- V47 — market_index_snapshot (ADR 0100 §2-7 LOCK · 2026-05-24 R0 amend V40 → V47 shift)
-- ============================================================================
-- T-MARKET Phase 1 — 지수·섹터 1분 snapshot 영속.
-- publisher: batch MarketIndexSyncJob (1분 cron · 26 req/min · INGESTION_LOW priority)
-- consumer : api MarketIndexController + STOMP fanout (R2 backend-core 후속)
--
-- 코드 매트릭스 (KIS-first raw 정합 — IndexCode.kt 카탈로그):
--   국내 지수      : 0001=KOSPI · 1001=KOSDAQ · 2001=KOSPI200
--   국내 섹터(21건): 0002~0022 (KOSPI 표준 분류 FID_MRKT_CLS_CODE=K · FID_BLNG_CLS_CODE=0)
--   해외 지수      : .IXIC=NASDAQ · .SPX=S&P500 (EXCD=NAS)
--
-- 부하 예측 (ADR 0100 §5-2 — DB 부담 mitigation):
--   1분 cron 26 row/min × 60min × 6h(장중) = ~9360 row/day. 90일 누적 ~840K row.
--   id BIGSERIAL + UQ index 충분 (read-only 본질 + 90일 retention 후속 검토).
-- ============================================================================
CREATE TABLE market_index_snapshot (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(16)  NOT NULL,           -- KIS index code (예: 0001 · .IXIC)
    market      VARCHAR(16)  NOT NULL,           -- DOMESTIC_INDEX · DOMESTIC_SECTOR · OVERSEAS_INDEX
    ts          TIMESTAMPTZ  NOT NULL,           -- 1분 단위 snapshot 시각 (KST)
    price       NUMERIC(15,4) NOT NULL,          -- BSTP_NMIX_PRPR · 해외 last
    open        NUMERIC(15,4) NULL,              -- 일봉 source만 채움 (1분 cron은 null)
    high        NUMERIC(15,4) NULL,
    low         NUMERIC(15,4) NULL,
    close       NUMERIC(15,4) NULL,
    change_rate NUMERIC(8,4)  NULL,              -- BSTP_NMIX_PRDY_CTRT · 전일 대비 %
    volume      BIGINT        NULL,              -- ACML_VOL (옵션, 일부 지수만 채움)
    raw_payload JSONB         NULL,              -- KIS-first 정합 — raw response 보존
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_index_snapshot_code_ts UNIQUE (code, ts)
);

CREATE INDEX idx_market_index_snapshot_code_ts   ON market_index_snapshot (code, ts DESC);
CREATE INDEX idx_market_index_snapshot_market_ts ON market_index_snapshot (market, ts DESC);

COMMENT ON TABLE  market_index_snapshot                  IS 'T-MARKET 지수·섹터 1분 snapshot (ADR 0100 §2-7 LOCK)';
COMMENT ON COLUMN market_index_snapshot.code             IS 'KIS index code (예: 0001=KOSPI · .IXIC=NASDAQ)';
COMMENT ON COLUMN market_index_snapshot.market           IS 'IndexCategory enum 직접 매핑 (DOMESTIC_INDEX·DOMESTIC_SECTOR·OVERSEAS_INDEX)';
COMMENT ON COLUMN market_index_snapshot.ts               IS '1분 cron snapshot 시각 (KST · UQ 멱등 키 일부)';
COMMENT ON COLUMN market_index_snapshot.price            IS 'KIS BSTP_NMIX_PRPR (국내) · last (해외)';
COMMENT ON COLUMN market_index_snapshot.change_rate      IS 'KIS BSTP_NMIX_PRDY_CTRT · 전일 대비 %';
COMMENT ON COLUMN market_index_snapshot.raw_payload      IS 'KIS raw response JSONB (KIS-first 정합 — 필드 변경 시 add-only 흡수 가능)';
