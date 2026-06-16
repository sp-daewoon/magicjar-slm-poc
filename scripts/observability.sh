#!/usr/bin/env bash
# magicJar — 관측 스택(Prometheus + Grafana) 기동 한 줄 헬퍼.
#
# 왜 필요?
#   docker-compose.observability.yml 을 손으로 호출하면 cwd가 docker/ 로 바뀔 때
#   `.env`를 못 찾는 함정(운영 함정 가드 §2)에 빠진다. 이 스크립트는
#     --env-file <절대경로 .env>  +  -f <절대경로 compose 파일>
#   조합으로 항상 repo root 기준 호출을 강제한다.
#   (--project-directory 는 build.context: ../batch 해석이 root 기준으로 틀어져
#    쓰지 않는다 — build-and-deploy.sh 와 동일 정책.)
#
# 사용:
#   scripts/observability.sh up       # Prometheus + Grafana 기동 (-d)
#   scripts/observability.sh down      # 중지 + 컨테이너 제거 (볼륨 유지)
#   scripts/observability.sh status    # 컨테이너/healthcheck 상태
#   scripts/observability.sh -h        # 도움말
#
# 전제: 관측 스택은 api·batch·consumer 의 /actuator/prometheus 를 scrape 한다.
#   scrape 대상이 떠 있어야 데이터가 보인다 —
#     · docker 통합 기동:  scripts/build-and-deploy.sh
#     · IDE bootRun:       docker compose -f docker/docker-compose.infra.yml up -d  +  ./gradlew :api:bootRun …
#   둘 다 안 떠 있으면 Prometheus target 은 자연스럽게 DOWN(에러 0).

set -Eeuo pipefail
cd "$(dirname "$0")/.."

REPO_ROOT="$PWD"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.observability.yml"

PROMETHEUS_PORT="${PROMETHEUS_HOST_PORT:-9090}"
GRAFANA_PORT="${GRAFANA_HOST_PORT:-3001}"

# ── 로그 함수 (build-and-deploy.sh 스타일) ──────────────────────────
c_reset=$'\033[0m'; c_blue=$'\033[34m'; c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'
log()  { printf '%s[obs]%s %s\n' "$c_blue" "$c_reset" "$*"; }
ok()   { printf '%s[obs ✓]%s %s\n' "$c_green" "$c_reset" "$*"; }
warn() { printf '%s[obs !]%s %s\n' "$c_yellow" "$c_reset" "$*"; }
die()  { printf '%s[obs ✗]%s %s\n' "$c_red" "$c_reset" "$*" >&2; exit 1; }

[[ -f "$COMPOSE_FILE" ]] || die "compose 파일 없음: $COMPOSE_FILE"

# compose 호출 직전에만 .env 유무를 평가 (help/unknown 커맨드에서 경고 노이즈 회피).
compose() {
  local env_args=()
  if [[ -f "$ENV_FILE" ]]; then
    env_args=(--env-file "$ENV_FILE")
  else
    warn ".env 없음 ($ENV_FILE) — default 포트(Prometheus :$PROMETHEUS_PORT · Grafana :$GRAFANA_PORT)로 진행"
  fi
  docker compose "${env_args[@]}" -f "$COMPOSE_FILE" "$@"
}

print_access() {
  cat <<EOF

  ${c_green}접속 URL${c_reset}
    Prometheus   http://localhost:${PROMETHEUS_PORT}        (targets · metric explorer)
    Grafana      http://localhost:${GRAFANA_PORT}           (admin / \${GRAFANA_ADMIN_PASSWORD:-admin})
      └ 대시보드: magicJar / "magicJar — KIS Rate Limit" (uid magicjar-kis-ratelimit)
                  magicJar / "magicJar — SLO (ADR 0095)"
                  magicJar / "magicJar — JVM Baseline"

  ${c_yellow}scrape 전제${c_reset}: api·batch·consumer 가 떠 있어야 metric 이 채워집니다.
    docker 기동:   scripts/build-and-deploy.sh
    bootRun:       docker compose -f docker/docker-compose.infra.yml up -d  +  ./gradlew :api:bootRun
EOF
}

cmd="${1:-}"
case "$cmd" in
  up)
    log "관측 스택 기동 — Prometheus + Grafana (-d)"
    compose up -d
    ok "컨테이너 기동 요청 완료 (healthcheck 수렴까지 ~20s)"
    print_access
    ;;
  down)
    log "관측 스택 중지 + 컨테이너 제거 (볼륨 prometheus_data·grafana_data 는 유지)"
    compose down
    ok "중지 완료"
    ;;
  status)
    log "관측 스택 컨테이너 상태"
    compose ps
    ;;
  -h|--help|help|"")
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    die "알 수 없는 서브커맨드: $cmd (up|down|status|-h)"
    ;;
esac
