#!/usr/bin/env bash
# magicJar — 호스트 빌드 + docker compose 재기동 한 줄 헬퍼.
#
# 왜 필요?
#   api/batch/consumer Dockerfile은 단순 `COPY build/libs/<m>-*.jar /app/app.jar`로
#   호스트 산출물을 그대로 가져온다. `docker compose build`만으로는 gradle이 안 돌아
#   옛 jar가 컨테이너에 들어간다 (운영 함정 가드 — smoke.sh staleness check와 짝).
#
# 사용:
#   scripts/build-and-deploy.sh                # 전체 (gradle + docker build + up -d)
#   scripts/build-and-deploy.sh api frontend   # 일부 서비스만
#   scripts/build-and-deploy.sh --gradle-only  # gradle만 (jar만 갱신)
#   scripts/build-and-deploy.sh --no-gradle    # docker compose만 (이미 jar 최신일 때)
#   scripts/build-and-deploy.sh claude-bridge  # Claude 종목발굴 브리지만 호스트에서 기동
#
# 기본 동작: docker-compose.yml + docker-compose.dev.yml override + --env-file .env.
# default profile = dev,real-kis (memory feedback_no_mock_data.md 정합).
# mock profile은 jvm test fixture 전용 — 운영/사용자 흐름에서 사용 안 함.

set -euo pipefail
cd "$(dirname "$0")/.."

ALL_JVM=(api batch consumer)
# 빌드+up 대상. dozzle·kafka-ui는 image-only(외부)라 build는 noop, up만 동작.
ALL_SERVICES=(api-app batch-app consumer-app frontend dozzle kafka-ui)

GRADLE=1
DOCKER=1
SERVICES=()
START_CLAUDE_BRIDGE=0
NO_CLAUDE_BRIDGE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle-only)  DOCKER=0; shift ;;
    --no-gradle)    GRADLE=0; shift ;;
    --no-claude-bridge) NO_CLAUDE_BRIDGE=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    claude-bridge|claude-discovery-bridge)
      START_CLAUDE_BRIDGE=1
      shift
      ;;
    api) SERVICES+=(api-app); shift ;;
    batch) SERVICES+=(batch-app); shift ;;
    consumer) SERVICES+=(consumer-app); shift ;;
    *) SERVICES+=("$1"); shift ;;
  esac
done

# Compose 명령 합성: --env-file을 **절대 경로**로 명시 (compose의 working dir이 docker/로
# 잡혀 `.env`를 못 찾는 함정 회피). --project-directory는 쓰면 build.context: ../batch
# 해석이 root 기준으로 바뀌어 `/Users/<user>/batch`로 잘못 잡힘 (사용자 보고 2026-05-05).
COMPOSE_ARGS=(--env-file "$PWD/.env" -f docker/docker-compose.yml -f docker/docker-compose.dev.yml)

