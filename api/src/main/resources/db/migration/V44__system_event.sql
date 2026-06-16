-- V44__system_event.sql
-- 시스템 운영 이벤트 로그 P1 (spec: _workspace/specs/2026-05-20-system-event-log-design.md)
-- WebSocket·Reconcile·Kafka·KIS·DB·Redis 등 외부 의존 실패 catalog
-- correlation_id 그룹 (WS disconnect→reconnect 묶기) + viewed/resolved UI 마킹

CREATE TABLE IF NOT EXISTS system_event (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(20) NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    actor           VARCHAR(60) NOT NULL,
    action          VARCHAR(80) NOT NULL,
    user_id         VARCHAR(40),
    correlation_id  VARCHAR(64),
    detail_json     JSONB NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMP WITH TIME ZONE,
    viewed_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_system_event_severity CHECK (severity IN ('INFO','WARN','ERROR','CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_system_event_occurred ON system_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_system_event_user_unread ON system_event (user_id, viewed_at) WHERE viewed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_system_event_category ON system_event (category, severity, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_system_event_correlation ON system_event (correlation_id) WHERE correlation_id IS NOT NULL;
