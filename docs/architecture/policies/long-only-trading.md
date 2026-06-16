# 롱 온리(Long-Only) 매매 원칙 — 공매도 없음

> **상태**: 권위 (2026-06-12 최고 관리자 직접 결재)
> **결재 문구 인용**: "우리 시스템에는 숏(공매도)이 없어. 매도는 그냥 신호 나오면 팔면 끝이지 따로 계산할 게 없어." / "헌법 정책 시스템 메모리에도 우리는 공매도가 없다는 거 확실히 알려줘야지 — 전략 생성할 때나 매도 전략이나 매수 전략에 중요한 지표가 되니깐"

## 현행 규약

1. **magicJar는 롱 온리 시스템이다.** 공매도(숏 진입)·대주·파생 숏 포지션은 존재하지 않으며, 도입하지 않는다.
2. 따라서 **매도(SELL)는 항상 보유분 청산**이다 — 익절·손절·DSL exit·수동 매도 전부. 보유 수량이 자연 상한이므로 매도에는 금액·수량류 상한 검증이 필요 없다.
3. **방향별 비대칭 원칙 (BUY=가격 보호 / SELL=체결 보장)**:
   - **매수(진입)**: 자본이 나가는 방향 — cap(수량·금액·일일 횟수)·가격 보호(marketable limit, 시장가 fallback 금지 — ADR 0114 §3-1-5)를 엄격 적용
   - **매도(청산)**: 위험이 줄어드는 방향 — cap 검증 면제, 가격 산정(지정가 둘 곳) 실패 시 **시장가로 던진다** (체결 보장 우선)
4. **전략 생성·평가 시 전제**: 매도 시그널은 "보유가 있을 때만 의미"가 있다. 보유 0의 SELL 시그널 차단은 정상 가드이며 알람 대상이 아니다. 전략 DSL의 exit 조건·AI 전략 추천·빌더 검증 모두 숏 진입 의미로 해석하지 않는다.

## 적용 위치 (코드 권위)

| 지점 | 적용 |
|------|------|
| canary hard cap (ADR 0109 §7 amend) | MAX_ORDERS_PER_DAY = BUY만 카운트(왕복 1회=1) · QTY/NOTIONAL cap SELL 전면 면제 (PR #927·#928) |
| `OrderRouterKafkaListener.evaluateCanaryHardCap` | `side==SELL` early return |
| `SignalGeneratedKafkaListener.publishSell` | 매도 지정가 산정 실패 → MARKET fallback (INFO 가시화) |
| `RiskGatewayEvaluator` SELL 룰 | SELL_WITHOUT_POSITION = 정상 가드 (Slack mute — PR #926) |
| 전략 빌더·AI 전략 추천·발굴 프리셋 | 숏 전략 생성·추천 금지 전제 |

## 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-06-12 | 최초 발행 — REAL canary 2일차 incident(매도가 매수용 cap에 차단되는 자본 보호 역전) 계기, 최고 관리자 결재 명문화 (orchestrator) |
