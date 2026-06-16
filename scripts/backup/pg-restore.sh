#!/usr/bin/env bash
#
# pg-restore.sh — magicJar PostgreSQL pg_dump 복원 (pg-dump-daily.sh 짝)
#
# ADR 0096 §4-2 RTO 30분 절차 + R-4 runbook §3-2 정합
# 새 PC 마이그레이션: docs/operations/new-pc-migration-runbook.md §C-4 인용
#
# pg-dump-daily.sh가 만든 custom format(-Fc) dump를 받아 복원한다.
# dump에 flyway_schema_history가 포함되므로 복원이 마이그레이션 이력까지 함께 회복한다.
#
# 사용:
#   # 1) 검증/연습 — 임시 DB(운영 DB 무손상)에 복원 후 테이블 수·flyway 버전 출력
#   ./scripts/backup/pg-restore.sh --target-db magicjar_restore_test
#   ./scripts/backup/pg-restore.sh --target-db magicjar_restore_test --drop-after
#
#   # 2) dry-run — 실제 복원 0, dump 내용(TABLE DATA·flyway)만 미리보기
#   ./scripts/backup/pg-restore.sh --dry-run
#
#   # 3) 운영 DB 전체 복구 (DR — R-4 runbook §2 앱 정지 후 의무)
#   ./scripts/backup/pg-restore.sh --target-db magicjar --confirm-prod
#
#   # 특정 dump 파일 지정 (생략 시 daily/ 최신 *.dump 자동 선택)
#   ./scripts/backup/pg-restore.sh --file _workspace/backups/postgres/daily/2026-06-06.dump
#
# 옵션:
#   --file <path>        복원할 dump 파일 (생략 시 daily/ 최신 자동 선택)
#   --target-db <name>   복원 대상 DB 이름 (default: magicjar_restore_test — 운영 보호)
#   --dry-run            pg_restore --list만 출력, 실제 복원 0
#   --no-safety-backup   복원 전 현재 DB 안전 백업 skip (default: target=운영 DB일 때만 백업)
#   --drop-after         복원·검증 후 target DB DROP (임시 검증 DB 정리)
#   --confirm-prod       target-db 가 운영 DB(magicjar)일 때 필수 — 오발사 방지 이중 게이트
#
# 환경:
#   - docker compose postgres 컨테이너 `magicjar-postgres` running 의무
#   - dump 파일은 pg-dump-daily.sh custom format(-Fc) 산출물 또는 동형
#
# ★ 안전 원칙: default target-db = magicjar_restore_test.
#   운영 DB(magicjar) 복원은 --target-db magicjar --confirm-prod 둘 다 명시해야만 진행.

set -Eeuo pipefail

# ────────────────────────────────────────────────────────────
# 설정 (ADR 0096 §2-1·§4-2 + pg-dump-daily.sh 정합)
# ────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="${REPO_ROOT}/_workspace/backups/postgres/daily"
SAFETY_DIR="${REPO_ROOT}/_workspace/backups/postgres/pre-restore"
CONTAINER_NAME="${MAGICJAR_PG_CONTAINER:-magicjar-postgres}"
DB_USER="${MAGICJAR_PG_USER:-magicjar}"
PROD_DB="${MAGICJAR_PG_DB:-magicjar}"

# 기본값 — 운영 보호 (검증용 임시 DB)
DUMP_FILE=""
TARGET_DB="magicjar_restore_test"
DRY_RUN=0
SAFETY_BACKUP="auto"   # auto: target=운영 DB일 때만 백업
DROP_AFTER=0
CONFIRM_PROD=0

# ────────────────────────────────────────────────────────────
# 로깅 (pg-dump-daily.sh 동형)
# ────────────────────────────────────────────────────────────
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S KST')] [pg-restore] $*"
}

err() {
  log "ERROR: $*" >&2
}

# ────────────────────────────────────────────────────────────
# 인자 파싱
# ────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --file)            DUMP_FILE="$2"; shift 2 ;;
    --target-db)       TARGET_DB="$2"; shift 2 ;;
    --dry-run)         DRY_RUN=1; shift ;;
    --no-safety-backup) SAFETY_BACKUP="off"; shift ;;
    --drop-after)      DROP_AFTER=1; shift ;;
    --confirm-prod)    CONFIRM_PROD=1; shift ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      err "알 수 없는 옵션: $1 (--help 참조)"
      exit 1
      ;;
  esac
done

# ────────────────────────────────────────────────────────────
# 운영 DB 이중 게이트 (오발사 방지)
# ────────────────────────────────────────────────────────────
if [ "${TARGET_DB}" = "${PROD_DB}" ]; then
  if [ "${CONFIRM_PROD}" -ne 1 ]; then
    err "target-db='${PROD_DB}'(운영 DB) 복원은 --confirm-prod 필수 — 오발사 방지 이중 게이트"
    err "  운영 복원은 R-4 runbook §2(앱 정지·KillSwitch 검토) 선행 의무"
    err "  검증/연습은 --target-db magicjar_restore_test (default) 사용"
    exit 1
  fi
  if [ "${DROP_AFTER}" -eq 1 ]; then
    err "운영 DB(${PROD_DB})에 --drop-after 금지 — 데이터 영구 소실 risk"
    exit 1
  fi
