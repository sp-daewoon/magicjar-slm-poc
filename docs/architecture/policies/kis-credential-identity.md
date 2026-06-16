# KIS Credential 식별 규약 — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-0046 (Onboarding 도입), backlog §23 (REAL/MOCK 불일치 의제), PR #246 (firstOrNull fallback)
> **마지막 갱신**: 2026-05-08 (PR #246)

---

## 현행 규약

`user_kis_credential` 테이블의 `user_id` 컬럼에 들어가는 값이 **REAL/MOCK 다름** (single-user 환경 가정):

| 모드 | `user_id` 컬럼 | `account_type` |
|------|----------------|----------------|
| REAL | **계좌번호** (`request.accountNumber`) | `"REAL"` |
| MOCK | **KIS user_id** (`request.userId`) | `"MOCK"` |

### Lookup 전략 (single-user 가정)

`KisApprovalAdapter`·`KisRestCandleAdapter`는 다음 fallback 적용:

```kotlin
val cred = credentialRepository
    .findAllByAccountType(accountType.name)
    .firstOrNull { it.id.userId == userId }
    ?: creds.firstOrNull()    // single-user fallback
    ?: error("KIS 자격증명 없음 — type=$accountType")
```

→ `defaultUserId`와 매칭 fail 해도 **첫 credential 사용**. single-user 환경에서 정상 작동.

---

## 적용 위치 (코드)

| 위치 | 역할 |
|------|------|
| `api/.../OnboardingService.kt:136` | REAL 키 저장 (user_id=accountNumber) |
| `api/.../OnboardingService.kt:231` | MOCK 키 저장 (user_id=userId) |
| `consumer/.../KisApprovalAdapter.kt:124-130` | WS approval_key — firstOrNull fallback |
| `consumer/.../KisRestCandleAdapter.kt:71-72` | REST 캔들 — firstOrNull |
| `consumer/.../PositionBootstrapRunner.kt:58` | 모든 active credential 순회 (userId 매칭 X) |

---

## 알려진 한계 + 다음 진화

**현재 single-user 환경 한정**. multi-tenant 진입 시 식별 규약 통일 필요. backlog §23에서 4 옵션 검토:

1. REAL/MOCK 모두 user_id 통일 (user_account.user_id 권위)
2. user_kis_credential.user_id 컬럼 의미 명시 + 주석 + ADR
3. KisWebSocketBridge `defaultUserId` 의미 분기 (REAL=계좌번호, MOCK=user_id)
4. `kis_user_id` 컬럼 별도 추가 (마이그레이션)

**트리거**: W3 T-O (Order) 진입 시 본격 정리 — 사용자별 주문 권한 격리 시점.

---

## 변경 이력

- 2026-05-08: 도입 (ADR-0053, PR #248) — Living Policy로 분리
- 2026-05-08: firstOrNull fallback 추가 (PR #246, backlog §23 신설)
- 2026-05-XX: 도입 시점 (ADR-0046 — Onboarding & Credential Registration)
