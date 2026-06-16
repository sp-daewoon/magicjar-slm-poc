#!/usr/bin/env bash
# magicJar — 장중 consumer 재배포 in-flight 주문 drain 가드 (ADR 0115 §2-P2-④ / spec §5-2 · development-rules §3-7).
#
# 왜 필요?
#   in-flight 주문(REQUESTED·ACK·PARTIALLY_FILLED)이 있는 consumer 를 재배포하면 체결통지 유실 risk
#   (MOCK 은 WS 미지원이라 부팅 backfill 만이 복구 경로 — G1 직접 원인: AUTO SELL 발사 직후 재배포로 다운).
#   본 가드는 그 윈도를 줄이는 보강(완전 방지 아님 — 부팅 backfill 과 병행).
#
# 동작:
#   order_request 중 status IN (REQUESTED, ACK, PARTIALLY_FILLED, PENDING_RECONCILE) count 조회.
#   > 0 이면 경고 + exit 1 (배포 중단). 0 이면 exit 0 (배포 진행).
#
# 우회:
#   SKIP_DRAIN_CHECK=1 scripts/build-and-deploy.sh consumer   # 명시 force (drain 무시)
#
# 비활성:
#   MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED=false        # 가드 자체 off (ADR rollback)
#
# DB 좌표는 .env 의 POSTGRES_* / SPRING_DATASOURCE_* 에서 자동 해석 (psql 사용).

set -euo pipefail
cd "$(dirname "$0")/.."

# ── 토글 ──────────────────────────────────────────────────────────────────
if [[ "${MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED:-true}" == "false" ]]; then
  echo "▶ drain 가드 비활성(MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED=false) — skip"
  exit 0
fi
if [[ "${SKIP_DRAIN_CHECK:-0}" == "1" ]]; then
  echo "▶ drain 가드 우회(SKIP_DRAIN_CHECK=1) — in-flight 주문 무시하고 진행"
  exit 0
fi

# ── .env 로드 (DB 좌표) ───────────────────────────────────────────────────
# source 금지 — .env 는 docker compose 파서 기준이라 공백 포함 무인용 값
# (예: cron 표현식)이 존재하며 bash source 가 이를 명령으로 오해석 (2026-06-10 배포 중단 evidence).
env_lookup() {
  [[ -f .env ]] || return 0
  # 키 부재 시 grep exit 1 이 set -e 로 전파되지 않도록 무실패 보장
  { grep -E "^${1}=" .env || true; } | tail -1 | cut -d= -f2- | tr -d '"' | tr -d "'"
}

PGHOST="${POSTGRES_HOST:-$(env_lookup POSTGRES_HOST)}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${POSTGRES_PORT:-$(env_lookup POSTGRES_PORT)}"
PGPORT="${PGPORT:-5432}"
PGDB="${POSTGRES_DB:-$(env_lookup POSTGRES_DB)}"
PGDB="${PGDB:-magicjar}"
PGUSER="${POSTGRES_USER:-$(env_lookup POSTGRES_USER)}"
PGUSER="${PGUSER:-magicjar}"
PGPASSWORD="${POSTGRES_PASSWORD:-$(env_lookup POSTGRES_PASSWORD)}"
PGPASSWORD="${PGPASSWORD:-magicjar}"
export PGPASSWORD

SQL="SELECT COUNT(*) FROM order_request WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED','PENDING_RECONCILE');"

query_inflight() {
  # 호스트 psql 우선, 없으면 postgres 컨테이너 경유 (개발 호스트 표준 — psql 미설치)
  if command -v psql >/dev/null 2>&1; then
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDB" -tAc "$SQL" 2>/dev/null || echo "ERR"
  elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^magicjar-postgres$'; then
    docker exec magicjar-postgres psql -U "$PGUSER" -d "$PGDB" -tAc "$SQL" 2>/dev/null || echo "ERR"
  else
    echo "SKIP"
  fi
}

INFLIGHT="$(query_inflight)"

if [[ "$INFLIGHT" == "SKIP" ]]; then
  echo "▶ drain 가드 skip — psql/postgres 컨테이너를 찾을 수 없습니다(가드 미적용으로 진행)"
  exit 0
fi
if [[ "$INFLIGHT" == "ERR" ]]; then
  echo "▶ drain 가드 — order_request 조회 실패(DB 미가동 추정) — 가드 미적용으로 진행"
  exit 0
fi

# in-flight > 0 — 즉시 차단하지 않고 drain 대기 (in-flight 는 보통 수 초 내 체결/거부로 해소).
# 치명 결함 긴급 배포가 "무조건 차단"에 막히지 않도록: 대기 → 자동 해소 시 진행, 시한 초과 시에만 중단+우회 안내.
DRAIN_WAIT_SECONDS="${DRAIN_WAIT_SECONDS:-60}"
POLL_INTERVAL=5
waited=0
while [[ "${INFLIGHT:-0}" -gt 0 && "$waited" -lt "$DRAIN_WAIT_SECONDS" ]]; do
  echo "▶ in-flight 주문 ${INFLIGHT}건 — drain 대기 중 (${waited}s/${DRAIN_WAIT_SECONDS}s)…"
  sleep "$POLL_INTERVAL"
  waited=$((waited + POLL_INTERVAL))
  INFLIGHT="$(query_inflight)"
  [[ "$INFLIGHT" == "ERR" || "$INFLIGHT" == "SKIP" ]] && INFLIGHT=0
done

if [[ "${INFLIGHT:-0}" -gt 0 ]]; then
  echo "✗ in-flight 주문 ${INFLIGHT}건이 ${DRAIN_WAIT_SECONDS}s 내 해소되지 않음 — consumer 재배포 중단(체결통지 유실 risk)" >&2
  echo "  긴급 배포(치명 결함)면: SKIP_DRAIN_CHECK=1 scripts/build-and-deploy.sh …  (부팅 backfill 이 체결 회복)" >&2
  echo "  대기 연장: DRAIN_WAIT_SECONDS=300 …" >&2
  exit 1
fi

echo "▶ drain 가드 통과 — in-flight 주문 0건 (대기 ${waited}s)"
exit 0
