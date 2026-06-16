-- V51__user_preference.sql — TrendPreference 1급 도메인 + forward-compat 확장 슬롯 단일 진입점
-- ADR 0101 §2-2 + brainstorming §2-5 정합 (2026-05-24 R0 amend V44 → V51 shift · ADR 0102 정합)
--
-- ## schema 정합 정정 (R1 backend-core audit 결과)
-- ADR 0101 §2-2 DDL 초안은 `user_id BIGINT REFERENCES user_account(id)` 로 작성되었으나
-- main HEAD V1 baseline 의 `user_account.user_id VARCHAR(20)` (KIS 계좌번호 자연 키 — ADR 0046 §2-1) 와 불일치.
-- 본 R1 backend-core 가 audit 후 실제 schema 정합으로 `user_id VARCHAR(20) REFERENCES user_account(user_id)` 적용.
-- 본 결함 evidence + ADR 0101 본문 amend 의무는 PR 본문 §2 결함 보고 슬롯에 명시.
--
-- ## CHECK 제약 2건 (Kotlin require + DB CHECK 이중 방어)
-- - chk_user_pref_ma_periods_size — jsonb_array_length 1..5
-- - chk_user_pref_rs_period — 1..252
-- (crossPair invariant 는 application Kotlin require 본문 + 단위 테스트 fixture)
--
-- ## 시드 정합 (idempotent)
-- 기존 user_account 모든 row 에 default [5,20,60] · rsPeriod 60 시드 → 마이그레이션 즉시 모든 사용자 default pref 보유.
-- `ON CONFLICT DO NOTHING` 으로 재실행 안전.

CREATE TABLE IF NOT EXISTS user_preference (
    user_id            VARCHAR(20)   NOT NULL,
    trend_ma_periods   JSONB         NOT NULL DEFAULT '[5,20,60]'::jsonb,
    trend_rs_period    INT           NOT NULL DEFAULT 60,
    trend_cross_pair   JSONB         NULL,
    extra              JSONB         NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_preference PRIMARY KEY (user_id),
    CONSTRAINT fk_user_preference_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT chk_user_pref_ma_periods_size
        CHECK (jsonb_array_length(trend_ma_periods) BETWEEN 1 AND 5),
    CONSTRAINT chk_user_pref_rs_period
        CHECK (trend_rs_period BETWEEN 1 AND 252)
);

CREATE INDEX IF NOT EXISTS idx_user_pref_updated
    ON user_preference (updated_at DESC);

COMMENT ON TABLE user_preference IS
    '사용자 설정 단일 진입점 (ADR 0101). extra JSONB 는 forward-compat 확장 슬롯 (MA type · indicator · 차트 · 알림 등 add-only 흡수).';
COMMENT ON COLUMN user_preference.user_id IS
    'user_account.user_id FK (VARCHAR(20) KIS 계좌번호 자연 키 — ADR 0046 §2-1 · ON DELETE CASCADE).';
COMMENT ON COLUMN user_preference.trend_ma_periods IS
    'JSONB array — MA 기간 list (1~5개 · 각 1~252일 · sorted ASC). default [5,20,60].';
COMMENT ON COLUMN user_preference.trend_rs_period IS
    'RS rating 비교 기간 (1~252일). default 60.';
COMMENT ON COLUMN user_preference.trend_cross_pair IS
    'crossPair 명시 — {"short":5,"long":60} 형식. null 시 maPeriods 최소/최대 auto.';
COMMENT ON COLUMN user_preference.extra IS
    'forward-compat 확장 슬롯 — MA type · indicator · 차트 default · 알림 임계 등 add-only 흡수. Kotlin 측 Map<String, Any?>.';

-- 시드 — 기존 user_account 모든 row 에 default pref 채움 (idempotent)
INSERT INTO user_preference (user_id, trend_ma_periods, trend_rs_period)
SELECT user_id, '[5,20,60]'::jsonb, 60
FROM user_account
ON CONFLICT (user_id) DO NOTHING;