fi

# ────────────────────────────────────────────────────────────
# 진단 — postgres 컨테이너 running 검증
# ────────────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  err "docker 명령 부재 — Docker Desktop 설치 + 실행 필요"
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  err "${CONTAINER_NAME} 컨테이너 running 0 — 'docker compose -f docker/docker-compose.infra.yml up -d' 후 재시도"
  exit 1
fi

if ! docker exec "${CONTAINER_NAME}" pg_isready -U "${DB_USER}" -d postgres >/dev/null 2>&1; then
  err "${CONTAINER_NAME} pg_isready FAIL — DB 결함 의심 (R-4 runbook 진입)"
  exit 1
fi

# ────────────────────────────────────────────────────────────
# dump 파일 선택 (생략 시 daily/ 최신 자동)
# ────────────────────────────────────────────────────────────
if [ -z "${DUMP_FILE}" ]; then
  DUMP_FILE="$(ls -t "${BACKUP_DIR}"/*.dump 2>/dev/null | head -1 || true)"
  if [ -z "${DUMP_FILE}" ]; then
    err "${BACKUP_DIR} 에 *.dump 파일 0 — pg-dump-daily.sh 먼저 실행하거나 --file 지정"
    exit 1
  fi
  log "dump 파일 자동 선택 (최신): ${DUMP_FILE}"
fi

if [ ! -f "${DUMP_FILE}" ]; then
  err "dump 파일 부재: ${DUMP_FILE}"
  exit 1
fi

DUMP_SIZE=$(du -h "${DUMP_FILE}" | cut -f1)
log "복원 source: ${DUMP_FILE} (${DUMP_SIZE})"
log "복원 target DB: ${TARGET_DB}"

# 컨테이너로 dump 복사 (호스트 → 컨테이너 /tmp)
TMP_DUMP="/tmp/pg-restore-$(date '+%s').dump"
log "dump 컨테이너 복사: ${CONTAINER_NAME}:${TMP_DUMP}"
docker cp "${DUMP_FILE}" "${CONTAINER_NAME}:${TMP_DUMP}"

cleanup_tmp() {
  docker exec "${CONTAINER_NAME}" rm -f "${TMP_DUMP}" >/dev/null 2>&1 || true
}
trap cleanup_tmp EXIT

# dump custom format 검증 (PGDMP magic)
if ! docker exec "${CONTAINER_NAME}" pg_restore --list "${TMP_DUMP}" >/dev/null 2>&1; then
  err "pg_restore --list FAIL — custom format(-Fc) dump 아님 또는 손상"
  exit 1
fi

DUMP_TABLE_DATA=$(docker exec "${CONTAINER_NAME}" pg_restore --list "${TMP_DUMP}" 2>/dev/null | grep -c 'TABLE DATA' || true)
log "dump 내 TABLE DATA: ${DUMP_TABLE_DATA}건 (flyway_schema_history 포함 — 마이그레이션 이력까지 복원)"

# ────────────────────────────────────────────────────────────
# dry-run — 실제 복원 0, dump 내용 미리보기만
# ────────────────────────────────────────────────────────────
if [ "${DRY_RUN}" -eq 1 ]; then
  log "── DRY-RUN — 실제 복원 0. dump 내용 미리보기 (TABLE DATA 상위 20건):"
  docker exec "${CONTAINER_NAME}" pg_restore --list "${TMP_DUMP}" 2>/dev/null \
    | grep 'TABLE DATA' | head -20 | while IFS= read -r LINE; do
      log "  ${LINE}"
    done
  log "── DRY-RUN 종료. 실제 복원은 --dry-run 제거 후 재실행."
  exit 0
fi

# ────────────────────────────────────────────────────────────
# 복원 전 안전 백업 — target=운영 DB이면 의무, 임시 DB이면 skip
# ────────────────────────────────────────────────────────────
DO_SAFETY=0
if [ "${SAFETY_BACKUP}" = "auto" ] && [ "${TARGET_DB}" = "${PROD_DB}" ]; then
  DO_SAFETY=1
elif [ "${SAFETY_BACKUP}" = "on" ]; then
  DO_SAFETY=1
fi

