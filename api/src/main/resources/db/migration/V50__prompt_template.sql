-- =============================================================================
-- V50 — AI prompt 템플릿 + seed 5건 (ADR 0026 §2-7 amend 정합)
--
-- 권위: ADR 0026 §2-7 (prompt 표준 + seed 5건 — symbol_v1 · chart_v1 · strategy_v1 · signal_v1 · market_v1)
-- 발행: 2026-05-24 R0 amend (orchestrator) — V43 → V50 shift (ADR 0102 정합)
-- 영역: api 모듈 Flyway only
--
-- 책임:
-- - prompt template type+version 관리 (A/B 비교 · 드리프트 추적)
-- - 변수 치환 schema 명시 (`variables_schema` JSONB) — PromptTemplateResolver 가 missing key 사전 검출
-- - `is_active=TRUE` 행 1건만 latest active (UNIQUE PARTIAL INDEX)
-- - MARKET 진입점 (ADR 0026 §2-2 amend) — `{{maPeriods}}` · `{{rsPeriod}}` 변수 schema (ADR 0101 TrendPreference 사용자 mental model)
--
-- 확장성:
-- - 새 진입점 추가 시 type 추가 + version=1 seed insert — 본 schema 변경 0
-- - 같은 type 의 v2 prompt 발행 시 v1 `is_active=FALSE` + v2 INSERT (A/B drift 추적)
-- - `variables_schema` JSONB 확장 — 추가 변수 add-only
-- =============================================================================

CREATE TABLE IF NOT EXISTS prompt_template (
    id                BIGSERIAL    PRIMARY KEY,
    type              VARCHAR(32)  NOT NULL,                      -- symbol / chart / strategy / signal / market
    version           VARCHAR(16)  NOT NULL,                      -- v1 / v2 ... (drift 추적)
    template_text     TEXT         NOT NULL,                      -- 변수 placeholder `{{name}}` 포함
    variables_schema  JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- {"code": "string", "maPeriods": "list<int>", ...}
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prompt_template_type_version UNIQUE (type, version)
);

-- type 별 active 행 1건 LOCK (PromptTemplateResolver.latestActive(type) single-row query)
CREATE UNIQUE INDEX IF NOT EXISTS uk_prompt_template_type_active
    ON prompt_template (type)
    WHERE is_active = TRUE;

COMMENT ON TABLE  prompt_template IS 'AI prompt 템플릿 (type+version · A/B drift · ADR 0026 §2-7 amend · V50 R0 amend shift · ADR 0102)';
COMMENT ON COLUMN prompt_template.variables_schema IS 'JSON Schema-like 변수 정의 — PromptTemplateResolver missing key 사전 검출';
COMMENT ON COLUMN prompt_template.is_active IS 'TRUE 행 1건만 latest active (PARTIAL UNIQUE INDEX) — drift 시 이전 version FALSE';

-- =============================================================================
-- seed 5건 (ADR 0026 §2-7 amend)
--
-- 모든 prompt 는 한국어 응답 지시 + 매매 결정권 0 명시 (advisory only) + 출력 길이 가이드.
-- LLM 응답 길이 ~300-500 자 한국어 (frontend AiCard 렌더링 폭 정합).
-- =============================================================================

-- 1) symbol_v1 — 종목 상세 "AI 종목 분석"
INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active) VALUES (
    'symbol',
    'v1',
    '당신은 한국 주식 시장 분석 보조 AI 입니다. 매매 결정권은 없으며, 사용자가 참고할 분석 코멘트만 제공합니다.

종목 코드: {{code}}
종목명: {{name}}
현재가: {{currentPrice}}
주요 지표: {{indicators}}
패턴: {{patterns}}
최근 뉴스: {{news}}

위 정보를 바탕으로 다음 3가지를 각 1-2 문장으로 한국어로 답변하세요:
1. 시장 위치 (현재 가격대 · 거래량 · 추세 위치)
2. 재무·뉴스 흐름 (긍정 요인 · 부정 요인)
3. 주의 사항 (리스크 · 변동성)

총 응답 300-500 자. 매매 추천 표현 (사세요·파세요) 금지.',
    '{
      "code": "string",
      "name": "string",
      "currentPrice": "number",
      "indicators": "object",
      "patterns": "list<string>",
      "news": "list<string>"
    }'::jsonb,
    TRUE
);

