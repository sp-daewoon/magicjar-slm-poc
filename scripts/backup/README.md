# magicJar backup 스크립트

> ADR 0096 §2·§3 정합 — PostgreSQL daily full dump + Redis snapshot

## 스크립트 매트릭스

| 스크립트 | 주기 | 산출물 | retention |
|---------|------|--------|----------|
| `pg-dump-daily.sh` | 매일 03:00 KST | `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` | 7일 |
| `redis-snapshot.sh` | 매일 03:30 KST | `_workspace/backups/redis/snapshots/dump-YYYY-MM-DD-HHMM.rdb` | 3일 |
| `pg-restore.sh` | on-demand (DR·새 PC 복원·검증) | — (dump 복원 + dry-run + 복원 후 검증) | — |

## 복원 (pg-restore.sh — dump 짝)

```bash
# 1) dry-run — 실제 복원 0, dump 내용 미리보기
bash scripts/backup/pg-restore.sh --dry-run

# 2) 검증/연습 — 임시 DB(운영 무손상)에 복원 + 정리
bash scripts/backup/pg-restore.sh --target-db magicjar_restore_test --drop-after

# 3) 운영 DB 전체 복구 (DR — R-4 runbook §2 앱 정지 선행 의무)
bash scripts/backup/pg-restore.sh --target-db magicjar --confirm-prod
```

★ default target-db = `magicjar_restore_test` (운영 보호). 운영 복원은 `--confirm-prod` 이중 게이트 필수.
새 PC 완전 복원 절차: [`docs/operations/new-pc-migration-runbook.md`](../../docs/operations/new-pc-migration-runbook.md).

## 사용

### 수동 1회 실행 (긴급 시·재배포 직전 의무)

```bash
# PostgreSQL daily full dump
bash scripts/backup/pg-dump-daily.sh

# Redis RDB snapshot
bash scripts/backup/redis-snapshot.sh
```

### cron 등록 (사용자 macOS 단독 운영자)

자세한 가이드: [`docs/operations/backup-setup.md`](../../docs/operations/backup-setup.md)

요약:

```bash
crontab -e
```

```cron
# magicJar daily backup — ADR 0096 정합
0 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1
30 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
```

## 환경변수 (선택 — default 권장)

| 환경변수 | default | 본문 |
|---------|---------|------|
| `MAGICJAR_PG_CONTAINER` | `magicjar-postgres` | postgres 컨테이너 이름 |
| `MAGICJAR_PG_USER` | `magicjar` | DB 사용자 |
| `MAGICJAR_PG_DB` | `magicjar` | DB 이름 |
| `MAGICJAR_PG_BACKUP_RETENTION_DAYS` | `7` | daily backup retention 일수 |
| `MAGICJAR_REDIS_CONTAINER` | `magicjar-redis` | redis 컨테이너 이름 |
| `MAGICJAR_REDIS_SNAPSHOT_RETENTION_DAYS` | `3` | snapshot retention 일수 |

## 검증 의무

### backup 직후

```bash
# PostgreSQL backup 존재 + 용량 확인
ls -lh _workspace/backups/postgres/daily/ | tail -3

# Redis snapshot 존재 + 용량 확인
ls -lh _workspace/backups/redis/snapshots/ | tail -3
```

### 복구 시 (DR drill 분기 의무)

[`docs/operations/runbook/R4-db-recovery.md`](../../docs/operations/runbook/R4-db-recovery.md) 절차 LOCK:

- §3-2 pg_restore --clean --create — DB 전체 복구 (RTO 30분)
- §3-3 부분 복구 — 특정 테이블만 (RTO 15분)
- §4 검증 매트릭스 — Flyway·startup·무결성 SQL 5건·자본 path·KillSwitch state

### 분기 DR drill (운영 헌법 §10-4)

- 사용자 결재 의무 + qa Visual E2E 5 시나리오 자동 재현
- evidence: `_workspace/devlog/YYYY-MM-DD-dr-drill-quarter-N.html`
- 실측 RPO < 24h + RTO < 30분 (ADR 0096 §6-3 매트릭스)

## 관련 ADR

- **ADR 0096** — DR/backup 절차 단일 권위 + RPO/RTO + DR drill
- ADR 0094 §3-7 P0-OPS-4 + §9-4 backup·DR
- ADR 0095 P0-OPS-3 SLO/SLA + alert routing
- ADR 0088 persistence-shared (Outbox/notification recovery 정합)
- ADR 0006 PostgreSQL 단일 + 스키마 분리

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0096 §2·§3 정합 + 사용자 macOS crontab 가이드 + 환경변수 매트릭스 (architect) |
