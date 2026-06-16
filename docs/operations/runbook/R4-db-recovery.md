# R-4: DB 복구·Backup

> ADR 0094 §3-7 P0-OPS-5 · 운영 헌법 §9-4 정기 backup · **ADR 0096 P0-OPS-4 DR/backup 단일 권위** (스크립트·산출물·RPO/RTO·DR drill 본문화 완료 — 2026-05-21)

## 0. 사용 시점

- `docker ps`에 `magicjar-postgres` 미존재 또는 status `unhealthy`
- `docker logs magicjar-api` startup 로그에 `FlywayException` 또는 `Connection refused`
- 사용자 화면 자본 path 차단 (account API 500 + position 표시 불가)
- table drop · query 폭주 · DB 파일 무결성 손상 의심
- Flyway migration 실패 (V44 이후 신규 migration)
- 분기 DR drill (ADR 0094 §10-4 — qa Visual E2E 자동 재현 의무)

R-1 §2-3 분기 LOCK — DB 분류 시 본 runbook 진입.

---

## 1. 상태 확인 (Diagnose)

### 1-1. 컨테이너 상태

```bash
# postgres 컨테이너 상태
docker ps -a | grep magicjar-postgres
# 정상: STATUS=Up X hours (healthy)
# 결함: Exited 또는 Up X hours (unhealthy)
```

### 1-2. DB 가용성 확인 (pg_isready)

```bash
# postgres ready 체크
docker exec magicjar-postgres pg_isready -U magicjar -d magicjar
# 정상: "/var/run/postgresql:5432 - accepting connections"
# 결함: "no response" 또는 "connection refused"
```

### 1-3. Flyway 정합 확인

```bash
# Flyway info — 적용된 migration 목록 + 실패 row 확인
docker exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20;"
```

`success = false` row 발견 시 Flyway 부팅 차단 — § 3 복구 진입.

### 1-4. 앱 startup 로그 grep

```bash
# api 부팅 결함 root cause
docker logs --since 5m magicjar-api 2>&1 | grep -E "FlywayException|HikariPool|Failed to start|caused by:" | head -30

# 일반 패턴:
# "FlywayException: Validate failed: Migration checksum mismatch"
# "FlywayException: Migration V44__system_event.sql failed"
# "HikariPool: Connection is not available"
```

### 1-5. 무결성 매트릭스 5건 (R-3 §6 통합)

```sql
-- 1. 핵심 테이블 row count baseline
SELECT 'orders' AS tbl, COUNT(*) FROM orders UNION ALL
SELECT 'position', COUNT(*) FROM position UNION ALL
SELECT 'kill_switch_state', COUNT(*) FROM kill_switch_state UNION ALL
SELECT 'system_event', COUNT(*) FROM system_event UNION ALL
SELECT 'stomp_event', COUNT(*) FROM stomp_event UNION ALL
SELECT 'reconcile_run', COUNT(*) FROM reconcile_run UNION ALL
SELECT 'order_event_outbox', COUNT(*) FROM order_event_outbox UNION ALL
SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history;

-- 2. 최신 sequence 정합 (PK 자동증가 정상)
SELECT pg_get_serial_sequence('orders', 'id') AS seq, last_value
FROM orders, LATERAL pg_sequences
WHERE schemaname = 'public' AND sequencename = 'orders_id_seq';

-- 3. orders.status pending 잔여 (KillSwitch ON 시 cancel 흐름 결함 추정)
SELECT status, COUNT(*) FROM orders
GROUP BY status ORDER BY COUNT(*) DESC;

-- 4. KillSwitch state 분실 검증 (사용자별 1 row 의무)
SELECT user_id, enabled, updated_at FROM kill_switch_state;

-- 5. Outbox drain 정합 (ADR 0088 — persistence-shared)
SELECT topic, status, COUNT(*) FROM order_event_outbox
GROUP BY topic, status;
-- → status='PUBLISHED'가 절대 다수 의무. PENDING이 100+ 누적 시 drain lag risk.
```

---

## 2. 격리 (Isolate)

### 2-1. 자본 path 보호

DB 결함 → 잔고·position·KillSwitch 모두 영향 → **즉시 KillSwitch ON 검토** (R-3 §3-1).

단, KillSwitch도 `kill_switch_state` 테이블 의존 → DB 자체 결함 시 토글 불가. 이 경우 frontend OrderRouter 정지 시간 짧으면 자연 격리 (사용자 주문 발사 불가).

### 2-2. 앱 정지 (DB 손상 시 추가 write 차단)

```bash
# api·batch·consumer 정지 — DB 추가 write 차단
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" stop api-app batch-app consumer-app

# frontend는 정지 안 함 (사용자에게 격리 상태 표시 — "데이터 없음")
```

---

## 3. 복구 (Recover)

### 3-1. 백업 위치 (ADR 0096 §2-4 산출물 LOCK)

