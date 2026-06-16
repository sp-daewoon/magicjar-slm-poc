-- V29__trading_journal_and_note.sql
-- T-J 영속 (ADR 0072 §3-1)
-- order·signal·risk·reconcile·position 5 Kafka 이벤트 → trading_journal INSERT
-- 사용자 메모 journal_note 1:N
-- IF NOT EXISTS 강제 LOCK (헌법 §6 + ADR 0068·0070·0071 패턴)

CREATE TABLE IF NOT EXISTS trading_journal (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    event_type   VARCHAR(32)   NOT NULL,         -- ORDER_FILLED · ORDER_REJECTED · ORDER_CANCELLED · SIGNAL_GENERATED · RISK_VIOLATION
    instrument_code VARCHAR(16),                 -- nullable (RISK_VIOLATION 등 portfolio-wide 이벤트)
    instrument_name VARCHAR(64),
    occurred_at  TIMESTAMPTZ   NOT NULL,
    summary      VARCHAR(255)  NOT NULL,          -- "체결: 005930 매수 100주 @ 75,000원"
    payload      JSONB         NOT NULL,          -- 원본 이벤트 페이로드 (audit + W4 AI context)
    source_table VARCHAR(32)   NOT NULL,          -- 원본 영속 테이블명 (order_execution · signal · risk_event · reconcile_run · position)
    source_id    BIGINT        NOT NULL,           -- 원본 PK
    realized_pnl NUMERIC(18, 2),                  -- 체결 이벤트 한정, 그 외 NULL
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_trading_journal_user_occurred
    ON trading_journal (user_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS ix_trading_journal_user_event
    ON trading_journal (user_id, event_type, occurred_at DESC);

CREATE INDEX IF NOT EXISTS ix_trading_journal_user_code
    ON trading_journal (user_id, instrument_code, occurred_at DESC);

-- 멱등성 UQ (Kafka at-least-once 재처리 회피 — ADR 0072 §3-1)
CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_journal_source
    ON trading_journal (source_table, source_id, event_type);

CREATE TABLE IF NOT EXISTS journal_note (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    journal_entry_id BIGINT        NOT NULL REFERENCES trading_journal(id) ON DELETE CASCADE,
    note_text        TEXT          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_journal_note_entry
    ON journal_note (journal_entry_id);

CREATE INDEX IF NOT EXISTS ix_journal_note_user_created
    ON journal_note (user_id, created_at DESC);
