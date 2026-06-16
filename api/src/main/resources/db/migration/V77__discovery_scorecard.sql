-- V77: Discovery Scorecard — 매일 장전 발굴 TOP N 기록 + 다음 거래일 실제 수익률 자동 채점
--
-- 백테스트(과거)와 달리 forward-test 증거가 매일 공짜로 누적되는 살아있는 hit-rate 레이어.
--   1. 장전 snapshot 잡 (08:30 KST · 영업일): 활성 빌트인 DAYTRADE_* 프리셋으로 발굴 → TOP N INSERT
--      (전일 일봉 기준 발굴이므로 장전 시점이 정합 — 당일 일봉은 아직 없음).
--   2. 야간 채점 잡 (23:30 KST · R2 23:00 야간 일봉 snapshot 후): 미채점 row를 다음 거래일 일봉으로 채점.
--
-- 자본 영향 0 — 기록·관찰만 (주문/watchlist write 0). KIS 호출 0 (DB candle만). mock 0 (일봉 없으면 미채점 유지).
--
-- next_return_pct = (다음 거래일 종가 / snapshot 당일 종가 - 1) * 100  ← "발굴이 좋은 종목을 뽑는가"의 핵심 지표.
-- snapshot 당일 종가 = snapshot 시점 기준 전일 일봉(발굴 입력)의 마지막 close. 채점 시 함께 기록한다.
--
-- ON CONFLICT DO NOTHING (멱등) — 같은 거래일 재발사해도 중복 0. V70/V74 preset 시드 선례 정합.

CREATE TABLE IF NOT EXISTS discovery_scorecard (
    id                    BIGSERIAL    PRIMARY KEY,
    -- 발굴 snapshot 식별 (장전 발사일 = 발굴 입력 일봉 기준일의 다음 영업일)
    snapshot_date         DATE         NOT NULL,
    preset_key            VARCHAR(64)  NOT NULL,
    rank                  INTEGER      NOT NULL,
    instrument_code       VARCHAR(32)  NOT NULL,
    instrument_name       VARCHAR(120) NOT NULL,
    score                 NUMERIC(12,6) NOT NULL DEFAULT 0,
    -- 발굴 입력(전일) 일봉의 종가 — 다음날 수익률 분모. 채점 시 함께 기록(snapshot 시점엔 null 가능).
    base_close            NUMERIC(20,4),
    -- ── 채점 컬럼 (scored_at IS NULL = 미채점) ──────────────────────────────────────────────
    next_open             NUMERIC(20,4),
    next_high             NUMERIC(20,4),
    next_close            NUMERIC(20,4),
    -- (다음 거래일 종가 / base_close - 1) * 100
    next_return_pct       NUMERIC(12,4),
    -- (다음 거래일 시가 / base_close - 1) * 100  ← 장전 진입 시 시초가 수익률
    next_open_return_pct  NUMERIC(12,4),
    scored_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_discovery_scorecard UNIQUE (snapshot_date, preset_key, instrument_code)
);

-- 채점 잡: scored_at IS NULL + snapshot_date < 당일 row 스캔
CREATE INDEX IF NOT EXISTS idx_discovery_scorecard_unscored
    ON discovery_scorecard (scored_at, snapshot_date)
    WHERE scored_at IS NULL;

-- 조회 REST: 기간·프리셋별 요약 + 상세
CREATE INDEX IF NOT EXISTS idx_discovery_scorecard_preset_date
    ON discovery_scorecard (preset_key, snapshot_date DESC);

COMMENT ON TABLE discovery_scorecard IS
    '발굴 스코어카드. 매일 장전 발굴 TOP N을 기록하고 다음 거래일 실제 일봉으로 자동 채점하는 forward-test hit-rate 레이어. 자본/주문 영향 0 — 기록·관찰만.';
COMMENT ON COLUMN discovery_scorecard.snapshot_date IS
    '발굴 발사일(KST 영업일). 발굴 입력은 전일 일봉이며 채점 대상은 이 날짜의 일봉(다음 거래일).';
COMMENT ON COLUMN discovery_scorecard.base_close IS
    '발굴 입력(전일) 일봉 종가 = next_return_pct 분모. snapshot 시 미상이면 채점 시 보강.';
COMMENT ON COLUMN discovery_scorecard.next_return_pct IS
    '(다음 거래일 종가 / base_close - 1) * 100. 발굴 품질의 핵심 채점 지표. scored_at NULL이면 미채점.';
COMMENT ON COLUMN discovery_scorecard.scored_at IS
    'NULL=미채점(일봉 미수집 등). 다음 밤 채점 잡 재시도. 정직성 — mock 0.';