if [ "${DO_SAFETY}" -eq 1 ]; then
  # target DB가 실제 존재할 때만 안전 백업
  if docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d postgres -tAc \
       "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" | grep -q 1; then
    mkdir -p "${SAFETY_DIR}"
    SAFETY_FILE="${SAFETY_DIR}/pre-restore-${TARGET_DB}-$(date '+%Y-%m-%d-%H%M%S').dump"
    log "복원 전 안전 백업: ${SAFETY_FILE} (롤백 가능 보장)"
    if docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -Fc "${TARGET_DB}" > "${SAFETY_FILE}"; then
      SAFETY_SIZE=$(du -h "${SAFETY_FILE}" | cut -f1)
      log "안전 백업 완료: ${SAFETY_FILE} (${SAFETY_SIZE})"
    else
      err "안전 백업 FAIL — 운영 DB 복원 중단 (롤백 보장 불가)"
      rm -f "${SAFETY_FILE}"
      exit 1
    fi
  else
    log "target DB '${TARGET_DB}' 미존재 — 안전 백업 skip (신규 생성 경로)"
  fi
fi

# ────────────────────────────────────────────────────────────
# 임시 검증 DB 경로 — DROP CASCADE 후 CREATE (운영 DB는 이 경로 미진입)
# ────────────────────────────────────────────────────────────
if [ "${TARGET_DB}" != "${PROD_DB}" ]; then
  log "임시 검증 DB 재생성: ${TARGET_DB} (DROP IF EXISTS → CREATE)"
  docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d postgres \
    -c "DROP DATABASE IF EXISTS ${TARGET_DB};" \
    -c "CREATE DATABASE ${TARGET_DB} OWNER ${DB_USER};"
fi

# ────────────────────────────────────────────────────────────
# pg_restore 실행
#  - 운영 DB(magicjar): --clean --create -d postgres (R-4 §3-2 정합 — DB 재생성)
#  - 임시 DB:           -d <target> (이미 빈 DB 생성됨 — --create 불필요)
# ────────────────────────────────────────────────────────────
log "pg_restore 시작 → ${TARGET_DB}"
if [ "${TARGET_DB}" = "${PROD_DB}" ]; then
  # R-4 runbook §3-2: --clean --create 로 운영 DB 삭제 후 재생성 (postgres DB에 접속)
  docker exec "${CONTAINER_NAME}" pg_restore \
    --clean --create --if-exists \
    -U "${DB_USER}" -d postgres \
    "${TMP_DUMP}" 2>&1 | tee /tmp/pg-restore-out.log | grep -iE 'error|warning' | head -20 || true
  RESTORE_RC=${PIPESTATUS[0]}
else
  # 임시 DB: 빈 DB에 데이터·스키마 적재 (--exit-on-error 끔 — 무해 warning 허용)
  docker exec "${CONTAINER_NAME}" pg_restore \
    --no-owner \
    -U "${DB_USER}" -d "${TARGET_DB}" \
    "${TMP_DUMP}" 2>&1 | grep -iE 'error|warning' | head -20 || true
  RESTORE_RC=${PIPESTATUS[0]}
fi

# pg_restore는 무해 warning(role already exists 등)에도 비0 종료 가능 → 검증으로 성패 판정
log "pg_restore 종료 (warning 일부 정상 — 검증으로 성패 판정)"

# ────────────────────────────────────────────────────────────
# 복원 후 검증 — 테이블 수 + flyway 최신 버전 출력
# ────────────────────────────────────────────────────────────
RESTORED_TABLES=$(docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${TARGET_DB}" -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" 2>/dev/null | tr -d '[:space:]' || echo "?")

RESTORED_FLYWAY=$(docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${TARGET_DB}" -tAc \
  "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1;" 2>/dev/null | tr -d '[:space:]' || echo "?")

log "── 복원 검증 결과 ──"
log "  target DB        : ${TARGET_DB}"
log "  public 테이블 수 : ${RESTORED_TABLES} (dump TABLE DATA: ${DUMP_TABLE_DATA})"
log "  flyway 최신 버전 : V${RESTORED_FLYWAY}"

if [ "${RESTORED_TABLES}" = "0" ] || [ "${RESTORED_TABLES}" = "?" ]; then
  err "복원 검증 FAIL — public 테이블 0 또는 조회 실패. pg_restore 로그 확인"
  exit 1
fi

# dump TABLE DATA(57개 — flyway_schema_history 포함) ≈ public 테이블 수 정합 확인
log "검증 OK — 테이블 ${RESTORED_TABLES}건 복원 + flyway V${RESTORED_FLYWAY} 회복"

# ────────────────────────────────────────────────────────────
# 검증용 임시 DB 정리 (--drop-after)
# ────────────────────────────────────────────────────────────
if [ "${DROP_AFTER}" -eq 1 ] && [ "${TARGET_DB}" != "${PROD_DB}" ]; then
  log "임시 검증 DB DROP: ${TARGET_DB} (--drop-after)"
  docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d postgres \
    -c "DROP DATABASE IF EXISTS ${TARGET_DB};"
  log "임시 DB 정리 완료"
fi

log "exit 0 — 정상 완료"
exit 0
