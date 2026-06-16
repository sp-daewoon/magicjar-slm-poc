# REAL canary 준비 체크리스트 (진행 추적)

> **사용법**: 최고 관리자가 항목을 준비하고 알려주시면, orchestrator가 **실측 검증 후** `[x]` + 검증 evidence를 기록합니다. 전 항목 ✅ + 실행일 결정 = canary 진입 가능.
> 가이드: [`real-canary-precheck.html`](real-canary-precheck.html) · 절차 권위: [`real-canary-runbook.md`](real-canary-runbook.md)
> 상태: **실행일 확정 — 2026-06-11 (목)** · 선세팅 완료 (잔여: U-4 확인·U-7 동의 토글 + 당일 게이트)
> **결재 amend (2026-06-11 최고 관리자)**: notional cap 100,000 → **350,000** + 종목예산 100,000 → **350,000** (대상 전략 49 xx1 종목 042700이 주당 ~27만원이라 1주 주문이 구 cap에 차단됨 — QTY=1·PER_DAY=5·DLL 50,000은 유지)

## A. 사용자 준비 항목 (precheck §4와 1:1)

| 체크 | # | 항목 | 검증 방법 | 검증 evidence (orchestrator 기록) |
|------|---|------|-----------|-----------------------------------|
| [x] | U-1 | canary용 전략 1개 생성 + 백테스트 통과 (분할 OFF·손절/익절·Sizing 최소) | strategy row + 백테스트 결과 | ✅ 2026-06-11 — 전략 49 `xx1` (042700·15m) 단독 ACTIVE. 손절 2.0%·익절 1.8%·scale_in_config NULL 실측. 전략 8·50 PAUSED 처리. 페이퍼 전략 82는 GAP-0 MOCK 강제로 유지(사용자 결정) |
| [x] | U-2 | MOCK 관찰 기간 — 시그널↔주문 정합 확인 (며칠) | 관찰 리포트 (orchestrator 발행) | ✅ 2026-06-08~06-10 MOCK 운영 — WS 체결통보 라이브 실증(PR #878)·포지션 정합 자동보정 가동·시그널→주문→Slack e2e 다수. 당일 09:00 dwell로 최종 확인 |
| [x] | U-3 | KIS 실전 주문용(vps) 키 등록 (⚙️ SettingsModal) | `user_kis_credential` REAL row + 잔고 200 | ✅ 2026-06-11 — REAL row 1건 실측. 잔고 200 검증은 당일 아침 G게이트에서 |
| [ ] | U-4 | KIS IP 화이트리스트 등록 | 실키 조회 1회 성공 | 사용자 확인 대기 (시세 수집 실전 키는 작동 중 — 주문 키 동일 IP 여부 확인) |
| [x] | U-5 | `.env` hard cap 3종 입력 (QTY=1 · **NOTIONAL=350000(amend)** · PER_DAY=5) | printenv 3종 | ✅ 2026-06-11 .env 기입 (컨테이너 반영은 당일 REAL flip 재배포 시 — printenv 재확인 의무) |
| [x] | U-6 | RiskConfig 4종 (손실한도 50,000 · 전략당 5건 · 동시 1종목 · **종목예산 350,000(amend)**) | risk_config row | ✅ 2026-06-11 PUT /api/risk-config 적용 실측 — 350000/DLL 50000 active/동시 1/일 5 |
| [ ] | U-7 | 실거래 동의 토글 | consent API `consented=true` | 실측 `consented=false` — 사용자 ⚙️ 토글 대기 (사용자 단독 권한) |
| [x] | U-8 | 실행일 결정 + 당일 아침 통보 | — | ✅ 2026-06-11 (목) 확정 — 사용자 직접 지시 |

## B. 시스템 준비 항목 (orchestrator 책임)

| 체크 | # | 항목 | 상태 |
|------|---|------|------|
| [x] | S-1 | canary hard cap 게이트 코드 (ADR 0109) | ✅ PR #740 배포 (2026-06-05) |
| [x] | S-2 | 주문 path rate limit 우선권 (EGW00201 재발 방지) | ✅ PR #749 배포 — USER_PATH reserve 3토큰 |
| [x] | S-3 | WS 유령 재연결 루프 fix | ✅ PR #748 배포 + 19:00 라이브 실증 |
| [x] | S-4 | 외부 HTS 주문 흡수 매핑 (canary cap 오염 차단) | ✅ PR #752 + 보정 SQL 실증 |
| [x] | S-5 | 장운영시간 게이트 | ✅ PR #717 (default ON) |
| [x] | S-6 | runbook + 사전점검서 발행 | ✅ #739 + 본 kit |
| [ ] | S-7 | F-1-OBS 패치 (PRICE_DROP 다회차) — **분할 canary(2회차) 전 필수, 1회차는 불요** | backlog |
| [ ] | S-8 | canary 전일 저녁 배포 완료 상태 동결 (당일 아침 재배포 금지 — WS 공백 회피) | 실행일 결정 후 |

## C. 실행 당일 게이트 (당일 아침 orchestrator 실측 — runbook §1)

| 체크 | # | 항목 |
|------|---|------|
| [ ] | G-1 | `MAGICJAR_MODE=REAL` |
| [ ] | G-2 | `MAGICJAR_ALLOW_REAL=true` |
| [ ] | G-3 | 실거래 동의 consented=true |
| [ ] | P-1 | KillSwitch OFF |
| [ ] | P-3 | hard cap ENV 3종 printenv 일치 |
| [ ] | S(범위) | ACTIVE 전략 정확히 1개 · 종목 1개 |
| [ ] | O | 백업 최신 · Slack 도달 · 4모듈 ERROR 0 · staging 30분 dwell |

## 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-06-11 | 실행일 확정(6/11 목) + 선세팅 evidence 기록 (U-1·2·3·5·6·8 ✅) + **notional/종목예산 cap 350,000 amend (최고 관리자 결재 — 042700 주가 ~27만원 정합)** + 대상 전략 49 LOCK·전략 8/50 PAUSE·페이퍼 82 유지(GAP-0) |
| 2026-06-05 | 최초 발행 (orchestrator) — MOCK 관찰 단계 진입, B 6/8 완료 |
