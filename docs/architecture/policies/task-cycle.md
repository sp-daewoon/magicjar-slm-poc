# Task 사이클 — 현행 권위

> **권위**: 본 문서 + `scripts/task.mjs` 동작.
> **결정 이력**: ADR-0036 §1 (사이클 정의), 운영 헌법 §1·§2, ADR-0051 (default fun trap 영향)
> **마지막 갱신**: 2026-05-08 (Living Policy 분리)

---

## 현행 4단계

```bash
# 1. 시작 — agent worktree 진입 직후
node scripts/task.mjs start --agent <agent> --slug <slug> \
  --goal "..." --deliverables "...,...,..."

# 2. 체크 — 산출물 1개 완료 즉시
node scripts/task.mjs check "<항목>"

# 3. 완료 — PR 발행 후
node scripts/task.mjs done --pr <N>

# 4. archive — 머지 후 정리 4단계의 일부
node scripts/task.mjs archive T-<id>           # status=DONE 강제
node scripts/task.mjs archive T-<id> --force   # RUNNING도 강제 (sub-agent done 누락 시)
```

## 머지 후 정리 4단계 (헌법 §2)

PR 머지 직후 즉시:

```bash
git pull --ff-only origin main
node scripts/task.mjs archive T-<id>
git -C <worktree> restore _workspace/tasks/  # 잔재 제거
git worktree remove <worktree>
git branch -d agent/<agent>/<branch>
```

자동 점검 (orchestrator 사이클 시작 시):

```bash
ls _workspace/tasks/T-*.md | wc -l  # 5건 이상이면 일괄 archive
git worktree list                   # 잔재 검증
```

## task ID + slug 분리 (PR-30 학습)

분 단위 timestamp + 동일 slug면 ID 충돌. 4 sub-agent 동시 발행 시 **`--slug pr-N-<agent>` 필수**:

```bash
node scripts/task.mjs start --agent architect --slug pr-30-architect ...
node scripts/task.mjs start --agent backend-core --slug pr-30-backend ...
# T-20260508-1317-pr-30-architect, T-20260508-1317-pr-30-backend ← ID 분리
```

---

## 적용 위치 (코드)

- `scripts/task.mjs` — 4단계 본체
- `_workspace/tasks/` — 활성 task
- `_workspace/tasks/done/` — archive

## 함정

| 케이스 | 회피 |
|--------|------|
| sub-agent가 task done 누락 | orchestrator가 `archive --force` |
| task 파일이 main worktree에 untracked로 남음 | git pull 전 `rm -f _workspace/tasks/T-<id>.md` |
| worktree 안 task.mjs 호출 못함 | task 파일을 worktree에 cp 동기화 |
| task ID 충돌 (분 단위) | `--slug` 명시 |

---

## 변경 이력

- 2026-05-08: Living Policy로 분리 (ADR-0053, PR #248)
- 2026-05-XX: PR-30 learnings — slug 분리 의무 명문화
- 2026-05-XX: 운영 헌법 §2 머지 후 정리 4단계 통합 (PR-26)
- 2026-05-XX: ADR-0036 task.mjs 사이클 도입
