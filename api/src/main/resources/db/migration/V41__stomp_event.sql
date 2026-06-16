-- V41__stomp_event.sql
-- ADR 0084 P1-2 — STOMP 이벤트 영속 + backfill REST endpoint (spec 2026-05-18 order-path-stability §2-6)
--
-- 목적: frontend STOMP 클라이언트 disconnect 동안 publish된 메시지 손실 risk 회피.
-- publish 직전 INSERT → 재접속 시 GET /api/stomp/events?since= backfill 호출.
--
-- 보존 기간: 7일 (retention cron 별도 사이클).

CREATE TABLE stomp_event (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    channel      VARCHAR(64)  NOT NULL,    -- order · signal · risk · recon · notification 등
    event_type   VARCHAR(64)  NOT NULL,    -- REQUESTED · FILLED · reconcile.drift.detected 등
    payload_json JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- resync 쿼리 최적화: WHERE user_id = ? AND created_at >= ? ORDER BY created_at DESC LIMIT ?
CREATE INDEX idx_stomp_event_user_created
    ON stomp_event (user_id, created_at DESC);

-- channel filter 보조 인덱스 (선택적 WHERE channel = ? 시)
CREATE INDEX idx_stomp_event_user_channel_created
    ON stomp_event (user_id, channel, created_at DESC);
