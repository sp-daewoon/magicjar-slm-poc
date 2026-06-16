-- V70: Strategy Research AI discovery presets
--
-- AI 종목발굴의 core wrapper는 코드 권위로 유지하고, 사용자가 선택/수정하는 intent prompt만
-- 전용 preset 테이블에서 관리한다. 이 preset은 매수 추천이 아니라 Screener/Backtest 검증용
-- 후보 가설을 만드는 템플릿이다.

CREATE TABLE IF NOT EXISTS strategy_research_ai_discovery_preset (
    id           VARCHAR(64)  PRIMARY KEY,
    label        VARCHAR(80)  NOT NULL,
    description  VARCHAR(240) NOT NULL,
    prompt_text  TEXT         NOT NULL,
    sort_order   INTEGER      NOT NULL DEFAULT 100,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_builtin   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_strategy_research_ai_discovery_preset_active_order
    ON strategy_research_ai_discovery_preset (is_active, sort_order, id);

COMMENT ON TABLE strategy_research_ai_discovery_preset IS
    '전략 리서치 AI 종목발굴 intent preset. 사용자가 발굴 로직 prompt를 확인/수정한다.';
COMMENT ON COLUMN strategy_research_ai_discovery_preset.prompt_text IS
    'Core wrapper 뒤에 주입되는 intent template prompt. 매수 추천이 아니라 검증 후보 가설 생성용.';

ALTER TABLE strategy_research_ai_discovery_state
    ADD COLUMN IF NOT EXISTS preset_id VARCHAR(64);

COMMENT ON COLUMN strategy_research_ai_discovery_state.preset_id IS
    '마지막 AI 종목발굴에 사용한 preset id. null이면 직접 입력만 사용.';

INSERT INTO strategy_research_ai_discovery_preset
    (id, label, description, prompt_text, sort_order, is_active, is_builtin)
VALUES
    (
        'scalping_momentum',
        '단기 거래대금 폭발',
        '5분봉 스캘핑·당일 단타 검증용. 거래대금, 일중 변동성, 단기 뉴스 모멘텀을 중시합니다.',
        '현재 시장에서 거래대금이 집중되고 일중 변동성(ATR)이 큰 종목을 위주로 탐색해라. 특히 최근 1~2주 내에 강력한 거래량을 동반한 장대양봉이 출현했거나, 특정 뉴스 모멘텀(수주, 실적 등)으로 인해 장중 등락폭이 커서 5분봉 기준 스캘핑 및 눌림목 공략에 적합한 종목을 발굴해라.',
        10,
        TRUE,
        TRUE
    ),
    (
        'smart_money_orderblock',
        '오더블록 지지 후보',
        'FVG, 장악형 캔들, 기관·외국인 수급 가설을 검증할 구조적 반등 후보를 찾습니다.',
        '최근 강한 추세(상승 또는 하락)를 만들며 Fair Value Gap(FVG)을 형성했거나, 일봉/4시간봉 단위에서 명확한 장악형 캔들(오더블록)을 만들어 지지/저항 구간이 뚜렷한 종목을 찾아라. 기관이나 외국인의 스마트 머니 유입이 강하게 의심되며, 주요 매물대(오더블록 상단/하단)에서 기술적 반등이나 돌파를 테스트하기 좋은 후보를 제안해라.',
        20,
        TRUE,
        TRUE
    ),
    (
        'sector_rotation_swing',
        '섹터 순환매 대기',
        '주도 섹터 2·3등주, 20일선 눌림목, 며칠에서 몇 주 스윙 검증 후보를 찾습니다.',
        '현재 한국 주식 시장을 주도하는 메인 섹터(예: 반도체, 방산, 조선 등) 중에서 1등주가 급등한 후, 그 온기가 퍼지며 후속 순환매가 기대되는 2~3등주를 찾아라. 또는 주도 섹터 내에서 단기 과열을 식히고 20일 이동평균선 부근에서 건전한 눌림목을 형성하고 있는 스윙 검증용 후보를 제안해라.',
        30,
        TRUE,
        TRUE
    )
ON CONFLICT (id) DO NOTHING;
