# Scenario Replay Sandbox — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR 0107 (도입)
> **마지막 갱신**: 2026-06-03

## 현행 규약

Scenario Replay는 전략 QA 환경이다. 실제 투자와 같은 전략 DSL, candle, risk rule을 사용할 수 있지만 실제 주문 경로와 side effect는 사용할 수 없다.

### 절대 금지

| 영역 | 금지 대상 |
|---|---|
| Kafka | `order.intent.v1`, `order.requested.v1`, `order.filled.v1`, `order.rejected.v1`, `risk.violation.v1`, `signal.generated.v1` publish |
| 주문 코드 | `OrderRouterKafkaListener`, `KisOrderHttpAdapter`, `KisOrderPort`, `KisExecutionWsListener` 직접 호출/import |
| KIS 주문 | `TTTC0012U`, `TTTC0011U`, `VTTC0012U`, `VTTC0011U` 호출 또는 문자열 상수 보유 |
| live DB write | `order_request`, `order_execution`, `order_audit_event`, `risk_event`, `position`, `trading_journal`, `performance_summary` |
| 사용자 알림 | Slack TRADE/P0 주문 체결·거절 알림 publish |

### 허용

| 영역 | 허용 방식 |
|---|---|
| 전략 평가 | `strategy-engine` 순수 evaluator를 virtual candle slice로 호출 |
| 지표/패턴 | `indicator-impl`, `pattern-engine` 순수 계산 재사용 |
| 리스크 룰 | `RiskGatewayEvaluator` rule 공식을 사용하되 `ScenarioLedger` 기반 context만 주입 |
| candle | DB candle read-only 조회 |
| 비용 모델 | backtest tax/commission/slippage model 재사용 |
| 영속 | `scenario_*` 테이블만 |
| 이벤트 | `scenario_event` 또는 후속 `scenario.*` topic만 |

### 사용자 메시지

리플레이에서 발생한 주문/체결/거절은 항상 "가상" 또는 "리플레이"로 표시한다.

잔고 조회 실패, rate limit, data readiness 실패는 실제 매도 주문 실패가 아니다. 메시지는 다음처럼 원인을 분리한다.

| 원인 | 표시 원칙 |
|---|---|
| 잔고 조회 rate limit | "KIS 잔고 조회 rate limit으로 가상 주문 보류" |
| 잔고 조회 재시도 실패 | "잔고 조회 실패로 가상 주문 미발행" |
| 보유 수량 부족 | "리플레이 포지션 기준 매도 가능 수량 부족" |
| 장종료 | "리플레이 시장 세션 규칙상 가상 주문 거절" |

## 적용 위치 (코드·설정)

- `domain/.../scenario/` — 리플레이 도메인 모델과 pure engine
- `api/.../scenario/` — 리플레이 API, execution orchestration, report
- `api/.../persistence/scenario/` — `scenario_*` entity/repository만
- `api/src/main/resources/db/migration/V63__scenario_replay.sql` 이후 — `scenario_*` 테이블
- `frontend/src/pages/QA/ScenarioReplayPage.tsx` — `/qa/scenario-replay` 전용 QA 화면
- env enable 게이트 없음 — 격리는 구조적(`ScenarioReplayBoundaryTest.FORBIDDEN_TOKENS`가 live order/KIS/Kafka 참조 및 env enable 플래그를 금지). endpoint 상시 활성이나 `scenario_*` 외 자본·주문 경로 접촉 0으로 강제

## 변경 이력

- 2026-06-09: 미구현 `MAGICJAR_SCENARIO_REPLAY_ENABLED` enable-gate 표기 제거 — 실제 격리는 구조적(boundary test). 환경변수 게이트는 미채택.

- 2026-06-03: Phase 3 QA UI 구현 반영. 화면은 live 주문 메뉴가 아니라 QA 그룹 `/qa/scenario-replay`에 두고, 주문·체결·거절 이벤트는 `가상`·`리플레이`로 표기한다.
- 2026-06-03: Phase 2 API/DB 구현 반영. `api/scenario`와 `api/persistence/scenario`는 live order/KIS/Kafka/Slack adapter 참조 없이 `scenario_*`만 사용한다.
- 2026-06-03: ADR 0107에 따라 Scenario Replay Sandbox 현행 권위 정책 도입.
