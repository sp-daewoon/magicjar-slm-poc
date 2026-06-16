# magicJar 평가(Assessment) 인덱스

> **권위**: ADR 0108 — 주기 평가 문서의 단일 위치·네이밍·템플릿·인덱스 규약.
> 신규 평가 발행 시 본 표에 row **prepend** 의무. 같은 slug 반복 발행 = 시계열 (직전 평가 대비 스코어 diff를 본문 §9에 기록).

## 네이밍

`YYYY-MM-DD-<assessment-slug>.html` — 템플릿 9 섹션은 ADR 0108 본문 참조.

## slug 레지스트리

| slug | 평가 대상 |
|------|-----------|
| `autotrading-service-code-audit` | 자동매매 서비스 레벨·코드 레벨·ADR 정합 종합 감사 |
| `autotrading-readiness` | 자동매매 준비도 (종합 / MOCK 구현도 / REAL 안전 운영) |
| `db-usage-contention-audit` | DB 사용 패턴·row 분포·경합부 전수 감사 (실매매 스케일 선제 점검) |
| `candle-storage-capacity` | 캔들 데이터 저장 용량 점검 (interval 분포·용량 추정·보존 정책 권고) |
| `candle-operating-policy-options` | 캔들 유니버스·보존·AI 후보 readiness 운영 정책 케이스 |
| `candle-readiness-change-summary` | 캔들 readiness 워크플로 변경 요약 (AI 후보 카드·캔들 조회·DB 인덱스) |
| `order-execution-vigilance` | 주문 집행 정책(룰 비교·기본값) + 포지션 긴장 상태 8축 코드 검증 (autotrading-order-position-watch의 심층판) |

## 발행 이력

| 날짜 | slug | 종합 판정 | 핵심 스코어 | 파일 |
|------|------|-----------|-------------|------|
| 2026-06-13 | autotrading-service-code-audit | REAL canary 전 P1 2건 필수 (SELL 청산 리스크 룰 충돌 · Actuator/운영 노출) | 종합 74 · MOCK 84 · REAL 68 · ADR 78 | [2026-06-13-autotrading-service-code-audit.html](2026-06-13-autotrading-service-code-audit.html) |
| 2026-06-07 | order-execution-vigilance | Critical 2건 (readiness가 손절도 skip · DISABLED 전략 포지션 방치) — 실매매 전 필수 조치 | 감시구조 88 · 항상보장 52 · 가격보호 15 · 체결정합 85 | [2026-06-07-order-execution-policy-and-position-vigilance.html](2026-06-07-order-execution-policy-and-position-vigilance.html) |
| 2026-06-07 | autotrading-order-position-watch | 진입 가격 보호 + 포지션 tick 긴급 감시 필요 | 정책 보고 (BUY 지정가·손절 시장가·POSITION owner 권장) | [2026-06-07-autotrading-order-position-watch.html](2026-06-07-autotrading-order-position-watch.html) |
| 2026-06-06 | candle-readiness-change-summary | 캔들 readiness 강화 변경 요약 (후보 카드·조회 방식·인덱스) | 점수형 아님 (변경 요약 보고) | [2026-06-06-candle-readiness-change-summary.html](2026-06-06-candle-readiness-change-summary.html) |
| 2026-06-06 | candle-operating-policy-options | 전체 일봉 + 관리 유니버스 intraday 권장 | 점수형 아님 (정책 옵션 보고) | [2026-06-06-candle-operating-policy-options.md](2026-06-06-candle-operating-policy-options.md) |
| 2026-06-06 | candle-storage-capacity | 분봉 보존 정책·정리 권고 — 자동매매 전 선행 권장 | 점수형 아님 (용량 점검 보고) | [2026-06-06-candle-storage-capacity.html](2026-06-06-candle-storage-capacity.html) |
| 2026-06-06 | db-usage-contention-audit | candle 쓰기 증폭이 1순위 경합점 · 자본 경로는 견고 | 자본경로 92 · candle효율 41 · retention 35 · 스케일준비 58 | [2026-06-06-db-usage-contention-audit.html](2026-06-06-db-usage-contention-audit.html) |
| 2026-06-05 | autotrading-readiness | MOCK 안정 운영 가능 · REAL canary 게이트 대기 | 종합 87(+16) · MOCK 95(+13) · REAL 78(+14) | [2026-06-05-autotrading-readiness.html](2026-06-05-autotrading-readiness.html) |
| 2026-06-04 | autotrading-readiness | 조건부 정상 | 종합 71 · MOCK 82 · REAL 64 | [2026-06-04-autotrading-readiness.html](2026-06-04-autotrading-readiness.html) |
