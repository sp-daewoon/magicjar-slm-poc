-- =============================================================================
-- magicJar V1 baseline — 11 테이블
-- ADR 0046·0042·0041·0043·0024·0011·0019·0017·0028·0040·0044·0015 정합
-- 작성: backend-core (PR-5a)
-- =============================================================================
-- 규칙:
--   - ENUM 사용 금지 → VARCHAR + CHECK (Flyway 변경 비용 최소화)
--   - TIMESTAMPTZ NOT NULL DEFAULT now() (UTC 영속, 표현은 KST)
--   - FK ON DELETE CASCADE는 user_id 계열만 (multi-tenant 정합)
--   - 스키마 분리(trading/ops)는 PR-7+ V2로 미룸 (V1 단순화 — architect 결정)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. system_config (ADR 0046 §2-3) — singleton row (id=1)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS system_config (
    id                         SMALLINT     NOT NULL DEFAULT 1,
    dart_api_key               BYTEA,
    notification_slack_webhook BYTEA,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_system_config PRIMARY KEY (id),
    CONSTRAINT chk_system_config_singleton CHECK (id = 1)
);

-- ---------------------------------------------------------------------------
-- 2. user_account (ADR 0046 §2-1)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_account (
    user_id       VARCHAR(20)   NOT NULL,
    display_name  VARCHAR(100),
    masked_account VARCHAR(20)  NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT pk_user_account PRIMARY KEY (user_id)
);

