-- V6__market_calendar_and_reject_audit.sql
-- ADR 0013 §2-2 (market_calendar) + ADR 0015 §2-1 (order_reject_audit) 정합
-- PR-29a backend-core 단독 책임 (dispatch-spec §6-1 LOCK)
-- KIS chk-holiday endpoint: CTCA0903R /uapi/domestic-stock/v1/quotations/chk-holiday
-- 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py

-- =============================================================================
-- §3-4. market_calendar 테이블
-- =============================================================================
CREATE TABLE IF NOT EXISTS market_calendar (
    date          DATE          NOT NULL,
    is_business_day BOOLEAN     NOT NULL,
    is_holiday    BOOLEAN       NOT NULL DEFAULT false,
    holiday_name  VARCHAR(64),
    source        VARCHAR(32)   NOT NULL,  -- 'STATIC_SEED' | 'KIS_CHK_HOLIDAY' | 'MANUAL'
    synced_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_calendar PRIMARY KEY (date),
    CONSTRAINT chk_market_calendar_source CHECK (source IN ('STATIC_SEED', 'KIS_CHK_HOLIDAY', 'MANUAL'))
);

-- 영업일만 빠른 조회 (SubscriptionRouter 영업일 체크 경로)
CREATE INDEX IF NOT EXISTS idx_market_calendar_business
    ON market_calendar (date)
    WHERE is_business_day = true;

COMMENT ON TABLE market_calendar IS 'KRX 영업일 캘린더. 1차: 정적 시드, 2차: KIS chk-holiday(CTCA0903R) cron 동기화';
COMMENT ON COLUMN market_calendar.source IS 'STATIC_SEED: JSON 정적 시드 | KIS_CHK_HOLIDAY: KIS API 응답 | MANUAL: 수동 입력';

-- =============================================================================
-- §3-5. orders 테이블 reject 컬럼 추가
-- =============================================================================
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS reject_reason  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reject_detail  TEXT,
    ADD COLUMN IF NOT EXISTS reject_source  VARCHAR(32);

COMMENT ON COLUMN orders.reject_reason  IS 'RejectReason.code — KILL_SWITCH | SESSION_CLOSED | NOT_BUSINESS_DAY | DAILY_LIMIT | POSITION_LIMIT | DRAWDOWN_STOP | PRICE_SANITY | KIS_API:* | INTERNAL';
COMMENT ON COLUMN orders.reject_source  IS 'GUARD | KIS_API | INTERNAL';

-- =============================================================================
-- §3-5. order_reject_audit 테이블
-- =============================================================================
CREATE TABLE IF NOT EXISTS order_reject_audit (
    id             BIGSERIAL     NOT NULL,
    intent_id      UUID          NOT NULL,
    user_id        BIGINT        NOT NULL,
    code           VARCHAR(8)    NOT NULL,
    side           VARCHAR(8)    NOT NULL,  -- 'BUY' | 'SELL'
    reject_source  VARCHAR(32)   NOT NULL,  -- 'GUARD' | 'KIS_API' | 'INTERNAL'
    reject_reason  VARCHAR(64)   NOT NULL,  -- RejectReason.code
    reject_detail  TEXT,
    occurred_at    TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_reject_audit PRIMARY KEY (id),
    CONSTRAINT chk_reject_audit_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_reject_audit_source CHECK (reject_source IN ('GUARD', 'KIS_API', 'INTERNAL'))
);

-- 사용자별 시간 역순 조회 (audit 이력 화면)
CREATE INDEX IF NOT EXISTS idx_reject_user_time
    ON order_reject_audit (user_id, occurred_at DESC);

-- intent_id 중복 audit 방지 (멱등)
CREATE UNIQUE INDEX IF NOT EXISTS uq_reject_intent
    ON order_reject_audit (intent_id);

COMMENT ON TABLE order_reject_audit IS 'ADR 0015 §2-1 — 주문 거부 감사 로그. retention 90일 (cleanup job post-W3)';
COMMENT ON COLUMN order_reject_audit.intent_id IS '주문 의도 식별자 (UUID) — upsert 중복 방지';
COMMENT ON COLUMN order_reject_audit.reject_source IS 'GUARD: 가드 레이어 | KIS_API: KIS 오류 응답 | INTERNAL: 내부 예외';
