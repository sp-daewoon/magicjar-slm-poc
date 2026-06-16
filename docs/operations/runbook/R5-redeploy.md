# R-5: 재배포

> ADR 0094 §3-7 P0-OPS-5 · ADR 0030 (master-architecture · build-and-deploy 정합) · CLAUDE.md 운영 함정 가드 §1·§2 정합

## 0. 사용 시점

- PR 머지 직후 (사용자 메모리 `feedback_release_policy_patch_first` — patch 사이클)
- hotfix 적용 후 (R-1 §4 dispatch 완료 후)
- KIS 키 회전 (`.env` 갱신 후 — 사용자 메모리 `project_kis_key_management`)
- observability stack 갱신 (Prometheus·Grafana config 변경 — `docs/operations/observability.md`)
- Flyway migration 신규 (R-4 §3-4 fix 후)
- Release cut 직전 docker 검증 (사용자 메모리 `feedback_docker_log_verify_before_cut` 의무)

R-1 §3-3 격리 lever + R-1 §5-1 검증 step 통합.

---

## 1. pre-flight 체크

### 1-1. main pull 정합 확인

```bash
# 작업 worktree 진입 (또는 main pull 직접)
cd /Users/ak-song/magic_ch1  # 또는 작업 worktree
git status
# 정상: "On branch main / Your branch is up to date with 'origin/main' / nothing to commit, working tree clean"

# 분기 시 (사용자 메모리 feedback_pr_rebase_sync 정합)
git fetch origin
git status -uno
# "behind 'origin/main' by N commits"이면 pull
git pull --rebase origin main
```

### 1-2. `.env` 정합 확인 (시크릿 미커밋 LOCK)

```bash
# .env 파일 존재 + git tracked 0 확인
ls -la .env
git check-ignore .env  # → 출력에 .env 있으면 정상 (gitignore)
git ls-files .env      # → 출력 0 row 의무 (시크릿 미커밋)
```

### 1-3. 영향 범위 결정

| 변경 영역 | 빌드 대상 |
|-----------|----------|
| JVM 코드 (api/batch/consumer/domain/persistence-shared/indicator-impl/strategy-engine) | `./gradlew check && ./gradlew bootJar` 전체 |
| api만 | `./gradlew :api:check :api:bootJar` |
| consumer만 | `./gradlew :consumer:check :consumer:bootJar` |
| batch만 | `./gradlew :batch:check :batch:bootJar` |
| domain·persistence-shared·indicator-impl·strategy-engine (라이브러리) | 의존 JVM 모듈 모두 rebuild |
| frontend | `cd frontend && pnpm typecheck && pnpm build` |
| docker compose만 (yml·env 변경) | `--no-gradle` flag |
| observability stack | `docker compose -f docker/docker-compose.observability.yml up -d` |

---

## 2. 영역별 build

### 2-1. JVM 전체

```bash
# 1) 전체 검증 (husky pre-push가 자동 실행 — 수동 실행은 PR push 전 사전 확인용)
./gradlew check
# 정상: BUILD SUCCESSFUL + ktlint 0 violation + test 152 PASSED

# 2) jar 산출
./gradlew :api:bootJar :batch:bootJar :consumer:bootJar
# 출력: api/build/libs/api-X.Y.Z.jar 등
ls -lh api/build/libs/ batch/build/libs/ consumer/build/libs/
```

### 2-2. JVM 부분 (영역별)

```bash
# api만
./gradlew :api:check :api:bootJar

# consumer만 (KillSwitchAutoToggler·SignalGenerator·KIS WS 영역)
./gradlew :consumer:check :consumer:bootJar

# batch만 (DailyCandleBackfillJob·BalanceReconcileJob 영역)
./gradlew :batch:check :batch:bootJar
```

### 2-3. frontend

```bash
cd frontend
pnpm typecheck   # TypeScript 검증
pnpm build       # vite production build → dist/

# 결과 확인
ls -lh dist/
# 정상: index.html + assets/ (JS·CSS bundle)

cd ..
```

### 2-4. ktlint 자동 정정 (husky pre-commit 통과 의무)

```bash
# 변경 파일 자동 정정
./gradlew ktlintFormat

# 잔여 위반 확인
./gradlew ktlintCheck
# 정상: BUILD SUCCESSFUL
```

---

## 3. 통합 명령 (build-and-deploy.sh)

### 3-1. 전체 재배포 (가장 일반)

```bash
scripts/build-and-deploy.sh
# 동작:
# 1. ./gradlew :api:bootJar :batch:bootJar :consumer:bootJar (호스트 빌드)
# 2. docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
#       --env-file "$PWD/.env" build (jar copy + image 재생성)
# 3. docker compose ... up -d (4 모듈 + infra 3 + dozzle·kafka-ui)
```

