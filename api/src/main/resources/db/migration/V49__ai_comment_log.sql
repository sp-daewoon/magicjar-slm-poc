-- =============================================================================
-- V49 — AI 코멘트 호출 로그 (ADR 0026 §2-5 amend 정합)
--
-- 권위: ADR 0026 §2-5 (AI 비용 통제 — `cost_usd` 월 누적 → ADR 0095 amend S-AI-1 $10/월 임계 Slack CRITICAL alert)
-- 발행: 2026-05-24 R0 amend (orchestrator) — V42 → V49 shift (ADR 0102 정합)
-- 영역: api 모듈 Flyway only — `consumer` / `batch` 마이그레이션 0건 (ADR 0006 정합)
--
-- 책임:
-- - 모든 LLM 호출 audit 기록 (provider · model · type · tokens · latency · cost)
-- - 1일 1사용자 토큰 한도 집계 (`AiTokenLimitGuard.dailyTokens()` 본 표 조회)
-- - 월 비용 누적 (`AiCostTracker.monthlySpend()` cost_usd SUM)
-- - 캐시 hit 감사 (`cached=TRUE` 행은 LLM 호출 0건 / tokens=0)
-- - graceful refuse 감사 (`graceful=TRUE` 행은 KillSwitch / 토큰 한도 / mock fallback)
--
-- 인덱스 전략:
-- - `(user_id, created_at)` — 1일 토큰 한도 집계 (`WHERE user_id=? AND created_at >= now() - interval '1 day'`)
-- - `(provider, created_at)` — provider 별 월 비용 집계
-- - `(request_id)` — STOMP fanout 추적 (`/topic/ai.explain.{requestId}`)
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_comment_log (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID            NOT NULL,
    user_id         BIGINT          NOT NULL,
    comment_type    VARCHAR(32)     NOT NULL,                      -- SYMBOL / CHART / STRATEGY / SIGNAL / MARKET (ADR 0026 §2-2 amend)
    provider        VARCHAR(32)     NOT NULL,                      -- anthropic / gemini / openai / mock
    model           VARCHAR(128)    NOT NULL,                      -- claude-3-5-haiku-20241022 등
    prompt_template VARCHAR(64)     NOT NULL,                      -- prompt_template:type:version (예: market_v1)
    tokens_in       INTEGER         NOT NULL DEFAULT 0,
    tokens_out      INTEGER         NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12, 6)  NOT NULL DEFAULT 0,            -- ADR 0095 amend S-AI-1 누적 source
    latency_ms      BIGINT          NOT NULL DEFAULT 0,
    cached          BOOLEAN         NOT NULL DEFAULT FALSE,        -- TRUE = Redis prompt cache hit (LLM 호출 0건)
    graceful        BOOLEAN         NOT NULL DEFAULT FALSE,        -- TRUE = KillSwitch / 토큰 한도 / mock refuse
    error_message   TEXT,                                          -- provider 실패 시 (Resilience4j 모두 fail 후 last cause)
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 1일 토큰 한도 집계 (가장 빈번 — `AiTokenLimitGuard.dailyTokens(userId)`)
CREATE INDEX IF NOT EXISTS idx_ai_comment_log_user_created
    ON ai_comment_log (user_id, created_at DESC);

-- provider 별 월 비용 집계 (`AiCostTracker.monthlySpendByProvider()`)
CREATE INDEX IF NOT EXISTS idx_ai_comment_log_provider_created
    ON ai_comment_log (provider, created_at DESC);

-- STOMP fanout 추적 (`/topic/ai.explain.{requestId}` 디버깅용)
CREATE INDEX IF NOT EXISTS idx_ai_comment_log_request_id
    ON ai_comment_log (request_id);

COMMENT ON TABLE  ai_comment_log IS 'AI 코멘트 호출 audit · 토큰 한도 집계 · 월 비용 누적 (ADR 0026 §2-5 amend · V49 R0 amend shift · ADR 0102)';
COMMENT ON COLUMN ai_comment_log.cost_usd IS 'tokens_in × provider input cost + tokens_out × provider output cost (provider cost 표는 AiCostTracker 코드 LOCK · ADR 0095 amend S-AI-1 누적 source)';
COMMENT ON COLUMN ai_comment_log.cached  IS 'TRUE = Redis ai:prompt:cache:{templateHash} 5분 hit · LLM 호출 0건 · tokens=0';
COMMENT ON COLUMN ai_comment_log.graceful IS 'TRUE = KillSwitch / 1일 한도 / mock fallback / disabled 분기 — 사용자 친화 한국어 응답';
