-- V27__notification_outbox.sql
-- T-NOTIFY Phase 1 — notification_outbox 풀세트 영속 (ADR 0044·0070 §3-3 + spec §2-4)
--
-- Hotfix 2026-05-14: V1 baseline 의 placeholder `notification_outbox`(event_topic·payload_json) 는
-- 사용되지 않은 빈 정의였음. T-NOTIFY 풀세트(event_id UUID + channel + priority + payload JSONB +
-- retry_count + last_error) 구조와 호환 안 됨 → CREATE TABLE IF NOT EXISTS skip 후 INDEX 실패
-- (column "event_id" does not exist).
--
-- 정정 패턴: DROP TABLE IF EXISTS + CREATE 풀세트.
-- V1 baseline 테이블에 row 0 건이라 데이터 손실 없음(부팅 시점에 첫 활성화).

DROP TABLE IF EXISTS notification_outbox;

CREATE TABLE notification_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    channel         VARCHAR(16)  NOT NULL,           -- SLACK · (FUTURE: EMAIL · SMS · PUSH · DISCORD)
    priority        VARCHAR(4)   NOT NULL,            -- P0 · P1 · P2
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL,            -- PENDING · SENT · FAILED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE UNIQUE INDEX uq_notification_outbox_event_channel
    ON notification_outbox (event_id, channel);

CREATE INDEX ix_notification_outbox_status_created
    ON notification_outbox (status, created_at);

-- system_config 컬럼 ALTER (ADR 0046 AES-256-GCM 정합 — BYTEA 단일 row)
ALTER TABLE system_config ADD COLUMN IF NOT EXISTS notification_slack_webhook BYTEA;
