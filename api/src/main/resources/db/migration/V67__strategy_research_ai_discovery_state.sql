-- V67: Strategy Research AI discovery current state
--
-- 사용자별 AI 종목발굴의 마지막 조건과 후보 상태를 보관한다.
-- 재발굴 시 KEPT 후보는 유지하고 DISMISSED 후보는 제외 프롬프트/필터에 사용한다.

CREATE TABLE IF NOT EXISTS strategy_research_ai_discovery_state (
    user_id         VARCHAR(40) PRIMARY KEY,
    query           TEXT        NOT NULL,
    market          VARCHAR(16),
    candidate_limit INTEGER     NOT NULL CHECK (candidate_limit BETWEEN 1 AND 12),
    provider        VARCHAR(64) NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL,
    warnings        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    items           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_strategy_research_ai_discovery_updated
    ON strategy_research_ai_discovery_state (updated_at DESC);

COMMENT ON TABLE strategy_research_ai_discovery_state IS
    '전략 리서치 AI 종목발굴 마지막 상태. 사용자별 current-state, KEPT/DISMISSED 후보를 JSONB에 보관.';
COMMENT ON COLUMN strategy_research_ai_discovery_state.items IS
    'StrategyResearchAiDiscoveryItemDto JSON 배열. status=NEW/KEPT/DISMISSED로 재발굴 병합 제어.';
