-- ============================================================================
-- V87 — strategy_daily_note (전략×일자 메모)
-- ============================================================================
-- spec: _workspace/specs/2026-06-16-strategy-daily-performance.md §3 (V87) · §7-4 태그 시드
-- 목적: 전략×일자 정성 관찰을 자유텍스트 + 태그로 누적한다.
--       "이런 장에 이 전략이 좋았다"를 장기 축적 (성과 히스토리 일자행과 1:1 짝).
--       journal_note(V29) 패턴 참고. 일자당 단일 메모를 UPSERT(수정 가능).
-- consumer: api PUT /api/strategy/{id}/note/{date} (당일치 UPSERT) · GET .../note (타임라인).
--
-- UQ (user_id, strategy_id, trade_date):
--   (전략, 일자)당 단일 메모. PUT 시 INSERT … ON CONFLICT DO UPDATE 멱등 수정 키.
--
-- 컨벤션: IF NOT EXISTS LOCK (헌법 §6) · BIGSERIAL PK · TIMESTAMPTZ DEFAULT now() ·
--         user_id VARCHAR(40) (strategy_daily_performance V85 형제 정합) ·
--         tags JSONB NOT NULL DEFAULT '[]' (strategy_position.executed_exit_stages V64 JSONB 패턴 정합).
-- ============================================================================
CREATE TABLE IF NOT EXISTS strategy_daily_note (
    id           BIGSERIAL       PRIMARY KEY,
    user_id      VARCHAR(40)     NOT NULL,
    strategy_id  BIGINT          NOT NULL,
    trade_date   DATE            NOT NULL,                  -- KST 영업일 (성과 일자행과 짝)
    note_text    TEXT            NULL,                      -- 자유 메모
    tags         JSONB           NOT NULL DEFAULT '[]',     -- 태그 배열 (추천 시드 + 자유 입력)
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- (전략, 일자)당 단일 메모 UPSERT 키 (수정 가능).
CREATE UNIQUE INDEX IF NOT EXISTS ux_strategy_daily_note_user_strategy_date
    ON strategy_daily_note (user_id, strategy_id, trade_date);

-- 전략 메모 타임라인 조회(최신 일자순).
CREATE INDEX IF NOT EXISTS ix_strategy_daily_note_strategy_date_desc
    ON strategy_daily_note (strategy_id, trade_date DESC);

COMMENT ON TABLE  strategy_daily_note            IS 'spec 2026-06-16 §3 — 전략×일자 자유 메모 + 태그. 성과 히스토리 일자행과 1:1, journal_note(V29) 패턴 참고.';
COMMENT ON COLUMN strategy_daily_note.trade_date IS 'KST 영업일 — 성과 일자행과 짝. (user,strategy,date)당 단일 메모.';
COMMENT ON COLUMN strategy_daily_note.note_text  IS '자유 메모 텍스트 (nullable — 태그만 달 수도 있음).';
COMMENT ON COLUMN strategy_daily_note.tags       IS '태그 배열 JSONB (추천 시드: 거래량부족·변동성과다·뉴스이벤트·손절잘됨·익절놓침·추세양호·슬리피지큼·갭상승·갭하락 + 자유 입력).';