ADR 0094 §9-4 정기 backup + ADR 0096 §2·§3 매트릭스 LOCK:
- **PostgreSQL 위치**: `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` (cron 산출물 — ADR 0096 §2-1)
- **Redis 위치**: `_workspace/backups/redis/snapshots/dump-YYYY-MM-DD-HHMM.rdb` (cron 산출물 — ADR 0096 §3-2)
- **파일 형식**: pg_dump custom format (`-Fc`) — `pg_restore --clean --create` 호환
- **주기**: PostgreSQL 매일 03:00 KST + 7일 retention · Redis 매일 03:30 KST + 3일 retention (backend-core 책임)
- **스크립트**: `scripts/backup/pg-dump-daily.sh` + `scripts/backup/redis-snapshot.sh` (executable)
- **사용자 가이드**: `docs/operations/backup-setup.md` (macOS crontab 등록)

```bash
# 백업 파일 목록 확인 (ADR 0096 §2-4 정합)
ls -lh _workspace/backups/postgres/daily/ | tail -10
# 예시 출력:
# -rw-r--r--  1 user  staff  3.2M  5 21 03:00 2026-05-21.dump
# -rw-r--r--  1 user  staff  3.1M  5 20 03:00 2026-05-20.dump
```

★ **현 단계 evidence**: ADR 0096 발행 완료. `_workspace/backups/postgres/daily/` 디렉토리는 사용자 macOS crontab 등록 후 첫 03:00 KST 사이클에 자동 생성. 본 runbook은 ADR 0096 §2·§3 정합 절차 인용.

### 3-2. 복구 — pg_restore

```bash
# 1) 가장 최근 백업 선택
LATEST_BACKUP=$(ls -t _workspace/backups/postgres/*.dump | head -1)
echo "Restoring from: $LATEST_BACKUP"

# 2) 호스트 → postgres 컨테이너 복사
docker cp "$LATEST_BACKUP" magicjar-postgres:/tmp/restore.dump

# 3) pg_restore (--clean --create — 기존 DB 삭제 후 재생성)
docker exec magicjar-postgres pg_restore \
  --clean --create --if-exists \
  -h localhost -U magicjar -d postgres \
  /tmp/restore.dump
# 정상 종료 코드: 0
# 경고 (warning) 일부 OK (예: "role magicjar already exists")

# 4) 임시 파일 정리
docker exec magicjar-postgres rm /tmp/restore.dump
```

### 3-3. 부분 복구 (특정 테이블만)

```bash
# 예: orders 테이블만 복구
docker exec magicjar-postgres pg_restore \
  -h localhost -U magicjar -d magicjar \
  --data-only --table=orders \
  /tmp/restore.dump
```

### 3-4. Flyway 결함 복구 (migration 실패)

migration 파일 자체 결함 (예: V44 SQL syntax error) → fix 후 재배포:

```bash
# 1) flyway_schema_history에서 실패 row 제거
docker exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "DELETE FROM flyway_schema_history WHERE success = false;"

# 2) 영향받은 partial migration 수동 rollback (테이블 drop 등)
# 예: V44 partial — system_event 테이블 부분 생성
docker exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "DROP TABLE IF EXISTS system_event CASCADE;"

# 3) migration SQL fix (api/src/main/resources/db/migration/V44__system_event.sql)
# 4) 앱 재기동 — Flyway 자동 재실행
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" start api-app
```

### 3-5. Postgres 컨테이너 자체 결함 (이미지 손상·볼륨 결함)

```bash
# 1) 정지 + 컨테이너 삭제 (볼륨 유지)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" stop postgres
docker rm magicjar-postgres

# 2) 재생성 (볼륨 그대로 — 데이터 보존)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" up -d postgres

# 3) healthy 도달 대기 (보통 15~30초)
until docker exec magicjar-postgres pg_isready -U magicjar -d magicjar; do
  sleep 2
done
```

### 3-6. 볼륨 자체 손상 (최후 수단 — full restore)

```bash
# ★ 데이터 전체 손실 risk — 백업 존재 확인 후만
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" down postgres
docker volume rm magic_ch1_postgres-data
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" up -d postgres

# pg_restore (§3-2 절차)
```

---

## 4. 검증 (Verify)

### 4-1. Flyway 정합 확인

```bash
docker exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT version, description, success FROM flyway_schema_history WHERE success = false;"
# 결과 0 row 기대
```

### 4-2. 앱 startup 정상 (Flyway 통과)

```bash
# 앱 재기동
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" up -d api-app batch-app consumer-app

# 5분 startup 로그 — Flyway 통과 + Spring Context 정상 evidence
docker logs --since 2m magicjar-api 2>&1 | grep -E "Flyway|Started|HikariPool" | head -10
# 정상:
# "Flyway Community Edition X.Y.Z by Redgate"
# "Successfully validated NN migrations"
# "Started MagicjarApiApplication in NN.NNN seconds"
# "HikariPool-1 - Start completed"
```

### 4-3. 무결성 매트릭스 5건 재실행

§1-5 SQL 5건 재실행 → 백업 시점 row count와 비교. count 차이 발생 시 audit row 의무.

### 4-4. 자본 path 정상화 확인

