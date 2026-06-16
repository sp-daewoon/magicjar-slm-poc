-- V76: 단타 발굴 프리셋 — R3 IS/OOS 검증 결과 반영 (ADR 0117 §2-1 연장)
--
-- 출처: _workspace/strategy/2026-06-10-v76-preset-validation-report.md (task T-20260610-0222).
-- #858 연구 문서 8가설 + V75 3종을 R3 ④검증(overfit IS/OOS + sensitivity + 레짐 분할)으로 실데이터(80 유동주, IS 2021-01~2024-05 / OOS 2024-06~2026-06) 검증.
--
-- 권위 = 이 row(strategy_research_candidate, V74 스키마). 코드(ResearchCandidateProvider)는 read만.
-- ON CONFLICT (candidate_key) DO NOTHING — V74:186·V75:45 선례. created_at/updated_at DEFAULT NOW() 위임.
--
-- ★ 게이트 통과 1종만 신규 승격(억지 승격 0 LOCK):
--   8가설 中 H1(GAP_AND_GO)만 OOS 양(+, PF 1.13) · 거래 333(≥100) · sensitivity plateau(True) · 양 레짐 분할 PF>1(1.19/1.11) 통과.
--   H2·H6 = donchian_upper inclusive-bar로 close>donchian 0매칭(DSL 표현불가, 검증 리포트 §2-2).
--   H3 PF 1.03(near-noise) · H4 PF 0.72(손실) · H5 6거래(표본부족) · H7 plateau False+105거래 · H8 레짐A PF 0.81(손실 — aggregate luck) → 전부 탈락.
--
-- ★ entry_dsl LOCK (spec §3-R2): 등록 식별자만 — open · prev_close · volume · volume_ma · close (directCandle/CATALOG_IDS 통과 검증).
--   trade_intensity(tick-only) · highest/lowest/returns/change(엔진 미등록) · donchian breakout(inclusive-bar 무력) 절대 미사용.

-- 1) 신규 승격 — DAYTRADE_GAP_AND_GO (H1, sort_order 230 = V75 200~220 뒤)
INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl, target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'DAYTRADE_GAP_AND_GO',
        '장전 강한 갭상승(공격형) setup',
        '전일 종가 대비 강한 갭상승(상위 5% 수준)으로 출발하며 거래량이 크게 터지고 거래대금이 충분한, 갭이 추세로 이어지는 공격형 단타 후보를 장전에 압축합니다. '
        || 'V75 갭 setup(갭 1.5%)보다 공격적인 갭 2.5% 기준입니다. '
        || '※ 과거(2021-01~2026-06, 유동주 80종) 검증에서 검증 구간(OOS) 우위가 관측되었을 뿐 미래 수익을 보장하지 않습니다. 자동매매 결정권 0 — 사용자 검증·스크리닝 후보 출발점입니다.',
        '["전일 종가 대비 2.5% 이상 갭상승(상위 5% 수준)","거래량이 최근 20봉 평균의 2.5배 이상(RVOL)","거래대금이 160억 원 이상(유동성)","주가 2,000원 이상(동전주 제외)"]'::jsonb,
        'open >= prev_close * 1.025 and volume > volume_ma(20) * 2.5 and close * volume > 16000000000 and close > 2000',
        2.0, 1.2, 8, 230, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;

-- 2) V75-2 VOLATILITY_EXPANSION 비활성화 — 현행·수정안 둘 다 양 레짐 손실(검증 리포트 §3 V75-2c/V75-2f)
--    현행 OOS PF 0.96(레짐 0.90/0.98), 하한 상향(atr>4%) 수정안도 PF 1.00 breakeven·non-plateau·레짐A 손실.
--    연구 문서의 "ATR 하한 상향 수정" 가설이 데이터로 미입증 → 손실 프리셋 노출 불가, is_active=FALSE로 비활성(폐기 보존).
--    builtin row는 삭제하지 않고 비활성만(역사 evidence + ON CONFLICT 재배포 안정성).
UPDATE strategy_research_candidate
SET is_active = FALSE,
    market_fit = '[비활성 — 2026-06-10 R3 검증] ATR 변동성 밴드(1.5~6%)가 한국 단타 universe의 72% 이상을 통과시켜 필터 기능을 하지 못하며, '
        || '하한을 4%로 올린 수정안도 검증 구간 손익분기(PF 1.00)에 그치고 레짐 분할에서 손실이 관측되어 비활성화했습니다. '
        || '변동성은 종목 진입 edge가 아닌 손절폭 설계 변수로 재검토가 필요합니다(검증 리포트 §3·§5-3).',
    updated_at = NOW()
WHERE candidate_key = 'DAYTRADE_VOLATILITY_EXPANSION';
