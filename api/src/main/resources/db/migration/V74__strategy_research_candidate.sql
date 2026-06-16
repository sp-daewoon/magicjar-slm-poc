-- V74: Strategy Research 후보 전략 DB화 (research-candidates-db)
--
-- 기존에 StrategyResearchEngine 코드에 Kotlin 람다로 하드코딩되어 있던 18개 리서치 후보를
-- DB 테이블로 옮긴다. 진입 조건은 entry_dsl(strategy-engine 평가기 실행 권위)로 통일하고,
-- 청산은 target/stop/maxHold 파라미터로 표현한다(엔진이 exit profile 스윕으로 재구성).
--
-- 사용자가 본인 전략을 후보로 추가할 수 있다(is_builtin=false). builtin 후보도 삭제 허용하며,
-- 이 시드는 ON CONFLICT DO NOTHING 이라 재배포해도 부활하지 않는다(V70 preset 선례 정합).

CREATE TABLE IF NOT EXISTS strategy_research_candidate (
    id                 BIGSERIAL    PRIMARY KEY,
    candidate_key      VARCHAR(64)  NOT NULL UNIQUE,
    title              VARCHAR(120) NOT NULL,
    market_fit         VARCHAR(400) NOT NULL,
    entry_rules        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    entry_dsl          TEXT         NOT NULL,
    target_profit_pct  NUMERIC(9,4),
    stop_loss_pct      NUMERIC(9,4),
    max_hold_bars      INTEGER,
    sort_order         INTEGER      NOT NULL DEFAULT 100,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    is_builtin         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_strategy_research_candidate_active_order
    ON strategy_research_candidate (is_active, sort_order, id);

COMMENT ON TABLE strategy_research_candidate IS
    '전략 리서치 후보 전략. 진입 조건은 entry_dsl(strategy-engine 평가기 실행)이 권위. 매수 추천이 아니라 검증용 후보 출발점.';
COMMENT ON COLUMN strategy_research_candidate.entry_dsl IS
    '진입 DSL 식. KisYamlDslParser 화이트리스트 통과 + JvmStrategyEvaluator 실행 권위.';
COMMENT ON COLUMN strategy_research_candidate.target_profit_pct IS
    '기본 익절 %(null이면 엔진 기본 exit profile 사용). 엔진은 여러 exit profile을 스윕해 best를 고른다.';
COMMENT ON COLUMN strategy_research_candidate.is_builtin IS
    '코드/시드 기본 후보 여부. false=사용자가 본인 전략에서 추가. builtin도 삭제 가능(부활 X).';

INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl, target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'MA10_RECLAIM_SCALP',
        'MA10 회복 반등 스캘프',
        '하락 추세 중 단기 평균선 회복 구간만 짧게 노립니다.',
        '["직전 candle 종가가 MA10 아래","현재 candle 종가가 MA10 위로 회복","현재 종가가 시가 이상","MA20 대비 과열 구간은 제외"]'::jsonb,
        'close crosses_above ma(10) and close >= open and close <= ma(20) * 1.025',
        1.5, 0.8, 12, 10, TRUE, TRUE
    ),
    (
        'RSI30_REBOUND_SCALP',
        'RSI30 재진입 반등 스캘프',
        '급락 뒤 과매도에서 빠져나오는 첫 반등만 잡습니다.',
        '["직전 RSI14가 30 이하","현재 RSI14가 30 위로 회복","현재 종가가 직전 종가보다 높음","거래량이 최근 20개 평균의 60% 이상"]'::jsonb,
        'rsi(14) crosses_above 30 and close > prev_close and volume >= volume_ma(20) * 0.6',
        1.5, 0.8, 12, 20, TRUE, TRUE
    ),
    (
        'VOLUME_WASHOUT_REBOUND',
        '거래량 투매 후 반등 스캘프',
        '투매성 음봉 뒤 저가 방어와 거래량 반등이 보일 때만 진입합니다.',
        '["직전 candle 종가 대비 약세 뒤 회복","거래량이 최근 20개 평균의 1.2배 이상","현재 candle이 양봉","현재 종가가 직전 종가를 회복"]'::jsonb,
        'close > open and close >= prev_close and volume >= volume_ma(20) * 1.2',
        1.5, 0.8, 12, 30, TRUE, TRUE
    ),
    (
        'MA20_PULLBACK_CONTINUATION',
        'MA20 눌림목 추세 지속',
        '중기 상승 흐름에서 MA20 근처 눌림 후 재상승하는 구간을 노립니다.',
        '["MA20이 MA50 위에 있음","현재 저가가 MA20 근처까지 눌림","현재 종가가 MA20 위로 회복","현재 candle이 양봉이고 거래량이 평균의 70% 이상"]'::jsonb,
        'ma(20) > ma(50) and close >= ma(20) and low <= ma(20) * 1.01 and close > open and volume >= volume_ma(20) * 0.7',
        1.5, 0.8, 12, 40, TRUE, TRUE
    ),
    (
        'MA5_20_GOLDEN_CROSS',
        'MA5·MA20 골든크로스',
        '짧은 평균선이 중기 평균선을 상향 돌파하는 초기 반전 구간을 평가합니다.',
        '["직전 MA5가 MA20 이하","현재 MA5가 MA20 위로 돌파","현재 candle이 양봉","거래량이 최근 20개 평균의 80% 이상"]'::jsonb,
        'ma(5) crosses_above ma(20) and close > open and volume >= volume_ma(20) * 0.8',
        1.5, 0.8, 12, 50, TRUE, TRUE
    ),
    (
        'BREAKOUT_20_HIGH_VOLUME',
        '20봉 고점 거래량 돌파',
        '최근 박스권 상단을 거래량과 함께 돌파하는 모멘텀 구간을 봅니다.',
        '["현재 종가가 직전 20개 candle 고점 위","거래량이 최근 20개 평균의 1.5배 이상","현재 candle이 양봉","종가가 candle 상단부에 위치"]'::jsonb,
        'close crosses_above donchian_upper(20) and volume > volume_ma(20) * 1.5 and close > open',
        1.5, 0.8, 12, 60, TRUE, TRUE
    ),
    (
        'RSI40_TREND_PULLBACK',
        '상승 추세 RSI40 눌림 회복',
        'MA50 위 상승 흐름에서 RSI40을 되찾는 눌림 회복 구간을 노립니다.',
        '["현재 종가가 MA50 위","직전 RSI14가 40 아래","현재 RSI14가 40 위로 회복","현재 candle이 양봉"]'::jsonb,
        'close > ma(50) and rsi(14) crosses_above 40 and close > open',
        1.5, 0.8, 12, 70, TRUE, TRUE
    ),
    (
        'RSI50_MOMENTUM_RECLAIM',
        'RSI50 모멘텀 회복',
        '중립선을 되찾으며 단기 모멘텀이 살아나는 종목을 평가합니다.',
        '["현재 종가가 MA20 위","직전 RSI14가 50 아래","현재 RSI14가 50 위로 회복","거래량이 최근 20개 평균의 80% 이상"]'::jsonb,
        'close > ma(20) and rsi(14) crosses_above 50 and volume >= volume_ma(20) * 0.8',
        1.5, 0.8, 12, 80, TRUE, TRUE
    ),
    (
        'RANGE_SUPPORT_BOUNCE',
        '박스권 하단 지지 반등',
        '최근 저점대 근처에서 저가 방어 후 반등하는 평균회귀 구간을 봅니다.',
        '["현재 저가가 직전 20개 candle 저점 근처","현재 candle이 양봉","현재 종가가 직전 종가보다 높음","RSI14가 35 이상 60 이하"]'::jsonb,
        'low <= donchian_lower(20) * 1.01 and close > open and close > prev_close and rsi(14) >= 35 and rsi(14) <= 60',
        1.5, 0.8, 12, 90, TRUE, TRUE
    ),
    (
        'BB_LOWER_RECLAIM',
        '볼린저 하단 회복 반등',
        '볼린저 하단 이탈 후 다시 밴드 안으로 회복하는 과매도 반등을 봅니다.',
        '["직전 종가가 볼린저 하단 아래","현재 종가가 볼린저 하단 위로 회복","현재 candle이 양봉","거래량이 최근 20개 평균의 70% 이상"]'::jsonb,
        'close crosses_above bb_lower(20, 2) and close > open and volume >= volume_ma(20) * 0.7',
        1.5, 0.8, 12, 100, TRUE, TRUE
    ),
    (
        'DONCHIAN10_BREAKOUT',
        '10봉 단기 돌파',
        '짧은 박스권을 빠르게 벗어나는 단기 추세 시동을 평가합니다.',
        '["현재 종가가 직전 10개 candle 고점 위","거래량이 최근 20개 평균의 1.2배 이상","현재 candle이 양봉","종가가 candle 상단부에 위치"]'::jsonb,
        'close crosses_above donchian_upper(10) and volume > volume_ma(20) * 1.2 and close > open',
        1.5, 0.8, 12, 110, TRUE, TRUE
    ),
    (
        'VOLUME_SURGE_GREEN',
        '거래량 급증 장대양봉',
        '거래량이 평소보다 크게 터지며 강한 양봉이 나온 구간을 추적합니다.',
        '["거래량이 최근 20개 평균의 2배 이상","현재 candle 수익률이 0.8% 이상","현재 candle이 양봉","종가가 candle 상단부에 위치"]'::jsonb,
        'close > open and volume > volume_ma(20) * 2 and close >= open * 1.008',
        1.5, 0.8, 12, 120, TRUE, TRUE
    ),
    (
        'LOW_VOLUME_PULLBACK_BREAK',
        '저거래 눌림 후 전봉 돌파',
        '거래가 말라붙은 눌림 이후 직전 고가를 회복하는 조용한 재시동을 봅니다.',
        '["현재 종가가 직전 고가 위","현재 종가가 MA20 위","현재 거래량이 평균의 1.1배 이상","직전 거래가 마른 구간"]'::jsonb,
        'close crosses_above donchian_upper(2) and close > ma(20) and volume >= volume_ma(20) * 1.1',
        1.5, 0.8, 12, 130, TRUE, TRUE
    ),
    (
        'THREE_BAR_RECOVERY',
        '3연속 종가 회복',
        '작은 반등이 3개 candle 연속 이어질 때 추세 회복 가능성을 봅니다.',
        '["최근 종가가 연속 상승","현재 종가가 MA10 위","현재 candle이 양봉","거래량이 최근 20개 평균의 70% 이상"]'::jsonb,
        'consecutive(up) >= 2 and close > ma(10) and close > open and volume >= volume_ma(20) * 0.7',
        1.5, 0.8, 12, 140, TRUE, TRUE
    ),
    (
        'HAMMER_VOLUME_REVERSAL',
        '거래량 동반 망치형 반전',
        '아래꼬리로 매도 압력을 흡수한 뒤 양봉 마감하는 반전 후보입니다.',
        '["망치형(아래꼬리 긴) 캔들","현재 candle이 양봉","거래량이 최근 20개 평균의 1.2배 이상"]'::jsonb,
        'pattern(hammer) and close > open and volume >= volume_ma(20) * 1.2',
        1.5, 0.8, 12, 150, TRUE, TRUE
    ),
    (
        'GAP_DOWN_REVERSAL',
        '갭하락 회복 반전',
        '시초 또는 구간 갭하락을 빠르게 메우는 강한 되돌림을 추적합니다.',
        '["시가가 직전 종가보다 낮게 출발","현재 종가가 직전 종가 근처까지 회복","현재 candle이 양봉","거래량이 최근 20개 평균 이상"]'::jsonb,
        'close > open and open <= prev_close * 0.98 and close >= prev_close * 0.995 and volume >= volume_ma(20)',
        1.5, 0.8, 12, 160, TRUE, TRUE
    ),
    (
        'VOLATILITY_SQUEEZE_BREAKOUT',
        '변동성 수축 후 돌파',
        '거래 범위가 작아진 뒤 단기 고점을 돌파하는 에너지 방출 구간을 봅니다.',
        '["현재 종가가 직전 10개 candle 고점 위","거래량이 최근 20개 평균 이상","현재 candle이 양봉"]'::jsonb,
        'close crosses_above donchian_upper(10) and volume >= volume_ma(20) and close > open',
        1.5, 0.8, 12, 170, TRUE, TRUE
    ),
    (
        'MA50_TREND_DIP_RECLAIM',
        'MA50 상승 추세 MA20 재회복',
        '큰 추세는 유지된 상태에서 MA20 이탈 후 빠르게 되찾는 눌림목을 평가합니다.',
        '["MA20이 MA50 위","직전 종가가 MA20 아래","현재 종가가 MA20 위로 회복","RSI14가 45 이상 65 이하"]'::jsonb,
        'ma(20) > ma(50) and close crosses_above ma(20) and close > open and rsi(14) >= 45 and rsi(14) <= 65',
        1.5, 0.8, 12, 180, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;
