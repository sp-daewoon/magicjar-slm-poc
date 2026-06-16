#!/usr/bin/env bash
#
# redis-snapshot.sh — magicJar Redis RDB snapshot backup
#
# ADR 0096 §3-2 정합 — 매일 03:30 KST + 3일 retention
# 사용자 cron 등록 가이드: docs/operations/backup-setup.md
#
# 사용:
#   ./scripts/backup/redis-snapshot.sh
#
# cron 등록 예시 (사용자 macOS crontab -e):
#   30 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
#
# 환경:
#   - docker compose redis 컨테이너 `magicjar-redis` running 의무
#   - 호스트 디스크 여유 공간 ≥ 50MB (baseline ~5MB × 3일 = 15MB)

set -Eeuo pipefail

# ────────────────────────────────────────────────────────────
# 설정 (ADR 0096 §3-2 LOCK)
# ────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="${REPO_ROOT}/_workspace/backups/redis/snapshots"
CONTAINER_NAME="${MAGICJAR_REDIS_CONTAINER:-magicjar-redis}"
RETENTION_DAYS="${MAGICJAR_REDIS_SNAPSHOT_RETENTION_DAYS:-3}"

# KST timestamp (ADR 0096 §3-2)
TIMESTAMP="$(date '+%Y-%m-%d-%H%M')"
BACKUP_FILE="${BACKUP_DIR}/dump-${TIMESTAMP}.rdb"

# ────────────────────────────────────────────────────────────
# 진단 — redis 컨테이너 running 검증
# ────────────────────────────────────────────────────────────
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S KST')] [redis-snapshot] $*"
}

err() {
  log "ERROR: $*" >&2
}

if ! command -v docker >/dev/null 2>&1; then
  err "docker 명령 부재 — Docker Desktop 설치 + 실행 필요"
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  err "${CONTAINER_NAME} 컨테이너 running 0 — 'docker compose -f docker/docker-compose.infra.yml up -d' 또는 'scripts/build-and-deploy.sh' 실행 후 재시도"
  exit 1
fi

if ! docker exec "${CONTAINER_NAME}" redis-cli ping | grep -q PONG; then
  err "${CONTAINER_NAME} redis-cli ping FAIL — Redis 결함 의심"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# 산출물 디렉토리 보장 (ADR 0096 §2-4)
# ────────────────────────────────────────────────────────────
mkdir -p "${BACKUP_DIR}"

# ────────────────────────────────────────────────────────────
# Redis BGSAVE 트리거 (백그라운드 저장)
# ────────────────────────────────────────────────────────────
log "BGSAVE 트리거 — Redis 백그라운드 snapshot 시작"

# BGSAVE 실행 직전 lastsave timestamp 기록 (완료 감지 baseline)
BEFORE_LASTSAVE=$(docker exec "${CONTAINER_NAME}" redis-cli LASTSAVE)

if ! docker exec "${CONTAINER_NAME}" redis-cli BGSAVE | grep -qE "Background saving started|Background save already in progress"; then
  err "BGSAVE 트리거 FAIL"
  exit 1
fi

# BGSAVE 완료 대기 — LASTSAVE timestamp 변화 polling (최대 30초)
log "BGSAVE 완료 대기 (LASTSAVE polling, 최대 30초)"
WAIT=0
while [ "${WAIT}" -lt 30 ]; do
  sleep 1
  WAIT=$((WAIT + 1))
  AFTER_LASTSAVE=$(docker exec "${CONTAINER_NAME}" redis-cli LASTSAVE)
  if [ "${AFTER_LASTSAVE}" != "${BEFORE_LASTSAVE}" ]; then
    log "BGSAVE 완료 (${WAIT}초 소요)"
    break
  fi
done

if [ "${AFTER_LASTSAVE}" = "${BEFORE_LASTSAVE}" ]; then
  err "BGSAVE 완료 타임아웃 (30초) — Redis 부하 의심"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# RDB 파일 호스트 복사
# ────────────────────────────────────────────────────────────
log "RDB 파일 호스트 복사: ${BACKUP_FILE}"

if docker cp "${CONTAINER_NAME}:/data/dump.rdb" "${BACKUP_FILE}"; then
  SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
  log "snapshot 완료: ${BACKUP_FILE} (${SIZE})"
else
  err "docker cp FAIL — partial 파일 삭제"
  rm -f "${BACKUP_FILE}"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# retention rotation — RETENTION_DAYS 초과 파일 삭제 (ADR 0096 §3-2)
# ────────────────────────────────────────────────────────────
log "retention rotation: ${RETENTION_DAYS}일 초과 파일 정리"
DELETED=0
while IFS= read -r OLD_FILE; do
  rm -f "${OLD_FILE}"
  log "  삭제: ${OLD_FILE}"
  DELETED=$((DELETED + 1))
done < <(find "${BACKUP_DIR}" -name "*.rdb" -type f -mtime "+${RETENTION_DAYS}")

log "retention 완료: ${DELETED}건 삭제"

# ────────────────────────────────────────────────────────────
# 결과 요약
# ────────────────────────────────────────────────────────────
log "현재 snapshot 목록:"
ls -lh "${BACKUP_DIR}"/*.rdb 2>/dev/null | tail -5 | while IFS= read -r LINE; do
  log "  ${LINE}"
done

log "exit 0 — 정상 완료"
exit 0
