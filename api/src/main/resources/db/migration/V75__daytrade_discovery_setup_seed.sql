-- V75: 단타 발굴(Discovery) 빌트인 setup 프리셋 시드 (daytrade-discover backend / ADR 0117 §2-1)
--
-- 최고 관리자 결정 2 (2026-06-09 LOCK): 단타 발굴 프리셋 권위 = V75 Flyway 시드.
-- 직전 spec 옵션 A(코드 권위 BuiltinResearchCandidates add · Flyway 0)는 폐기.
-- 권위는 strategy_research_candidate(V74 스키마)에 INSERT하는 이 row다. 코드(ResearchCandidateProvider)는 read만.
--
-- V74 18종 후보(sort_order 10~180)와 별도 구간 200~ 로 단타 setup 프리셋을 분리 시드한다.
-- candidate_key = DAYTRADE_* prefix (V74 key와 UNIQUE 충돌 0).
-- ON CONFLICT (candidate_key) DO NOTHING — V70 preset·V74 시드 선례 정합. builtin 삭제 시 재배포해도 부활 X.
-- created_at/updated_at은 DEFAULT NOW() 위임 (INSERT 컬럼 목록에서 생략 — V74 패턴).
--
-- ★ entry_dsl LOCK (spec §3-R2): 장전 setup = 일봉 표현 가능 식별자만 사용.
--   close · open · volume · prev_close · atr · natr · volume_ma — KisYamlDslParser 화이트리스트 통과 검증됨.
--   trade_intensity(체결강도 — tick-only)는 절대 미포함. 그건 R1 장중 확인 경로 영역이며,
--   일봉 candle 평가 시 식별자 미해결로 false 처리되므로 장전 발굴에 부적합.
--   갭상승 = (open - prev_close) / prev_close, RVOL = volume / volume_ma, 거래대금 = close * volume, 변동성 = atr / close.

INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl, target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'DAYTRADE_GAP_RVOL_TURNOVER',
        '장전 갭상승·RVOL·거래대금 setup',
        '전일 종가 대비 갭상승 출발 + 거래량이 평소보다 크게 터지고 거래대금이 충분한, 장중 변동성 단타에 적합한 종목을 장전에 압축합니다.',
        '["전일 종가 대비 1.5% 이상 갭상승","거래량이 최근 20봉 평균의 2.5배 이상(RVOL)","거래대금이 100억 원 이상(유동성)","주가 2,000원 이상(동전주 제외)"]'::jsonb,
        'open >= prev_close * 1.015 and volume > volume_ma(20) * 2.5 and close * volume > 10000000000 and close > 2000',
        2.0, 1.2, 8, 200, TRUE, TRUE
    ),
    (
        'DAYTRADE_VOLATILITY_EXPANSION',
        '장전 변동성 확장·거래량 setup',
        '하루 변동폭(ATR)이 단타에 적합한 sweet spot(1.5%~6%) 안에 있고 거래량이 살아난 종목을 장전에 추립니다. 너무 잔잔하거나 과도하게 급변하는 종목은 제외합니다.',
        '["일중 변동성(ATR/주가)이 1.5% 이상","일중 변동성(ATR/주가)이 6% 이하","거래량이 최근 20봉 평균의 2배 이상","거래대금이 50억 원 이상"]'::jsonb,
        'atr(14) > close * 0.015 and atr(14) < close * 0.06 and volume > volume_ma(20) * 2.0 and close * volume > 5000000000',
        1.8, 1.0, 8, 210, TRUE, TRUE
    ),
    (
        'DAYTRADE_MOMENTUM_BREAKOUT',
        '장전 모멘텀 양봉·거래대금 setup',
        '전일 강한 양봉(0.8% 이상 상승)으로 마감하며 거래량과 거래대금이 동반된, 익일 모멘텀 지속을 노리는 단타 후보를 압축합니다.',
        '["전일 양봉 마감","전일 수익률 0.8% 이상","거래량이 최근 20봉 평균의 1.8배 이상","거래대금이 80억 원 이상"]'::jsonb,
        'close > open and close >= prev_close * 1.008 and volume > volume_ma(20) * 1.8 and close * volume > 8000000000',
        2.0, 1.2, 8, 220, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;