# 서비스 미지정 시 전체. 단, claude-bridge만 요청한 경우는 호스트 브리지 단독 실행.
if [[ ${#SERVICES[@]} -eq 0 && $START_CLAUDE_BRIDGE -eq 0 ]]; then
  SERVICES=("${ALL_SERVICES[@]}")
  if [[ $DOCKER -eq 1 && $NO_CLAUDE_BRIDGE -eq 0 ]]; then
    START_CLAUDE_BRIDGE=1
  fi
fi

if [[ $NO_CLAUDE_BRIDGE -eq 0 && ${#SERVICES[@]} -gt 0 ]]; then
  for s in "${SERVICES[@]}"; do
    case "$s" in
      api-app)
        if [[ $DOCKER -eq 1 ]]; then
          START_CLAUDE_BRIDGE=1
        fi
        ;;
    esac
  done
fi

start_claude_bridge() {
  local host="${CLAUDE_DISCOVERY_BRIDGE_HOST:-127.0.0.1}"
  local port="${CLAUDE_DISCOVERY_BRIDGE_PORT:-8787}"
  local command="${CLAUDE_DISCOVERY_COMMAND:-claude}"
  local max_timeout="${CLAUDE_DISCOVERY_MAX_TIMEOUT_SECONDS:-300}"
  local health_url="http://${host}:${port}/health"
  local log_dir="$PWD/_workspace/logs"
  local run_dir="$PWD/_workspace/run"
  local log_file="${log_dir}/claude-discovery-bridge.log"
  local pid_file="${run_dir}/claude-discovery-bridge.pid"

  echo "▶ claude discovery bridge: ${health_url}"

  if curl -fsS "$health_url" >/dev/null 2>&1; then
    echo "  already UP"
    return 0
  fi

  if ! command -v node >/dev/null 2>&1; then
    echo "  skip — node 명령을 찾을 수 없습니다"
    return 0
  fi

  if ! command -v "$command" >/dev/null 2>&1; then
    echo "  skip — ${command} 명령을 찾을 수 없습니다"
    return 0
  fi

  mkdir -p "$log_dir" "$run_dir"
  CLAUDE_DISCOVERY_BRIDGE_HOST="$host" \
    CLAUDE_DISCOVERY_BRIDGE_PORT="$port" \
    CLAUDE_DISCOVERY_COMMAND="$command" \
    CLAUDE_DISCOVERY_MAX_TIMEOUT_SECONDS="$max_timeout" \
    nohup node scripts/claude-discovery-bridge.mjs >> "$log_file" 2>&1 < /dev/null &
  local bridge_pid=$!
  echo "$bridge_pid" > "$pid_file"
  disown "$bridge_pid" 2>/dev/null || true

  for _ in {1..20}; do
    if curl -fsS "$health_url" >/dev/null 2>&1; then
      echo "  started pid=$(cat "$pid_file") log=${log_file}"
      return 0
    fi
    sleep 0.25
  done

  echo "  start requested but health check failed — log=${log_file}"
  return 1
}

# 1) gradle bootJar — JVM 모듈만 (api-app→api, batch-app→batch, consumer-app→consumer)
if [[ $GRADLE -eq 1 ]]; then
  jvm_modules=()
  if [[ ${#SERVICES[@]} -gt 0 ]]; then
    for s in "${SERVICES[@]}"; do
      case "$s" in
        api-app)      jvm_modules+=(:api:bootJar) ;;
        batch-app)    jvm_modules+=(:batch:bootJar) ;;
        consumer-app) jvm_modules+=(:consumer:bootJar) ;;
      esac
    done
  fi
  if [[ ${#jvm_modules[@]} -gt 0 ]]; then
    echo "▶ gradle: ${jvm_modules[*]}"
    ./gradlew "${jvm_modules[@]}"
  else
    echo "▶ gradle: (JVM 서비스 없음 — skip)"
  fi
fi

# 1.5) ADR 0115 §2-P2-④ — consumer 재배포 전 in-flight 주문 drain 가드.
#   consumer-app 재배포 대상이면 in-flight(REQUESTED·ACK·PARTIALLY_FILLED) 주문 0 확인.
#   > 0 이면 체결통지 유실 risk → 중단(우회: SKIP_DRAIN_CHECK=1). 비활성: MAGICJAR_DEPLOY_DRAIN_INFLIGHT_CHECK_ENABLED=false.
if [[ $DOCKER -eq 1 && ${#SERVICES[@]} -gt 0 ]]; then
  for s in "${SERVICES[@]}"; do
    if [[ "$s" == "consumer-app" ]]; then
      echo "▶ consumer 재배포 — in-flight drain 가드 (ADR 0115 §2-P2-④)"
      scripts/check-inflight-drain.sh
      break
    fi
  done
fi

# 2) docker compose build + up -d
if [[ $DOCKER -eq 1 && ${#SERVICES[@]} -gt 0 ]]; then
  echo "▶ profile: dev,real-kis (default — mock 부팅 path 폐기)"

  echo "▶ docker compose build: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" build "${SERVICES[@]}"

  echo "▶ docker compose up -d: ${SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d "${SERVICES[@]}"

  echo "▶ healthy 대기 (~60s)…"
  sleep 30
  docker compose "${COMPOSE_ARGS[@]}" ps --format "{{.Service}}\t{{.Status}}"
elif [[ $DOCKER -eq 1 ]]; then
  echo "▶ docker compose: (Docker 서비스 없음 — skip)"
fi

if [[ $START_CLAUDE_BRIDGE -eq 1 && $NO_CLAUDE_BRIDGE -eq 0 ]]; then
  start_claude_bridge
fi

echo "✓ done"
