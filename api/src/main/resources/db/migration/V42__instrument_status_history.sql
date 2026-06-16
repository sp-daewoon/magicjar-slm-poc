-- ADR 0090 Phase 1 — instrument_status_history 테이블 신설
--
-- 책임: 종목 status 시간별 history snapshot — chart-curator §12 4축 active gap detection axis 2·3 정밀도 향상
-- (ADR 0090 §2-1 LOCK 정합)
--
-- 정합 ADR:
--   - ADR 0013 Market Session FSM (axis 1 권위 `business_day_calendar`)
--   - ADR 0017 Instrument Master 1급 시민 (`instrument` 테이블 + KIS instrument master 갱신 path)
--   - ADR 0019 캔들 저장 정책 (캔들 row 시점 정합)
--   - ADR 0028 Watchlist Ingestion Worker (watchlist 종목 status history 적재 path)
--   - ADR 0039 종목 마스터 갱신 Cron (KIS instrument master 2회/일 cron 활용)
--   - ADR 0085 chart-curator 영입 (§12 active gap detection 본인 영역)
--   - ADR 0086 T-CC Phase 2 데이터 햇징 13 룰 (§12 4축 → 본 ADR로 정밀도 향상)
--
-- 운영 모델:
--   - effective_until = NULL → 현재 활성 row (instrument code당 0~1건)
--   - status 변화 detection 시: UPDATE effective_until=NOW() + INSERT 신규 row
--   - row immutability — effective_until 갱신 외 row 수정 금지 (history 보존)
--
-- 정합 검증 (ADR 0090 §2-6):
--   - watchlist 종목 수 = `SELECT COUNT(*) FROM instrument_status_history WHERE effective_until IS NULL`

CREATE TABLE instrument_status_history (
    id                BIGSERIAL PRIMARY KEY,
    instrument_code   VARCHAR(12) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    effective_from    TIMESTAMPTZ NOT NULL,
    effective_until   TIMESTAMPTZ NULL,
    reason            TEXT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_instrument_status_history_status
      CHECK (status IN ('NORMAL', 'TRADING_HALT', 'LIQUIDATION', 'DELISTED'))
);

-- 인덱스 매트릭스 (ADR 0090 §2-1 LOCK)
-- 1. 종목별 시점 lookup (DESC) — passesActiveGapAxes(code, at ts) 권위 path
CREATE INDEX idx_instrument_status_history_code_from
  ON instrument_status_history (instrument_code, effective_from DESC);

-- 2. active row partial index — effective_until IS NULL row만 (대부분의 쿼리는 active row 조회)
CREATE INDEX idx_instrument_status_history_active
  ON instrument_status_history (instrument_code) WHERE effective_until IS NULL;

COMMENT ON TABLE instrument_status_history IS
    'ADR 0090 Phase 1 — 종목 status history snapshot. chart-curator §12 4축 active gap detection axis 2·3 시점 정밀도 향상. KIS instrument master 갱신 cron (ADR 0039) 시점에 status 변화 detection → diff row INSERT.';

COMMENT ON COLUMN instrument_status_history.instrument_code IS 'KIS PDNO (6자리 종목코드, instrument.code FK 비정형)';
COMMENT ON COLUMN instrument_status_history.status IS 'NORMAL | TRADING_HALT | LIQUIDATION | DELISTED (도메인 InstrumentStatus enum 정합)';
COMMENT ON COLUMN instrument_status_history.effective_from IS '본 status가 시작된 시점 (KIS instrument master 갱신 시점)';
COMMENT ON COLUMN instrument_status_history.effective_until IS '본 status가 종료된 시점 (다음 status row 발행 시점에 backfill). NULL = 현재 활성';
COMMENT ON COLUMN instrument_status_history.reason IS 'KIS 응답 또는 운영자 메모 (향후 path)';
COMMENT ON COLUMN instrument_status_history.recorded_at IS 'row 발행 시점 (audit_trail)';
