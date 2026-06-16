-- =====================================================================
-- V53 — trendline
--
-- Symbol chart 위에 남는 수동/자동 추세선 저장소.
-- 실제 자동매매 진입 조건 연결은 strategy DSL 후속 작업에서 처리한다.
-- =====================================================================

CREATE TABLE trendline (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    code VARCHAR(8) NOT NULL,
    source_interval VARCHAR(8) NOT NULL,
    line_type VARCHAR(16) NOT NULL,
    source VARCHAR(16) NOT NULL,
    anchor1_ts TIMESTAMPTZ NOT NULL,
    anchor1_price NUMERIC(19, 4) NOT NULL,
    anchor2_ts TIMESTAMPTZ NOT NULL,
    anchor2_price NUMERIC(19, 4) NOT NULL,
    extend_right BOOLEAN NOT NULL DEFAULT true,
    label VARCHAR(80),
    color VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_trendline_type CHECK (line_type IN ('SUPPORT', 'RESISTANCE')),
    CONSTRAINT chk_trendline_source CHECK (source IN ('MANUAL', 'AUTO')),
    CONSTRAINT chk_trendline_anchor_order CHECK (anchor1_ts <> anchor2_ts),
    CONSTRAINT fk_trendline_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_trendline_instrument FOREIGN KEY (code)
        REFERENCES instrument (code)
);

CREATE INDEX idx_trendline_user_code ON trendline(user_id, code, created_at DESC);
CREATE INDEX idx_trendline_code_interval ON trendline(code, source_interval, source);

COMMENT ON TABLE trendline IS '차트 추세선 저장소 — 수동/자동 생성, strategy DSL 연결 전 단계';
COMMENT ON COLUMN trendline.source_interval IS '추세선을 그린 기준 봉 interval';
COMMENT ON COLUMN trendline.line_type IS 'SUPPORT 또는 RESISTANCE';
COMMENT ON COLUMN trendline.source IS 'MANUAL 또는 AUTO';