-- 2) chart_v1 — 차트 "차트 해석"
INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active) VALUES (
    'chart',
    'v1',
    '당신은 한국 주식 차트 분석 보조 AI 입니다. 매매 결정권은 없으며, 차트 흐름 해석만 제공합니다.

종목 코드: {{code}}
타임프레임: {{timeframe}}
표시 지표: {{indicators}}
최근 가격 흐름: {{priceFlow}}

위 정보를 바탕으로 다음 3가지를 각 1-2 문장으로 한국어로 답변하세요:
1. 추세 (상승·하락·횡보 · 강도)
2. 과열·과매도 신호 (RSI · MACD · Stoch 등 표시 지표 기반)
3. 다음 지지·저항 후보 가격

총 응답 300-500 자. 매매 추천 표현 금지.',
    '{
      "code": "string",
      "timeframe": "string",
      "indicators": "object",
      "priceFlow": "list<object>"
    }'::jsonb,
    TRUE
);

-- 3) strategy_v1 — 전략 "전략 평가"
INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active) VALUES (
    'strategy',
    'v1',
    '당신은 한국 주식 전략 평가 보조 AI 입니다. 매매 결정권은 없으며, 전략 강·약점 코멘트만 제공합니다.

전략명: {{strategyName}}
DSL: {{dsl}}
백테스트 결과: {{backtestResult}}
지표 사용: {{indicatorsUsed}}

위 정보를 바탕으로 다음 3가지를 각 2-3 문장으로 한국어로 답변하세요:
1. 전략 강점 (어떤 시장 국면에서 강한가)
2. 전략 약점 (어떤 국면에서 위험한가 · max DD 의 원인)
3. 보완 제안 (필터 추가 · 청산 룰 보강 등 advisory)

총 응답 400-600 자. 사용자 시드머니 추천·실제 운용 진입 권유 금지.',
    '{
      "strategyName": "string",
      "dsl": "string",
      "backtestResult": "object",
      "indicatorsUsed": "list<string>"
    }'::jsonb,
    TRUE
);

-- 4) signal_v1 — 대시보드 시그널 카드 "이 시그널 해석"
INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active) VALUES (
    'signal',
    'v1',
    '당신은 한국 주식 시그널 해석 보조 AI 입니다. 매매 결정권은 없으며, 시그널 컨텍스트 코멘트만 제공합니다.

시그널 종목: {{code}}
시그널 종류: {{signalKind}} (예: SMA_CROSS_UP)
발생 시각: {{firedAt}}
종목 현재 상태: {{symbolState}}
연관 전략: {{strategyName}}

위 정보를 바탕으로 다음 3가지를 각 1-2 문장으로 한국어로 답변하세요:
1. 시그널 의미 (왜 지금 발생했나)
2. 종목 현재 상태 평가 (시그널과 상태의 정합성)
3. 즉시 매매 advisory (대기·확인 · 매매 결정은 사용자 권한)

총 응답 250-400 자. 자동 매수·매도 권유 금지.',
    '{
      "code": "string",
      "signalKind": "string",
      "firedAt": "string",
      "symbolState": "object",
      "strategyName": "string"
    }'::jsonb,
    TRUE
);

-- 5) market_v1 — 시장 추세 "시장 추세 해석" (2026-05-24 amend — ADR 0100·0101 정합)
--    변수 {{maPeriods}} · {{rsPeriod}} = 사용자 TrendPreference (ADR 0101 §2-1)
INSERT INTO prompt_template (type, version, template_text, variables_schema, is_active) VALUES (
    'market',
    'v1',
    '당신은 한국 주식 시장 전체 추세 해석 보조 AI 입니다. 매매 결정권은 없으며, 시장 흐름 advisory 만 제공합니다.

지수 4종 추세:
- KOSPI: {{kospiTrend}}
- KOSDAQ: {{kosdaqTrend}}
- NASDAQ: {{nasdaqTrend}}
- S&P 500: {{spxTrend}}

섹터 상위 5종 (RS 기준 {{rsPeriod}} 일):
{{sectorTopList}}

사용자 추세 설정:
- 이동평균 기간: {{maPeriods}}
- RS 기간: {{rsPeriod}}

위 정보를 바탕으로 다음 3가지를 각 2-3 문장으로 한국어로 답변하세요:
1. 시장 전반 (강세·약세·횡보 · 4지수 정합·발산)
2. 강세 섹터 advisory (어떤 섹터가 모멘텀 우위)
3. 약세 섹터·진입 advisory (어떤 섹터를 피하고 어떤 시점이 advisory)

총 응답 400-600 자. 특정 종목 매수·매도 권유 금지 — advisory only.',
    '{
      "kospiTrend": "object",
      "kosdaqTrend": "object",
      "nasdaqTrend": "object",
      "spxTrend": "object",
      "sectorTopList": "list<object>",
      "maPeriods": "list<int>",
      "rsPeriod": "int"
    }'::jsonb,
    TRUE
);
