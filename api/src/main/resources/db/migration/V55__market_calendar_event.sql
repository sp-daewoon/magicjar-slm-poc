-- V55__market_calendar_event.sql
-- 시장 캘린더 확장: 휴장일 기준표(market_calendar)와 분리된 사용자 등록 시장 이벤트.

CREATE TABLE IF NOT EXISTS market_calendar_event (
    id              BIGSERIAL     NOT NULL,
    user_id         VARCHAR(20)   NOT NULL,
    event_date      DATE          NOT NULL,
    market          VARCHAR(16)   NOT NULL DEFAULT 'KRX',
    event_type      VARCHAR(32)   NOT NULL,
    title           VARCHAR(120)  NOT NULL,
    description     TEXT,
    instrument_code VARCHAR(16),
    source          VARCHAR(32)   NOT NULL DEFAULT 'MANUAL',
    payload         JSONB,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_calendar_event PRIMARY KEY (id),
    CONSTRAINT fk_market_calendar_event_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_market_calendar_event_user_date
    ON market_calendar_event (user_id, event_date, created_at);

CREATE INDEX IF NOT EXISTS idx_market_calendar_event_type_date
    ON market_calendar_event (event_type, event_date);

CREATE INDEX IF NOT EXISTS idx_market_calendar_event_instrument_date
    ON market_calendar_event (instrument_code, event_date)
    WHERE instrument_code IS NOT NULL;

COMMENT ON TABLE market_calendar_event IS '사용자 등록 시장 일정. IPO, 실적, 배당, 거시 이벤트 등 event_type 문자열로 확장';
COMMENT ON COLUMN market_calendar_event.market IS 'KRX, US, GLOBAL 등 시장 구분. 자동 수집 소스 확장 슬롯';
COMMENT ON COLUMN market_calendar_event.event_type IS 'IPO, EARNINGS, DIVIDEND, MACRO 등. CHECK 제약 없이 확장 가능';
COMMENT ON COLUMN market_calendar_event.source IS 'MANUAL 또는 향후 KRX/DART/IMPORT 등 데이터 출처';
COMMENT ON COLUMN market_calendar_event.payload IS '자동 수집/외부 연동용 원문 또는 부가 메타데이터 JSON';
