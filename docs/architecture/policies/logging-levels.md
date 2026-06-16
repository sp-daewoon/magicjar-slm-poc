# 모듈별 로그 레벨 — 현행 권위

> **권위**: 본 문서 + `application.yml` `logging.level` section.
> **결정 이력**: PR #245 (3 모듈 일괄 추가), PR #244 (nginx access_log off)
> **마지막 갱신**: 2026-05-08 (PR #245)

---

## 현행 로그 레벨 매트릭스

api·batch·consumer 3 모듈 `application.yml`에 동일하게 적용:

```yaml
logging:
  level:
    root: INFO
    org.hibernate.SQL: WARN
    org.hibernate.orm.jdbc.bind: WARN
    org.apache.kafka: WARN
    org.apache.kafka.clients.NetworkClient: ERROR  # broker 일시 끊김 INFO 노이즈 차단
    org.springframework.kafka: WARN
    kr.spdaewoon.magicjar: INFO
```

frontend nginx (`frontend/nginx.conf`):

```nginx
location = /ws { ...; access_log off; }
location /ws/ { ...; access_log off; }
```

→ STOMP healthcheck access_log 차단.

---

## 의도된 로그 출력 (남기는 것)

| 레벨 | 무엇을 |
|------|--------|
| `ERROR` | 진짜 결함 (인증 실패·예측 못 한 예외·CrashLoop trigger) |
| `WARN` | 사용자 조치 필요 (자격증명 미등록·외부 서비스 일시 fail) |
| `INFO` | 의미 있는 lifecycle (부팅·종료·sync 시작/완료·KIS approval_key 발급) |
| `DEBUG` | 정상 흐름 단위 (메서드 호출·sentinel 처리·command publish) |

## 안티패턴 (제거된 것)

| 위치 | 변경 |
|------|------|
| `KisInstrumentMasterAdapter.sanitizeRoe` | WARN → DEBUG (PR #244) — 정상 case |
| `SymbolController.symbol.detail.command 발행` | INFO → DEBUG (PR #245) — 정상 호출 case |
| `KisWebSocketBridge.getApprovalKey "자격증명 없음"` | ERROR 트레이스 → WARN 1줄 (PR #245) |
| Hibernate SQL DEBUG dump | WARN (PR #245) |
| Kafka NetworkClient INFO (broker 끊김) | ERROR로 강등 (PR #245) — 일시 끊김 노이즈 |

---

## 변경 시 의무

새 결함·노이즈 발견 시:

1. 정상 case → DEBUG 강등
2. 사용자 조치 필요 → WARN (1줄, 안내 포함)
3. 진짜 결함만 ERROR (스택 트레이스 가치 있을 때만)
4. application.yml logging.level 추가 시 3 모듈 동일 정합

본 문서 갱신 + (필요 시) 영향이 큰 변경은 ADR 후속 발행.

## 알림 승격 정책 (2026-06-01 보강)

로그 레벨 자체가 외부 메신저의 단일 권위가 아니다. 코드가 의미 있는 운영 사건으로 판단할 수 있으면 `SystemEvent`를 발행하고, consumer가 `system.event.v1`을 받아 Slack outbox로 라우팅한다.

| 분류 | 메신저 정책 | 예시 |
|------|------------|------|
| `CRITICAL/P0` | 즉시 Slack | KillSwitch, 주문 차단, 리스크 위반, DLT, 데이터 복구 실패, KIS WS 장시간 단절 |
| `ERROR` | 즉시 Slack | KIS upstream 실패, 주문 거절, gap fill 실패, 계좌/호가/현재가 stale |
| `WARN` | UI 알림 + Dozzle/Slack 묶음 보조 | KIS rate limit, 재연결 성공, 일부 캔들 지연, 일시적 API 실패 |
| `TRADE` | 즉시 Slack | REAL/MOCK/PAPER 매수체결, 매도체결, 부분체결, 주문거절 |

Dozzle v10 alert는 보조 안전망으로 사용한다. 앱 컨테이너의 `WARN`/`ERROR` 로그를 5분 이상 cooldown으로 묶어 Slack에 보낼 수 있지만, 주문·자본·시장 데이터 권위 알림은 `SystemEvent`/Kafka/outbox 경로를 우선한다.

---

## 적용 위치

| 모듈 | 위치 |
|------|------|
| api | `api/src/main/resources/application.yml` |
| batch | `batch/src/main/resources/application.yml` |
| consumer | `consumer/src/main/resources/application.yml` |
| frontend | `frontend/nginx.conf` |

---

## 변경 이력

- 2026-05-08: Living Policy로 분리 (ADR-0053, PR #248)
- 2026-05-08: 3 모듈 logging.level section 추가 (PR #245)
- 2026-05-08: nginx /ws access_log off (PR #244)