### 3-2. 일부 서비스만

```bash
# api + frontend만
scripts/build-and-deploy.sh api-app frontend

# consumer 단독
scripts/build-and-deploy.sh consumer-app
```

### 3-3. flag 매트릭스

| flag | 동작 | 사용 시점 |
|------|------|----------|
| (없음) | gradle + docker 전체 | 코드 변경 후 표준 |
| `--gradle-only` | jar만 갱신 (docker compose 건너뜀) | IDE bootRun 직전 산출물 갱신 |
| `--no-gradle` | docker만 (jar 최신 가정) | .env·yml만 변경했을 때 |

### 3-4. 인프라 별도 셋업

```bash
# 관측 stack만
docker compose -f docker/docker-compose.observability.yml \
  --env-file "$PWD/.env" up -d

# 인프라 (postgres·kafka·redis) + 관측 (IDE bootRun 친화)
docker compose -f docker/docker-compose.infra.yml \
  -f docker/docker-compose.observability.yml \
  --env-file "$PWD/.env" up -d
```

### 3-5. ★ 운영 함정 가드 (CLAUDE.md §1·§2)

1. **stale jar 함정**: `api/batch/consumer` Dockerfile = `COPY build/libs/*.jar`. 코드 변경 후 `bootJar` 안 돌리면 옛 jar 그대로 컨테이너 진입. `build-and-deploy.sh`가 정석 경로 — `docker compose build` 단독 사용 금지.
2. **`--env-file` 절대경로 LOCK**: docker compose 작업 디렉토리가 `docker/`로 바뀌면 `.env` 미발견. `--project-directory` 사용 시 `build.context: ../batch`가 root 기준 잘못 해석.
3. **default profile = `dev,real-kis`** LOCK (사용자 메모리 `feedback_no_mock_data` 정합 — mock 부팅 path 폐기).

---

## 4. 검증 (Verify)

### 4-1. docker ps 4 모듈 healthy

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep magicjar
# 정상 표시:
# magicjar-postgres   Up X mins (healthy)
# magicjar-kafka      Up X mins (healthy)
# magicjar-redis      Up X mins (healthy)
# magicjar-api        Up X mins (healthy)
# magicjar-batch      Up X mins (healthy)
# magicjar-consumer   Up X mins (healthy)
# magicjar-frontend   Up X mins (healthy)
```

`unhealthy` 또는 미존재 시 R-4 (DB) 또는 docker logs grep으로 root cause 분석.

### 4-2. docker logs 최근 5분 startup 에러 0건

```bash
# 4 모듈 startup 에러 patrol
for m in api batch consumer; do
  echo "─── magicjar-$m ───"
  docker logs --since 5m "magicjar-$m" 2>&1 | grep -E "ERROR|FATAL|Caused by|FlywayException" | head -5
done
# 기대: 4 모듈 모두 출력 0 row
```

### 4-3. SystemEvent emit 정상

```bash
# 부팅 SystemEvent — KisWebSocketBridge WS_CONNECTED 등
docker logs --since 2m magicjar-consumer 2>&1 | grep -E "SystemEvent emit|WS_CONNECTED"
# 정상 예:
# "SystemEvent emit category=KIS_WS_HEALTH severity=INFO actor=KisWebSocketBridge"
```

```sql
-- DB 부팅 직후 SystemEvent INFO row 확인
SELECT category, severity, actor, occurred_at
FROM system_event
WHERE occurred_at >= NOW() - INTERVAL '5 minutes'
ORDER BY occurred_at DESC LIMIT 20;
```

### 4-4. qa Visual 회귀 (운영 헌법 §10-2 의무)

frontend 변경 PR 또는 핵심 화면 영향 시 의무. 5 시나리오 — Dashboard·Orders·Positions·Settings·Alarm.

```bash
cd frontend
pnpm qa:visual
# Playwright snapshot 5 시나리오 실행
# 정상: All N tests passed
# 결함: HTML report 생성 → playwright-report/index.html 검토

