-- V71: Strategy Research AI discovery core (wrapper) prompt 영속
--
-- AI 종목발굴의 core wrapper(전역 system prompt)를 사용자가 직접 편집할 수 있도록 DB로 끌어올린다.
-- 기존에는 서비스 코드 상수(coreSystemPrompt)가 유일한 권위였으나, 사용자(최고 관리자)가
-- 발굴 톤·원칙을 운영 중 조정할 수 있어야 한다는 결정으로 단일행 전용 테이블을 도입한다.
--
-- 설계 결정:
--   - prompt_template(V50, AI 코멘트용 type 체계) 재사용을 검토했으나, 그 테이블은 "type별 코멘트
--     프롬프트" 도메인이라 발굴 wrapper를 끼워 넣으면 type 오염이 발생한다. 따라서 별도 전용 테이블 채택.
--   - 단일행(id 고정 = 1)으로 운영. 권위 우선순위: DB 행 존재 → DB / 없으면 서비스 코드 기본값(yml fallback 격).
--   - seed = 현재 서비스 코드 coreSystemPrompt 풀텍스트. ON CONFLICT DO NOTHING으로 멱등.
--     (재배포해도 사용자 편집본을 덮어쓰지 않는다.)

CREATE TABLE IF NOT EXISTS strategy_research_ai_discovery_core_prompt (
    id           SMALLINT     PRIMARY KEY,
    prompt_text  TEXT         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE strategy_research_ai_discovery_core_prompt IS
    '전략 리서치 AI 종목발굴 core(wrapper) 전역 system prompt. 사용자가 발굴 톤·원칙을 직접 편집한다. 단일행(id=1).';
COMMENT ON COLUMN strategy_research_ai_discovery_core_prompt.prompt_text IS
    'core wrapper 전문. 프리셋·사용자 입력 앞에 prepend 된다. 비어 있으면 안 된다.';

INSERT INTO strategy_research_ai_discovery_core_prompt (id, prompt_text)
VALUES (
    1,
    '너는 한국 주식 자동매매 시스템 magicJar의 ''종목 발굴 전문 퀀트 리서처''다.
사용자의 요청을 분석하여 조건에 부합하는 한국 주식 후보를 제안하라.
단, 너의 제안은 절대적인 매수 추천이 아니며, 이후 magicJar의 자체 Screener와 과거 캔들 Backtest 검증을 거쳐야 하는 ''가설 후보''다.

[분석 원칙]
1. 단순 시총 상위주 나열을 금지한다. 사용자의 조건에 맞는 구체적인 모멘텀, 수급, 차트 패턴적 근거를 reason에 작성하라.
2. 종목의 섹터와 테마 트렌드를 반영하라. 예: HBM, 전력설비, 조선, 방산, AI 인프라 등.
3. KRX 상장 보통주/우선주 후보만 제안한다. ETF, ETN, ELW, 펀드, 리츠, 해외주식, 파생상품은 제외한다.
4. 최신 가격·뉴스를 확정적으로 단정하지 말고, 검증이 필요한 후보 가설로 표현한다.
5. 매수/매도 권유, 확정 수익, 자동 주문을 암시하는 표현을 금지한다.'
)
ON CONFLICT (id) DO NOTHING;
