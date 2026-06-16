-- =============================================================================
-- V7 — corp_action 테이블 (T-CA1 PR-29b market-data 책임)
-- ADR 0018 §2-1 정합
-- 작성: market-data (PR-29b)
-- =============================================================================
-- corp_action: KIS ksdinfo 6 endpoint (HHKDB669100C0~HHKDB669106C0) +
--              DART OPENDART list.json (pblntf_ty=B·D) 수집 결과 영속
--
-- UNIQUE(instrument_code, type, ex_date, source):
--   동일 이벤트를 KIS·DART 양쪽에서 발견해도 source별 독립 보존 (감사용).
--   CandleAdjustmentJob은 KIS 우선 적용 (ADR 0018 §4-2).
-- =============================================================================

CREATE TABLE corp_action (
    id               BIGSERIAL     NOT NULL,
    instrument_code  VARCHAR(8)    NOT NULL,
    type             VARCHAR(32)   NOT NULL,   -- CorpActionType enum 문자열
    ex_date          DATE          NOT NULL,   -- 권리락 기준일
    ratio            NUMERIC(15, 8),            -- 분할/증자 비율 (null=해당없음)
    cash_dividend    NUMERIC(15, 4),            -- 현금배당금 원/주 (null=해당없음)
    merge_ratio      NUMERIC(15, 8),            -- 합병·분할 비율
    source           VARCHAR(32)   NOT NULL,   -- 'KIS_KSDINFO' | 'DART'
    raw_payload      JSONB,                     -- 원본 API 응답 JSON (감사용)
    applied_at       TIMESTAMPTZ,               -- CandleAdjustmentJob 적용 완료 시각
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_corp_action PRIMARY KEY (id),
    CONSTRAINT chk_corp_action_type CHECK (
        type IN (
            'STOCK_SPLIT', 'REVERSE_SPLIT', 'RIGHTS_OFFERING', 'BONUS_ISSUE',
            'CASH_DIVIDEND', 'STOCK_DIVIDEND', 'MERGER', 'SPINOFF',
            'CAPITAL_DECREASE', 'DELISTING_NOTICE'
        )
    ),
    CONSTRAINT chk_corp_action_source CHECK (source IN ('KIS_KSDINFO', 'DART'))
);

-- 멱등 upsert 기준 UNIQUE (동일 이벤트 source별 독립 보존)
CREATE UNIQUE INDEX uq_corp_action_event
    ON corp_action (instrument_code, type, ex_date, source);

-- CandleAdjustmentJob 미적용 건 조회
CREATE INDEX idx_corp_action_pending
    ON corp_action (applied_at)
    WHERE applied_at IS NULL;

-- 종목 + 기준일 기반 조회
CREATE INDEX idx_corp_action_code_ex
    ON corp_action (instrument_code, ex_date DESC);
