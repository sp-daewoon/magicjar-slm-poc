# KIS-first Raw Fetch 의무 — 현행 권위

> **권위**: 본 문서 (charter §3은 ref 슬롯만 보유, 본문 단일 출처는 본 policy).
> **결정 이력**: 운영 헌법 §3 (`_workspace/arch/agent-charter.md`), ADR-0053 (Living Policy 도입), 사용자 메모리 "KIS-first 정책"
> **마지막 갱신**: 2026-05-12 (charter §3 본문→ref 축소 + §3 인용 오타 정정)

---

## 현행 규약

KIS API·외부 데이터 규격 결정 시 **자체 추론 0 정책**. 위반 시 fitness gap(PR-11 instrument 14 컬럼 vs KIS 73 필드 = 91.7% 갭 baseline) 발생.

### 발동 조건

- 새 KIS endpoint 도입 (REST/WS — tr_id·필드·byte offset)
- 새 KIS 데이터 규격 결정 (종목 마스터·시세·체결·호가·잔고·주문)
- DSL·tr_id·payload 구조 결정
- 외부 API 스키마 변경 인식 (Yahoo·DART·Naver)

### SoT 인용 우선순위

| 출처 | URL | 용도 |
|------|-----|------|
| **공식 SDK 레포** | https://github.com/koreainvestment/open-trading-api | tr_id·endpoint·payload 정합 |
| **MCP 서버** | `mcp__kis-code-assistant__*` | 코드 생성 시 raw search |
| **공식 문서** | https://apiportal.koreainvestment.com | 응답 스펙 보강 |

### 의무 절차 5단계

1. **raw fetch 직접 다운로드** — `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/<path>` 또는 KIS 공식 문서 URL.
2. **자체 추론 0 정책** — tr_id·endpoint·필드·byte offset·DSL 자체 결정 금지. 모든 결정은 raw 출처 인용 의무.
3. **검증 보고서 산출 권장** — 새 endpoint·새 데이터 규격 결정 시 `_workspace/specs/<pr>-kis-raw-verification.md` 권장 (예: PR-21 `pr-21-kis-header-raw-verification.md` baseline).
4. **sub-agent에 raw 출처 인용 의무 전파** — dispatch §에 "코드 inline 주석에 raw URL 명시" 의무. sub-agent 자체 추론 0 (architect spec 인용으로 입증).
5. **MCP 활용** — `mcp__kis-code-assistant__search_domestic_stock_api(query=...)` 결과 spec 또는 PR 본문에 인용 권장.

### 코드 inline 주석 양식

```kotlin
/**
 * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/.../inquire_ccnl.py
 * tr_id: FHKST01010100
 */
```

## 적용 영역

- `api/.../infrastructure/kis/` — REST 어댑터 (KisAuthValidator·KisRestPort 등)
- `consumer/.../infrastructure/kis/` — REST + WS 어댑터
- `batch/.../infrastructure/kis/` — 종목마스터·calendar·ksdinfo

## v3.1.0 시점 인용 인벤토리

`_workspace/baseline/v3.1.0/03-use-sequences.md` §7 부록 참조 — 17건 raw 인용.

---

## 위반 baseline (재발 방지 가시화)

PR-11 시점 사용자 강한 비판: *"kis 데이터 규격 확인 안하고 막 만들어서 꾸겨넣은거잖아"* (V4).

| 시점 | 위반 | 정정 |
|------|------|------|
| PR-11 V4 | instrument 14 컬럼 vs KIS 73 필드 = **91.7% fitness gap** | — |
| PR-21 | — | 70 필드 **add-only** 정정 + `pr-21-kis-header-raw-verification.md` baseline 보고서 발행 |
| PR-26 | — | 본 정책 운영 헌법 §3로 명문화 (5단계 의무 절차) |
| ADR-0053 | — | Living Policy로 분리 — 본 문서가 단일 본문 권위 |

---

## 변경 이력

- 2026-05-08: Living Policy로 분리 (ADR-0053, PR #248)
- 2026-05-XX: 운영 헌법 §3 명문화 (PR-26)
- 2026-05-XX: PR-21 baseline 검증 + architect SKILL 의무 추가
- 2026-05-12: charter §3 본문→ref 축소에 맞춰 **본 policy로 5단계 의무 절차 + 위반 baseline 흡수 (단일 본문 권위 확정)** (PR #349)