```bash
# account API 200 응답
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:18080/api/account/inquire-balance \
  -H "X-User-Id: <userId>"
# → 200

# Dashboard 진입 → 잔고 표시 + Watchlist 갱신 + Positions 정상
```

### 4-5. KillSwitch state 정합

```bash
# kill_switch_state 백업 시점 enabled 값과 비교
docker exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT user_id, enabled, reason, updated_at FROM kill_switch_state;"
```

복구 후 enabled=true 잔존 시 R-3 §3-2 수동 OFF 결재 (사용자 단독).

---

## 5. backup 정책 (운영 헌법 §9-4 — backend-core 책임 + ADR 0096 단일 권위)

### 5-1. cron 셋업 (ADR 0096 + `docs/operations/backup-setup.md` 정합)

ADR 0096 발행 완료. 사용자 macOS crontab 등록 1회 (자세한 가이드: `docs/operations/backup-setup.md` §2):

```bash
crontab -e
```

```cron
# magicJar backup — ADR 0096 P0-OPS-4 정합
0 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1
30 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
```

스크립트는 `scripts/backup/pg-dump-daily.sh` + `scripts/backup/redis-snapshot.sh` — pg_isready/BGSAVE 진단 + retention rotation + KST 로그 자동.

★ 현 단계: ADR 0096 baseline LOCK. 사용자 cron 등록 후 첫 03:00 KST 사이클에 자동 backup 활성.

### 5-2. 수동 backup (긴급 시)

```bash
# 즉시 1회 백업 (재배포 직전·DR drill 전 의무)
mkdir -p _workspace/backups/postgres
docker exec magicjar-postgres pg_dump -U magicjar -Fc magicjar > \
  "_workspace/backups/postgres/magicjar-$(date +%Y-%m-%d-%H%M)-manual.dump"
ls -lh _workspace/backups/postgres/ | tail -3
```

### 5-3. 분기 DR drill (ADR 0094 §10-4 의무)

분기 1회:
1. 최근 backup으로 staging 환경 복구
2. qa Visual E2E 시나리오 자동 재현 (Playwright 5 시나리오)
3. account API + Dashboard 정상 표시 확인
4. 결과 evidence: `_workspace/devlog/YYYY-MM-DD-dr-drill.html`

---

## 6. post-mortem

R-1 §6 통합. 추가 evidence:
- DB 결함 빈도 ≥ 분기 1회 → P0-OPS-4 DR ADR 갱신 + retention 정책 LOCK
- Flyway migration 실패 ≥ 분기 1회 → migration 검증 절차 갱신 (CI 도입 검토 — 사용자 메모리 `feedback_no_github_actions` 정합 시 husky pre-push gradle check로 흡수)
- Outbox drain lag P95 > 30초 → 운영 헌법 §10-1 SLO 위반 — backend-core capacity plan
- 무결성 SQL 5건 결과 baseline 누적 → 분기 1회 SystemEvent metric 추가 검토

### 6-1. ADR cross-link

- **ADR 0096 P0-OPS-4** ★ — DR/backup 단일 권위 (PostgreSQL 매트릭스 + Redis 매트릭스 + RPO 24h + RTO 30분 + DR drill 분기 + 스크립트 + 사용자 가이드)
- **ADR 0094 §3-7 P0-OPS-4** — task 본문화 (ADR 0096이 본문화 완료)
- **ADR 0094 §9-4** — postgres pg_dump 매일 03:00 + 7일 retention + 분기 DR drill (ADR 0096 본문화)
- **ADR 0095 P0-OPS-3** — SLO/SLA + alert routing (RTO/RPO 측정 source cross)
- **ADR 0088** — persistence-shared Outbox (drain lag 모니터링 — P0-OPS-6 cross)
- **ADR 0044** — Notification (backup 실패 Slack alert reuse)
- **ADR 0043** — KillSwitch (DR drill 시 사용자 결재 정합)
- **ADR 0070** — T-RECON notify bundled (DR drill 후 reconcile 강제)
- **ADR 0006** — PostgreSQL 단일 + 스키마 분리 (기술 스택 베이스)
- **ADR 0030** — master-architecture (post-MVP TimescaleDB 전환 — 일봉 ≥1억 row 도달 시 ADR 0096 amend 트리거)

---

## 7. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | **ADR 0096 cross-link 보강** — §3-1 백업 위치 + §5-1 cron 셋업 + §6-1 ADR cross-link 매트릭스 갱신. ADR 0096 P0-OPS-4 DR/backup 단일 권위 본문화 완료(스크립트·산출물·RPO 24h·RTO 30분·DR drill 분기). `scripts/backup/pg-dump-daily.sh` + `scripts/backup/redis-snapshot.sh` + `docs/operations/backup-setup.md` 신규 발행. (architect) |
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. pg_isready·Flyway info·무결성 SQL 5건·pg_restore·부분 복구·볼륨 손상 절차 매트릭스. P0-OPS-4 DR ADR 발행 후 cron 셋업 활성. |
