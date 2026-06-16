# 자동매매 RiskPlan — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-0106 (도입), ADR-0067 (Risk Gateway), ADR-0068 (Order Execution), ADR-0043/0098 (KillSwitch)
> **마지막 갱신**: 2026-06-02

## 현행 규약

자동매매 BUY 주문은 손절과 익절 계획이 모두 확정된 뒤에만 주문 경로로 진입한다.

## 제어 계층

| 계층 | 범위 | BUY | SELL·청산 | strategy 상태 변경 | 해제 |
|------|------|-----|-----------|--------------------|------|
| KillSwitch | `user_id` 전체. AUTO·MANUAL 주문 경로 모두 | 차단 | 차단 | 변경하지 않음 | 사용자 수동 OFF만 |
| `BUY_BLOCKED` | 해당 `strategy_id` 하나 | 차단 | 유지 | 해당 전략만 `BUY_BLOCKED` | 사용자가 해당 전략 재개 |
| RiskPlan/RiskGateway 일반 룰 | 시그널·주문 의도 단위 | 룰별 차단 | 룰별 차단 | strategy-local 반복 차단만 AutoPause 후보 | 룰 원인 해소 |

KillSwitch는 `BUY_BLOCKED`보다 상위 제어다. KillSwitch ON 또는 KillSwitch 상태 확인 불가 시 RiskGateway와 OrderRouter가 모두 fail-closed로 주문 의도·KIS 전송을 차단한다. 이때 기존 전략의 `ACTIVE`/`BUY_BLOCKED`/`PAUSED` 상태는 바꾸지 않으며, KillSwitch로 발생한 `KILL_SWITCH_ON` 차단 이벤트는 전략별 AutoPause 누적에 포함하지 않는다.

KillSwitch가 OFF 되어도 기존에 `BUY_BLOCKED`였던 전략은 자동으로 `ACTIVE`가 되지 않는다. 상위 비상정지를 푸는 것과 특정 전략의 신규 매수를 재개하는 것은 별도 사용자 결정이다.

| 항목 | 정책 |
|------|------|
| 손절 | 전략은 `fixed_stop_loss_pct`를 가져야 한다. 차트 범위 손절이 없는 1차 구현에서는 매수가 기준 고정 손절 %를 hard stop으로 사용한다. |
| 익절 | 전략은 `take_profit_pct`를 가져야 한다. 1차 구현에서는 보유 포지션 평가 수익률 기준 익절 %로 사용한다. |
| 손익비 | `fixed_stop_loss_pct`와 `take_profit_pct`는 모두 0보다 커야 한다. 단타형/스캘핑 전략은 `take_profit_pct <= fixed_stop_loss_pct`도 허용하며, UI는 손익비를 표시하되 저장/자동매매 진입을 차단하지 않는다. |
| 자동매매 시작 | 대상 종목, 인터벌, RiskPlan, 성공 백테스트가 모두 준비되어야 ACTIVE 전환 가능하다. |
| 실행 중 보호 | ACTIVE 전략이라도 BUY 시점에 RiskPlan이 없거나 비정상이면 Risk Gateway 이전 preflight에서 fail-closed로 차단한다. |
| 리스크 누적 자동정지 | 연속 리스크 차단은 `strategy_id` 단위로 계산한다. 임계치에 도달한 해당 전략만 `BUY_BLOCKED`로 전환하고, 같은 사용자의 다른 전략·워커는 계속 각자의 상태대로 동작한다. `BUY_BLOCKED` 전략은 신규 BUY만 금지하며 기존 포지션의 SELL·익절·손절 평가는 계속 유지한다. |
| KillSwitch 상위 정지 | KillSwitch는 사용자 전체 하드 스톱이다. AUTO·MANUAL 신규 주문 의도와 BUY·SELL을 모두 차단하되, 체결통보 수신·position 갱신·reconcile·audit·STOMP 통지는 계속 유지한다. |
| 매도 수량 | SELL 주문 수량은 내부 보유 수량에서 미체결 매도 주문 수량을 뺀 가용 수량만 사용한다. |
| KIS 매도 잔고 | AUTO SELL은 주문 의도 발행 전 KIS 잔고에 해당 수량이 있는지 확인한다. KIS 잔고 조회 실패나 수량 부족은 주문 요청 대신 Risk 차단 알림으로 남긴다. |
| KIS 조회 실패 차단 | KIS 잔고 조회 실패는 1초 대기 후 1회 재시도한다. 재시도 후에도 실패하면 주문은 보내지 않되, 실제 보유 수량 부족으로 확정하지 않고 조회 실패 보류로 알린다. 이 인프라성 차단은 strategy-local AutoPause 누적에서 제외한다. |

