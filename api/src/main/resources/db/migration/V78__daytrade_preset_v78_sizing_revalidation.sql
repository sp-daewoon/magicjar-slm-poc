-- V78: 단타 발굴 프리셋 — V78 최종 재검증 (사이징 정합 FixedFractional + 신 G3 MDD≤12%) 집행 (ADR 0121 LOCK)
--
-- 출처: _workspace/strategy/2026-06-10-v78-final-revalidation-report.md (task T-20260610-1430).
-- 배경: V77(#865)이 AllCapital(1포지션=자본 100%) 몰빵 누적 곡선 MDD를 분산 게이트(G3≤8%)로 재는
--   측정-게이트 불일치로 6종 전부 G3 탈락 → H1 비활성 판정을 최고 관리자가 집행 보류.
--   ADR 0121이 (a) 백테스트 사이징을 실전 RiskConfig 정합(FixedFractional 10%/5슬롯)으로 환산,
--   (b) G3를 "실전 사이징 기준 포트폴리오 MDD ≤ 12%"로 재정의. #871+#873(scale-in 슬롯 환산 연결)
--   배포 후 V78에서 6종 재판정.
--
-- ★ 사전 점검(사이징 분리 확인): 동일 H1 요청을 AllCapital vs FixedFractional로 1회씩 실행 →
--   OOS MDD 0.3277 vs 0.0789 (비트 비동일). PF 1.32→1.37(슬롯 크기 무관 근사 불변).
--   #873 hotfix 작동 검증 — 직전 결함(scale-in 경로 누락 → 비트 동일) 재발 0.
--
-- ★ V78 실측 (universe 80 유동주 거래대금 상위 보통주 · IS 2021-01~2024-05 / OOS 2024-06~2026-06 ·
--   레짐 A 2024-06~2025-06 / B 2025-06~2026-06 · FixedFractional positionPercent=10·maxConcurrent=5 LOCK):
--   신 G3 = OOS Fractional MDD ≤ 12%. 하드 게이트 G1·G2·G3(신)·G4·G5 + 소프트 G6·G8.
--
--   | 프리셋 | OOS ret | 거래 | PF | Frac MDD | (V77 AllCap MDD) | 레짐A PF | 레짐B PF | plateau | 판정 |
--   |--------|--------:|----:|----:|--------:|-----------------:|--------:|--------:|:-------:|------|
--   | H1 GAP_AND_GO       | 0.320 | 370 | 1.373 | 0.0789 | 0.3277 | 1.313 | 1.473 | True | ✅ 유지 |
--   | H2 VOLUME_BREAKOUT  | 0.701 | 789 | 1.430 | 0.0766 | 0.2972 | 1.210 | 1.643 | True | ✅ 신규 승격 |
--   | H6 RANGE_BREAKOUT   | 0.666 | 780 | 1.410 | 0.0858 | 0.3538 | 1.204 | 1.631 | True | ✅ 신규 승격 |
--   | V75-1 GAP_RVOL      | 0.414 | 517 | 1.347 | 0.0701 | 0.2460 | 1.186 | 1.498 | True | ✅ 유지 |
--   | V75-2 VOLATILITY    | 0.150 |1135 | 1.082 | 0.1230 | 0.5825 | 1.003 | 1.206 |  —   | ❌ 비활성 유지(G3 미달) |
--   | V75-3 MOMENTUM      | 0.764 |1522 | 1.244 | 0.0711 | 0.3078 | 1.079 | 1.428 | True | ✅ 유지 |
--
--   → 사이징 정합 후 6종 中 5종이 신 G3(≤12%) 통과. V75-2만 Frac MDD 12.30%(>12%)로 탈락(+ 레짐A PF 1.003 near-noise).
--   임계 추정(ADR §임계 9~14%) 실측 정합(H1 7.9% · 환산계수 ≈ 0.24) — X=12% 확정, ADR amend 트리거 없음.
--
-- 권위 = 이 row(strategy_research_candidate, V74 스키마). 코드(ResearchCandidateProvider)는 read만.
-- ON CONFLICT (candidate_key) DO NOTHING — V74·V75·V76 선례. created_at/updated_at DEFAULT NOW() 위임.
-- ★ entry_dsl LOCK: 등록 식별자만(close·open·prev_close·volume·volume_ma·donchian_upper·adx — CATALOG_IDS/directCandle 통과 검증).
--   donchian_upper(n)은 #860 fix(현재 봉 제외)로 close>donchian_upper 돌파 정상 매칭(V78 H2 789·H6 780 거래 실측).

-- 1) 신규 승격 — DAYTRADE_VOLUME_BREAKOUT (H2, sort_order 240 = V76 H1 230 뒤)
INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl, target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'DAYTRADE_VOLUME_BREAKOUT',
        '거래량 폭발 20봉 신고가 돌파 setup',
        '최근 20봉 채널 상단(신고가)을 거래량 폭발과 함께 돌파하며 거래대금이 충분한, 박스권 상단 돌파 모멘텀 단타 후보를 압축합니다. '
        || '※ ADR 0121 사이징 정합(자본의 10%씩 최대 5종목 분산 가정) 백테스트에서 검증 구간(OOS) 포트폴리오 MDD 7.7%·PF 1.43·양 레짐 PF>1(1.21/1.64)·민감도 plateau로 신 G3(MDD≤12%) 포함 전 하드 게이트를 통과했습니다(2021-01~2026-06, 유동주 80종). '
        || '과거 우위가 관측됐을 뿐 미래 수익을 보장하지 않습니다. 자동매매 결정권 0 — 사용자 검증·스크리닝 후보 출발점입니다.',
        '["최근 20봉 신고가(채널 상단) 돌파","거래량이 최근 20봉 평균의 2배 이상(RVOL)","거래대금이 100억 원 이상(유동성)","주가 2,000원 이상(동전주 제외)"]'::jsonb,
        'close > donchian_upper(20) and volume > volume_ma(20) * 2.0 and close * volume > 10000000000 and close > 2000',
        2.0, 1.2, 8, 240, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;

