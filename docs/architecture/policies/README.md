# Living Policy Docs — 현행 권위 정책

> **본 폴더 = "지금 무엇이 권위인가"의 단일 출처**.
> ADR은 결정 이력. 정책 본문은 항상 본 폴더 문서가 권위.
> ref ADR 0053 (도입 결정).

---

## charter vs policies — 책임 경계

magicJar는 **메타 룰**을 두 층위로 분리한다:

| 폴더 | 책임 | 갱신 주체 | 양식 |
|------|------|----------|------|
| `_workspace/arch/agent-charter.md` | **에이전트 행동 강령** (어떻게 일할까) — task.mjs 4단계·머지 후 정리·CHANGELOG 슬롯·rebase·Repository default fun·fail 분기 등 8 actor 의무 정독 룰 | architect 단독 PR | 7 § 헌법 본문 |
| `docs/architecture/policies/` (본 폴더) | **시스템·도메인 사실 권위** (무엇이 맞는가) — KIS credential 식별·로그 레벨·KIS-first·task cycle·mermaid 등 코드·데이터·외부 통합 정책 | architect 또는 영역 owner | 표준 4섹션 (header·현행규약·적용위치·변경이력) |

**중복 처리 룰**: 두 폴더에 같은 주제가 등장하면 **policies가 단일 본문 권위**, charter는 ref 슬롯만 보유 (예: charter §3 KIS-first → `kis-first-raw-fetch.md` 단일 출처).

**[branching-rules.md](../../../_workspace/arch/branching-rules.md)** 는 **실전 운영 가이드**(다이어그램·표 중심) 성격으로 policies·charter와 별개 — 브랜치 흐름·네이밍·모델 차등·git author 화이트리스트를 표·다이어그램으로 보유.

---

## 정책별 권위 위치

| 영역 | 정책 | 위치 | 변경 빈도 |
|------|------|------|----------|
| **도메인** | 롱 온리(공매도 없음) 매매 원칙 | [long-only-trading.md](long-only-trading.md) | 낮음 |
| **외부 통합** | KIS Credential 식별 규약 | [kis-credential-identity.md](kis-credential-identity.md) | 낮음 |
| **외부 통합** | KIS-first raw fetch 의무 | [kis-first-raw-fetch.md](kis-first-raw-fetch.md) | 낮음 |
| **운영** | 모듈별 로그 레벨 | [logging-levels.md](logging-levels.md) | 중간 |
| **운영** | Task 사이클 (start·check·done·archive) | [task-cycle.md](task-cycle.md) | 낮음 |
| **운영** | Mermaid 작성 규약 (mermaid 10 호환) | [mermaid-conventions.md](mermaid-conventions.md) | 낮음 |
| **자동매매** | BUY 전 손절/익절 RiskPlan 필수화 | [autotrading-risk-plan.md](autotrading-risk-plan.md) | 중간 |
| **자동매매 QA** | Scenario Replay Sandbox 분리 규약 | [scenario-replay-sandbox.md](scenario-replay-sandbox.md) | 높음 |

이후 추가 영역:

| 영역 (미작성) | 후보 위치 |
|---|---|
| 자격증명·시크릿 처리 | `secrets-handling.md` (W3 진입 시) |
| 주문 idempotency | `order-idempotency.md` (W3 T-O 진입 시) |
| 시그널 sandbox | `signal-sandbox.md` (W3 T-SIG 진입 시) |
| AI 호출 게이트 | `ai-trigger-gate.md` (W4 진입 시) |

---

## 작성 규약

각 policy 문서 표준 구조:

```markdown
# <정책명> — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-XXXX (도입), backlog §N (의제 시), PR #N (적용)
> **마지막 갱신**: YYYY-MM-DD (PR #N)

## 현행 규약
(현재 옳은 답을 단답·표·코드 예시로)

## 적용 위치 (코드·설정)
- module/path/File.kt:42 — <역할>
- application.yml — <키>

## 변경 이력 (footer, 한 줄씩)
- 2026-05-08: 도입 (ADR-0053, PR #N)
- 2026-XX-XX: <변경 사유> (ADR-XXXX, PR #N)
```

## 변경 시 의무

1. **본 문서 본문 update** (현행 권위)
2. **ADR 후속 발행** (이력)
3. **`# 변경 이력` footer에 한 줄 추가** (cross-ref)
4. **backlog § close** (있을 시)

본 인덱스(`README.md`)도 새 정책 추가/제거 시 갱신.