# baseline 갱신 (의도된 UI 변경 시)
pnpm qa:visual:update
```

★ qa Visual 결과 evidence 부재 시 PR 머지 차단 (qa "품질 헌터" 페르소나 LOCK — ADR 0094 §2-2-B).

### 4-5. 핵심 화면 5건 dogfooding (사용자 메모리 `feedback_verification_strict`)

```text
1. Dashboard (`/`) — SymbolPanel 실시간 표시 + Watchlist 갱신 1초 이내
2. Orders (`/orders`) — 최근 주문 list + 상태 (PENDING·FILLED·CANCELLED) 표시
3. Positions (`/positions`) — 잔고 + 평가손익 표시 + drift 0
4. Settings (`/` 우상단 ⚙️) — 4 Section (KIS 키·시스템·KillSwitch·실거래 동의) 정상 mount
5. 알람센터 / SystemEventPage (`/system`) — 최근 SystemEvent 표시 + ERROR row 0
```

### 4-6. 자본 path 정상화 확인

```bash
# account API 200 + 데이터 응답
curl -s http://localhost:18080/api/account/inquire-balance \
  -H "X-User-Id: <userId>" | jq '{httpOk: .httpOk, holdings: (.holdings | length), reservedBalance: .reservedBalance}'

# reconcile 수동 트리거 — drift 0 확인
curl -X POST http://localhost:18080/api/risk/reconcile/<userId> \
  -H "X-User-Id: <userId>" | jq '.driftCount'
# → 0 기대
```

### 4-7. KIS WS 연결 확인 (장중 09:00~15:30 SLO > 99%)

```bash
# consumer KIS WS connect evidence
docker logs --since 2m magicjar-consumer 2>&1 | grep -E "KisWebSocket|H0STCNT0|WS_CONNECTED"
# 정상:
# "KisWebSocketBridge WS connected — approval_key=...  (SystemEvent WS_CONNECTED emit)"
# "subscribe H0STCNT0 — symbol=005930"
```

---

## 5. Release cut 직전 의무 (사용자 메모리 `feedback_docker_log_verify_before_cut`)

각 사이클 tag cut 직전:

```bash
# 1) build-and-deploy 재배포 (§3-1)
scripts/build-and-deploy.sh

# 2) 4 모듈 docker logs 5분 에러 patrol (§4-2)
for m in api batch consumer frontend; do
  echo "─── magicjar-$m ───"
  docker logs --since 5m "magicjar-$m" 2>&1 | grep -E "ERROR|FATAL|Exception" | wc -l
done
# 기대: 모두 0

# 3) 에러 발견 시 hotfix 사이클 → 0건 확인 후 cut
```

cut 절차 (ADR 0094 §4-3 release 정책 + CLAUDE.md release 절차):
```bash
git tag vX.Y.Z
git push origin vX.Y.Z

# release/* immutable 브랜치 (pre-push 가드 LOCK)
git push origin main:refs/heads/release/vX.Y.Z

# release notes (baseline html 인용)
scripts/release.sh vX.Y.Z
```

---

## 6. rollback (재배포 실패 시)

### 6-1. 즉시 rollback (이전 jar 복원)

```bash
# 1) 이전 jar 복원 (git checkout 이전 commit)
git log --oneline -5
git checkout <이전 commit sha>

# 2) 재배포
scripts/build-and-deploy.sh

# 3) main 복귀 — fix 후 재진입
git checkout main
```

### 6-2. DB rollback (Flyway down — 위험 — 사용자 결재 의무)

Flyway down은 default 미지원 (out-of-the-box). DB 복원이 필요하면 R-4 §3-2 pg_restore.

---

## 7. post-mortem

R-1 §6 통합. 추가 evidence:
- 재배포 실패 빈도 ≥ 분기 3회 → CI 자동 검증 도입 검토 (사용자 메모리 `feedback_no_github_actions` 정합 시 husky pre-push gradle check 강화)
- startup 5분 에러 발견 빈도 ≥ 1회/사이클 → backend-core 부팅 검증 보강
- qa Visual 회귀 detect 빈도 ≥ 주 1회 → ADR 0094 §4-5 점진 고도화 #6 trigger (frontend 안정성 개선 사이클)

### 7-1. ADR cross-link

- **ADR 0030 §6 모듈 매트릭스** — JVM 4 모듈 + frontend 영역 매트릭스
- **ADR 0008** — Docker 표준 (모듈별 Dockerfile + compose 통합 기동)
- **ADR 0094 §3-7 P0-OPS-5** — 본 runbook 발행 권위
- **ADR 0094 §10-2 변경 관리** — PR diff > 50 LOC 또는 자본 path 변경 시 rollback plan 본문 명시 + canary (dev → staging → REAL 30분 staging dwell) + frontend 핵심 화면 변경 시 qa Visual 자동 dispatch
- **ADR 0094 §10-5 신규 ADR 양식** — 모든 신규 ADR §결과 직전 SLO 영향·rollback plan·관측 매트릭스 의무

---

## 8. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. build-and-deploy.sh flag 매트릭스 + 4 모듈 healthy 검증 + qa Visual 회귀 의무 + Release cut 직전 docker logs 0 LOCK + rollback 절차. |