## 적용 위치 (코드·설정)

- `strategy.fixed_stop_loss_pct` — 자동매매 기본 hard stop 손절 %
- `strategy.take_profit_pct` — 보유 포지션 평가 수익률 기준 익절 %
- `api/.../StrategyService.kt` — 전략 저장 검증 및 ACTIVE 전환 차단 사유
- `consumer/.../RealtimeSignalGenerator.kt` — 평가 가능 전략을 `strategy_id`별로 순회하며, `BUY_BLOCKED`인 해당 전략의 신규 BUY 신호만 버리고 SELL·청산 신호는 유지
- `consumer/.../SignalGeneratedKafkaListener.kt` — BUY RiskPlan preflight fail-closed, AUTO SELL KIS 잔고 preflight
- `consumer/.../SignalGeneratedKafkaListener.kt` — KillSwitch 상태 확인 불가 시 fail-closed, `KILL_SWITCH_ON` 차단은 strategy AutoPause 누적에서 제외
- `consumer/.../RiskGatewayEvaluator.kt` — SELL 가용 수량 산정
- `consumer/.../OrderRouterKafkaListener.kt` — RiskGateway 통과 후에도 KIS 전송 직전 KillSwitch 재검증
- `api/.../KillSwitchQueryAdapter.kt`, `consumer/.../ConsumerKillSwitchQueryAdapter.kt` — 명시적 OFF row가 없거나 조회 실패 시 fail-closed ON
- `api/.../RuntimeFlagsController.kt` — userId 지정 런타임 플래그 조회에서 KillSwitch row 없음·조회 실패를 `killSwitchActive=true`로 표시
- `frontend/.../StrategySaveModal.tsx` — 전략 저장 시 관리자 RiskPlan 입력 UX
- `_workspace/analysis/2026-06-01-autotrading-risk-plan.html` — 시각 설계도

## 변경 이력

- 2026-06-01: 자동매매 BUY 전 손절/익절 RiskPlan 필수화, SELL 가용 수량 게이트, AUTO SELL KIS 잔고 preflight 도입 (ADR-0106).
- 2026-06-01: 단타형/스캘핑 운용을 위해 익절 기준이 손절 기준 이하인 RiskPlan도 허용. 손절/익절 양수 필수와 실행 중 RiskPlan fail-closed는 유지.
- 2026-06-02: 리스크 차단 누적 자동정지를 전체 DISABLED 대신 `strategy_id` 단위 `BUY_BLOCKED`로 분리. 해당 전략의 신규 매수만 금지하고 기존 포지션 청산 평가는 유지한다.
- 2026-06-02: KillSwitch와 `BUY_BLOCKED` 계층 분리. KillSwitch는 사용자 전체 하드 스톱, `BUY_BLOCKED`는 전략별 신규 BUY 차단으로 정의하고 `KILL_SWITCH_ON` 차단은 strategy AutoPause 누적에서 제외.
- 2026-06-02: KIS 잔고 조회 rate-limit/조회 실패는 1회 재시도 후에도 실패할 때 주문 미전송 fail-closed로 보류하되, 실제 보유 부족과 다른 알림 문구를 사용하고 strategy AutoPause 누적에서 제외한다.
