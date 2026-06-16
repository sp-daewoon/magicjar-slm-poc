-- V16: risk_config 테이블 신규 — T-RISK Phase 2 (ADR 0067 §5-1 G-1 H-1)
-- 사용자별 risk 설정 — UQ user_id, 부재 시 H-1 default seed

CREATE TABLE risk_config (
    id                      BIGSERIAL       PRIMARY KEY,
    user_id                 VARCHAR(40)     NOT NULL UNIQUE,
    max_position_abs        NUMERIC(19, 4),
    max_position_pct        NUMERIC(7, 4),
    dll_abs                 NUMERIC(19, 4),
    dll_pct                 NUMERIC(7, 4),
    max_concurrent_positions INT            NOT NULL DEFAULT 5,
    trade_freq_per_min      INT             NOT NULL DEFAULT 3,
    trade_freq_per_day      INT             NOT NULL DEFAULT 50,
    auto_pause_threshold    INT             NOT NULL DEFAULT 10,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE risk_config IS 'T-RISK Phase 2 — 사용자별 risk 설정 (ADR 0067 §5-1 H-1 UQ user_id)';
COMMENT ON COLUMN risk_config.user_id              IS '사용자 ID (UQ — 1 row per user)';
COMMENT ON COLUMN risk_config.max_position_abs     IS '단일 포지션 최대 금액 절대값 (원) — A-1 절대+% min()';
COMMENT ON COLUMN risk_config.max_position_pct     IS '단일 포지션 최대 비율 (0.01~100.00) — A-1';
COMMENT ON COLUMN risk_config.dll_abs              IS '일일 손실 한도 절대값 (원) — B-1 xor dll_pct';
COMMENT ON COLUMN risk_config.dll_pct              IS '일일 손실 한도 비율 (0.01~100.00) — B-1 xor dll_abs';
COMMENT ON COLUMN risk_config.max_concurrent_positions IS '동시 보유 포지션 최대 수 — C-1 default 5';
COMMENT ON COLUMN risk_config.trade_freq_per_min   IS 'strategy당 분당 최대 거래 수 — D-1 default 3';
COMMENT ON COLUMN risk_config.trade_freq_per_day   IS 'strategy당 일별 최대 거래 수 — D-1 default 50';
COMMENT ON COLUMN risk_config.auto_pause_threshold IS '연속 BLOCKED N회 시 strategy AUTO_PAUSE 임계 — F-1 default 10';