-- ---------------------------------------------------------------------------
-- 3. user_kis_credential (ADR 0046 §2-2)
--    복합 PK (user_id, account_type). AES 암호화 컬럼은 BYTEA.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_kis_credential (
    user_id           VARCHAR(20)  NOT NULL,
    account_type      VARCHAR(8)   NOT NULL,
    account_number    VARCHAR(20)  NOT NULL,
    account_prdt_cd   VARCHAR(4)   NOT NULL DEFAULT '01',
    app_key_enc       BYTEA        NOT NULL,
    secret_key_enc    BYTEA        NOT NULL,
    last_token_iss    TIMESTAMPTZ,
    last_validated_at TIMESTAMPTZ,
    CONSTRAINT pk_user_kis_credential PRIMARY KEY (user_id, account_type),
    CONSTRAINT chk_user_kis_credential_type CHECK (account_type IN ('REAL', 'MOCK')),
    CONSTRAINT fk_user_kis_credential_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 4. instrument (ADR 0017)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS instrument (
    code        VARCHAR(8)    NOT NULL,
    isin        VARCHAR(12),
    name        VARCHAR(80)   NOT NULL,
    name_en     VARCHAR(120),
    market      VARCHAR(10)   NOT NULL,
    type        VARCHAR(20)   NOT NULL,
    sector      VARCHAR(40),
    listed_at   DATE,
    delisted_at DATE,
    active      BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_instrument PRIMARY KEY (code),
    CONSTRAINT chk_instrument_market CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX')),
    CONSTRAINT chk_instrument_type   CHECK (type IN ('STOCK', 'ETF', 'ETN', 'PREFERRED', 'WARRANT', 'REIT'))
);

CREATE INDEX IF NOT EXISTS idx_instrument_market_active ON instrument (market, active);

-- ---------------------------------------------------------------------------
-- 5. watchlist (ADR 0028·0040)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS watchlist (
    id         BIGSERIAL     NOT NULL,
    user_id    VARCHAR(20)   NOT NULL,
    code       VARCHAR(8)    NOT NULL,
    priority   INTEGER       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_watchlist PRIMARY KEY (id),
    CONSTRAINT uq_watchlist_user_code UNIQUE (user_id, code),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_instrument FOREIGN KEY (code)
        REFERENCES instrument (code)
);

-- ---------------------------------------------------------------------------
-- 6. candle (ADR 0019)
--    interval CHECK: 11종 정합
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS candle (
    id        BIGSERIAL    NOT NULL,
    code      VARCHAR(8)   NOT NULL,
    interval  VARCHAR(8)   NOT NULL,
    ts        TIMESTAMPTZ  NOT NULL,
    open_raw  NUMERIC(19, 4),
    high_raw  NUMERIC(19, 4),
    low_raw   NUMERIC(19, 4),
    close_raw NUMERIC(19, 4),
    open_adj  NUMERIC(19, 4),
    high_adj  NUMERIC(19, 4),
    low_adj   NUMERIC(19, 4),
    close_adj NUMERIC(19, 4),
    volume    BIGINT,
    source    VARCHAR(20),
    CONSTRAINT pk_candle PRIMARY KEY (id),
    CONSTRAINT uq_candle_code_interval_ts UNIQUE (code, interval, ts),
    CONSTRAINT chk_candle_interval CHECK (
        interval IN ('1m', '2m', '5m', '15m', '30m', '90m', '1h', '1d', '1wk', '1mo', '1y')
    )
);

CREATE INDEX IF NOT EXISTS idx_candle_code_interval_ts ON candle (code, interval, ts DESC);

-- ---------------------------------------------------------------------------
-- 7. position (ADR 0042·0024)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS position (
    id         BIGSERIAL     NOT NULL,
    user_id    VARCHAR(20)   NOT NULL,
    code       VARCHAR(8)    NOT NULL,
    quantity   INTEGER       NOT NULL DEFAULT 0,
    avg_price  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_position PRIMARY KEY (id),
    CONSTRAINT uq_position_user_code UNIQUE (user_id, code),
    CONSTRAINT fk_position_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_position_instrument FOREIGN KEY (code)
        REFERENCES instrument (code)
);

-- ---------------------------------------------------------------------------
-- 8. orders (ADR 0011·0015)
--    테이블명 orders (order는 SQL 예약어)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id            BIGSERIAL    NOT NULL,
    intent_id     UUID         NOT NULL,
    user_id       VARCHAR(20)  NOT NULL,
    code          VARCHAR(8)   NOT NULL,
    side          VARCHAR(8)   NOT NULL,
    order_type    VARCHAR(16)  NOT NULL,
    quantity      INTEGER      NOT NULL,
    price         NUMERIC(19, 4),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reject_reason VARCHAR(64),
    reject_detail TEXT,
    reject_source VARCHAR(32),
    kis_ord_no    VARCHAR(20),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    executed_at   TIMESTAMPTZ,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_intent_id UNIQUE (intent_id),
    CONSTRAINT chk_orders_side   CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_orders_status CHECK (
        status IN ('PENDING', 'PARTIAL_FILLED', 'FILLED', 'REJECTED', 'CANCELLED')
    )
);

CREATE INDEX IF NOT EXISTS idx_orders_user_created ON orders (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 9. kill_switch_state (ADR 0024·0043)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kill_switch_state (
    user_id    VARCHAR(20)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT false,
    reason     VARCHAR(64),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(40),
    CONSTRAINT pk_kill_switch_state PRIMARY KEY (user_id),
    CONSTRAINT fk_kill_switch_state_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 10. notification_outbox (ADR 0044)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_outbox (
    id           BIGSERIAL    NOT NULL,
    event_topic  VARCHAR(60)  NOT NULL,
    payload_json JSONB        NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count  INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    CONSTRAINT pk_notification_outbox PRIMARY KEY (id),
    CONSTRAINT chk_notification_outbox_status CHECK (
        status IN ('PENDING', 'SENT', 'FAILED')
    )
);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_status ON notification_outbox (status, created_at);

-- ---------------------------------------------------------------------------
-- 11. audit_trail (ADR 0015)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_trail (
    id          BIGSERIAL    NOT NULL,
    actor       VARCHAR(40)  NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(40)  NOT NULL,
    detail_json JSONB,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_trail PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_trail_entity ON audit_trail (entity_type, entity_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 초기 데이터 — system_config singleton row (ADR 0046 §2-3)
-- ---------------------------------------------------------------------------
INSERT INTO system_config (id)
VALUES (1)
ON CONFLICT DO NOTHING;
