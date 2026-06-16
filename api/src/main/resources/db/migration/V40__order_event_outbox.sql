-- V40__order_event_outbox.sql
-- ADR 0084 P1-1 — order_event_outbox + Transactional Outbox 패턴 (spec 2026-05-18 §2-5).
--
-- 자본 path 4 producer (order.intent.v1 / order.requested.v1 / order.rejected.v1 / order.filled.v1)
-- Kafka publish 실패 시 row INSERT + 비동기 retry. NotificationOutbox(V27) 패턴 superset.
--
-- 컬럼 매트릭스:
--   id            BIGSERIAL    PK
--   event_id      UUID         UNIQUE (멱등 키 — 중복 publish 차단)
--   event_type    VARCHAR(64)  ORDER_INTENT · ORDER_REQUESTED · ORDER_REJECTED · ORDER_FILLED 등
--   topic         VARCHAR(96)  Kafka 토픽명 (order.intent.v1·order.requested.v1·order.rejected.v1·order.filled.v1)
--   partition_key VARCHAR(64)  partition key (userId)
--   payload_json  JSONB        producer가 직렬화한 payload 본문 (status·attempt 무관 immutable)
--   status        VARCHAR(16)  PENDING · PUBLISHED · FAILED
--   created_at    TIMESTAMPTZ  생성 시각
--   published_at  TIMESTAMPTZ  publish 성공 시각 (PUBLISHED 전이 시 set)
--   attempt_count INT          publish 시도 횟수
--   last_error    TEXT         실패 사유 (FAILED 시 set, 500 chars truncate 권장)
--
-- 인덱스:
--   uq_order_event_outbox_event_id — 멱등 키
--   ix_order_event_outbox_pending  — published_at IS NULL partial (worker poll 가속)
--   ix_order_event_outbox_status_created — status별 운영 조회 가속

CREATE TABLE order_event_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(96)  NOT NULL,
    partition_key   VARCHAR(64),
    payload_json    JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempt_count   INT          NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE UNIQUE INDEX uq_order_event_outbox_event_id
    ON order_event_outbox (event_id);

CREATE INDEX ix_order_event_outbox_pending
    ON order_event_outbox (created_at)
    WHERE published_at IS NULL;

CREATE INDEX ix_order_event_outbox_status_created
    ON order_event_outbox (status, created_at);
