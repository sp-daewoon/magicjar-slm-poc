-- =====================================================================
-- V52 — mcp_audit (ADR 0027 §2-5 amend · 2026-05-24 R0 amend V45 → V52 shift)
--
-- MCP tool 호출 로그·비용·드리프트 추적 단일 진입점.
-- 모든 tool execute 호출 시 1 row INSERT (success · error 양쪽 — error_message 필드로 구분).
--
-- ## 영역 LOCK
-- - V52 next-available (main HEAD V51 점유 audit 결과 · ADR 0102 정합)
-- - V40~V51 절대 touch 0
--
-- ## index 전략
-- - (user_id, called_at DESC) — 사용자별 호출 시계열 query 핵심 path
-- - (tool, called_at DESC) — tool별 호출 빈도 dashboard (Grafana — ADR 0095 amend S-MCP-1 alert routing)
-- =====================================================================

CREATE TABLE mcp_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(64),
    tool VARCHAR(64) NOT NULL,
    params JSONB,
    response_size_bytes INT,
    latency_ms INT,
    error_message TEXT,
    called_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mcp_audit_latency_nonneg CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT mcp_audit_response_size_nonneg CHECK (response_size_bytes IS NULL OR response_size_bytes >= 0)
);

CREATE INDEX idx_mcp_user_time ON mcp_audit(user_id, called_at DESC);
CREATE INDEX idx_mcp_tool_time ON mcp_audit(tool, called_at DESC);

COMMENT ON TABLE mcp_audit IS 'MCP tool 호출 audit log — ADR 0027 §2-5 (2026-05-24 R0 amend V45→V52 shift · ADR 0102)';
COMMENT ON COLUMN mcp_audit.user_id IS '사용자 id (multi-tenant 격리)';
COMMENT ON COLUMN mcp_audit.client_id IS 'API key client_id 매핑 (1차 nullable · post-MVP per-client 권한)';
COMMENT ON COLUMN mcp_audit.tool IS 'tool 이름 (예: get_candles · get_market_trend)';
COMMENT ON COLUMN mcp_audit.params IS 'tool 호출 input JSON (null safe · secret 평문 금지 — feedback_secret_handling 정합)';
COMMENT ON COLUMN mcp_audit.response_size_bytes IS 'tool 응답 직렬화 후 byte 수 (성능 추적)';
COMMENT ON COLUMN mcp_audit.latency_ms IS 'tool execute() 경과 시간';
COMMENT ON COLUMN mcp_audit.error_message IS 'tool 실패 시 한국어 메시지 (stacktrace 0 — 외부 client 보안)';
COMMENT ON COLUMN mcp_audit.called_at IS 'tool 호출 시점 (이력 query 기준)';
