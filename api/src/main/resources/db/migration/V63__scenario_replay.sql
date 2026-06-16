-- V63: Scenario Replay Sandbox persistence (ADR 0107, T-SIM Phase 2)
--
-- 실주문/모의주문 runtime과 분리된 QA replay namespace.
-- live 주문·리스크 테이블에는 FK를 걸지 않고 scenario_* 안에서만 CASCADE 된다.

CREATE TABLE IF NOT EXISTS scenario_run (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40) NOT NULL,
    strategy_id     BIGINT NOT NULL,
    strategy_name   VARCHAR(200),
    code            VARCHAR(8) NOT NULL,
    interval        VARCHAR(8) NOT NULL,
    range_from      TIMESTAMPTZ NOT NULL,
    range_to        TIMESTAMPTZ NOT NULL,
    initial_cash    NUMERIC(19,4) NOT NULL CHECK (initial_cash >= 0),
    fill_model      VARCHAR(32) NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS','ABORTED','FAILED')),
    event_count     INTEGER NOT NULL DEFAULT 0 CHECK (event_count >= 0),
    final_cash      NUMERIC(19,4),
    abort_reason    VARCHAR(80),
    detail_json     JSONB,
    started_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scenario_event (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES scenario_run(id) ON DELETE CASCADE,
    sequence_no     INTEGER NOT NULL CHECK (sequence_no >= 0),
    virtual_time    TIMESTAMPTZ NOT NULL,
    type            VARCHAR(40) NOT NULL,
    severity        VARCHAR(10) NOT NULL CHECK (severity IN ('INFO','WARN','ERROR')),
    title           VARCHAR(160) NOT NULL,
    message         TEXT NOT NULL,
    strategy_id     BIGINT,
    strategy_name   VARCHAR(200),
    code            VARCHAR(8),
    payload_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scenario_event_run_seq UNIQUE (run_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS scenario_order (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES scenario_run(id) ON DELETE CASCADE,
    virtual_time    TIMESTAMPTZ NOT NULL,
    side            VARCHAR(4) CHECK (side IN ('BUY','SELL')),
    quantity        BIGINT,
    price_type      VARCHAR(10),
    limit_price     NUMERIC(19,4),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','HELD','REJECTED','FILLED')),
    reason          TEXT,
    payload_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scenario_execution (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES scenario_run(id) ON DELETE CASCADE,
    virtual_time    TIMESTAMPTZ NOT NULL,
    side            VARCHAR(4) CHECK (side IN ('BUY','SELL')),
    quantity        BIGINT NOT NULL CHECK (quantity > 0),
    fill_price      NUMERIC(19,4) NOT NULL CHECK (fill_price > 0),
    payload_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scenario_position (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES scenario_run(id) ON DELETE CASCADE,
    code            VARCHAR(8) NOT NULL,
    quantity        BIGINT NOT NULL CHECK (quantity >= 0),
    average_price   NUMERIC(19,4) NOT NULL CHECK (average_price >= 0),
    virtual_time    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scenario_run_user_created
    ON scenario_run (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_scenario_run_strategy_created
    ON scenario_run (strategy_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_scenario_event_run_time
    ON scenario_event (run_id, virtual_time ASC, sequence_no ASC);

CREATE INDEX IF NOT EXISTS idx_scenario_order_run_time
    ON scenario_order (run_id, virtual_time ASC);

CREATE INDEX IF NOT EXISTS idx_scenario_execution_run_time
    ON scenario_execution (run_id, virtual_time ASC);

CREATE INDEX IF NOT EXISTS idx_scenario_position_run_code
    ON scenario_position (run_id, code);

COMMENT ON TABLE scenario_run IS 'T-SIM Scenario Replay 실행 메타. live 주문·리스크 테이블과 분리된 QA namespace.';
COMMENT ON TABLE scenario_event IS 'T-SIM virtual time event timeline. signal/risk/virtual order/fault를 한 run 안에 기록.';
COMMENT ON TABLE scenario_order IS 'T-SIM virtual order 상태. 실제 주문 라우터와 연결하지 않는다.';
COMMENT ON TABLE scenario_execution IS 'T-SIM virtual fill 결과. 실제 체결 통보와 연결하지 않는다.';
COMMENT ON TABLE scenario_position IS 'T-SIM run 내부 virtual position snapshot.';