-- 2) 신규 승격 — DAYTRADE_RANGE_BREAKOUT (H6, sort_order 250 — H2에 ADX 추세 게이트 추가형)
INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl, target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'DAYTRADE_RANGE_BREAKOUT',
        '박스권 상단 돌파 + 추세(ADX) 게이트 setup',
        '20봉 신고가 돌파를 추세 강도(ADX>20) 게이트로 거른, 횡보장 가짜 돌파를 배제한 박스권 돌파 단타 후보입니다(H2 거래량 돌파 + ADX 추세 확인형). '
        || '※ ADR 0121 사이징 정합(자본의 10%씩 최대 5종목 분산 가정) 백테스트에서 검증 구간(OOS) 포트폴리오 MDD 8.6%·PF 1.41·양 레짐 PF>1(1.20/1.63)·민감도 plateau로 신 G3(MDD≤12%) 포함 전 하드 게이트를 통과했습니다(2021-01~2026-06, 유동주 80종). '
        || '과거 우위가 관측됐을 뿐 미래 수익을 보장하지 않습니다. 자동매매 결정권 0 — 사용자 검증·스크리닝 후보 출발점입니다.',
        '["최근 20봉 신고가(채널 상단) 돌파","추세 강도 ADX(14)가 20 초과(횡보 배제)","거래량이 최근 20봉 평균의 2배 이상(RVOL)","거래대금이 80억 원 이상(유동성)","주가 2,000원 이상(동전주 제외)"]'::jsonb,
        'close > donchian_upper(20) and adx(14) > 20 and volume > volume_ma(20) * 2.0 and close * volume > 8000000000 and close > 2000',
        2.0, 1.2, 8, 250, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;

-- 3) H1 DAYTRADE_GAP_AND_GO — active 유지 확정 (V77 보류 비활성 판정 집행 안 함, ADR §3-3 promote rule).
--    사이징 정합 후 신 G3(Frac MDD 7.9% ≤ 12%) 통과 — V77 AllCapital 35% 몰빵 MDD가 측정-게이트 불일치였음이 실증됨.
--    market_fit에 V78 사이징 정합 근거 갱신(builtin row 비활성/삭제 없음, UPDATE만).
UPDATE strategy_research_candidate
SET market_fit = '전일 종가 대비 강한 갭상승(상위 5% 수준)으로 출발하며 거래량이 크게 터지고 거래대금이 충분한, 갭이 추세로 이어지는 공격형 단타 후보입니다(갭 2.5% 기준). '
        || '※ [V78 사이징 정합] ADR 0121 분산 가정(자본 10%씩 최대 5종목) 백테스트에서 검증 구간(OOS) 포트폴리오 MDD 7.9%·PF 1.37·양 레짐 PF>1(1.31/1.47)·민감도 plateau로 신 G3(MDD≤12%) 포함 하드 게이트 통과(2021-01~2026-06, 유동주 80종). V77 몰빵 MDD 35%는 측정 불일치였음이 실증됐습니다. '
        || '과거 우위가 관측됐을 뿐 미래 수익을 보장하지 않습니다. 자동매매 결정권 0 — 사용자 검증·스크리닝 후보입니다.',
    updated_at = NOW()
WHERE candidate_key = 'DAYTRADE_GAP_AND_GO';

-- 4) V75-2 DAYTRADE_VOLATILITY_EXPANSION — 비활성 유지 (변경 0, 기록만).
--    사이징 정합 후에도 Frac MDD 12.30%(>12% 신 G3 미달) + 레짐A PF 1.003(near-noise) → 재활성 안 함.
--    ADR LOCK "V75-2는 통과 시에만 재활성 검토" — 미통과이므로 is_active=FALSE(V76 비활성) 유지. UPDATE 불필요.
--    (V75-1·V75-3은 본 V78에서 신 G3 통과로 기존 active 유지 — 변경 0.)
