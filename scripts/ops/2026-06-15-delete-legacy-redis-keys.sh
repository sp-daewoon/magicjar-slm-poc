#!/usr/bin/env bash
set -euo pipefail

# Delete legacy Redis keys after the production wire-key cutover.
#
# Defaults:
#   DRY_RUN=1                  print candidates only
#   REDIS_CONTAINER=magicjar-redis  use docker exec against the local Redis container
#
# Examples:
#   scripts/ops/2026-06-15-delete-legacy-redis-keys.sh
#   DRY_RUN=0 scripts/ops/2026-06-15-delete-legacy-redis-keys.sh
#   REDIS_URL=redis://localhost:6379 DRY_RUN=0 scripts/ops/2026-06-15-delete-legacy-redis-keys.sh

DRY_RUN="${DRY_RUN:-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-magicjar-redis}"

redis_cli() {
  if [[ -n "${REDIS_URL:-}" ]]; then
    redis-cli -u "${REDIS_URL}" "$@"
  elif [[ -n "${REDIS_CONTAINER}" ]]; then
    docker exec "${REDIS_CONTAINER}" redis-cli "$@"
  else
    redis-cli "$@"
  fi
}

should_skip_standard_key() {
  local key="$1"
  case "${key}" in
    account:availability:user:*|\
    account:balance:user:*|\
    instrument:list:market:*|\
    screener:daytrade:preset:*|\
    news:dedup:url:sha256:*|\
    notification:dedup:event:*|\
    notification:dedup:order:user:*|\
    notification:ratelimit:user:*|\
    notification:once:user:*|\
    ai:prompt:cache:sha256:*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

patterns=(
  "access_token:*"
  "kis:token:*"
  "kis:token:lock:*"
  "kis:approval:*"
  "kis:approval:lock:*"
  "kis:key-changed-lock:*"
  "account:balance:*"
  "account:balance:lock:*"
  "account:availability:*"
  "position:current:*"
  "quote:last:*"
  "orderbook:top:*"
  "candle:current:*"
  "candle:today:*"
  "candle:backfill:guard:*"
  "candle:universe:*"
  "meta:*"
  "session:cache:current"
  "strategy:active:by_code:*"
  "signal:dedupe:*"
  "risk:trade-freq:*"
  "daily_pnl:*"
  "instrument:by-code:*"
  "instrument:list:*"
  "instrument:sync:running"
  "instrument:sync:lastAt"
  "indicator:cache:*"
  "pattern:cache:*"
  "perf:summary:*"
  "perf:equity:*"
  "screener:cache:*"
  "screener:lock:*"
  "screener:daytrade:*"
  "news:ratelimit:naver"
  "news:dedup:rcept:*"
  "news:dedup:url:*"
  "notification:dedup:*"
  "notification:ratelimit:*"
  "notification:once:*"
  "admin:realtime-status"
  "watchlist:source:*"
  "reconcile:lock:*"
  "reconcile:lastrun:*"
  "ai:prompt:cache:*"
)

tmp="$(mktemp)"
trap 'rm -f "${tmp}" "${tmp}.sorted"' EXIT

for pattern in "${patterns[@]}"; do
  while IFS= read -r key; do
    [[ -z "${key}" ]] && continue
    if should_skip_standard_key "${key}"; then
      continue
    fi
    printf '%s\n' "${key}" >> "${tmp}"
  done < <(redis_cli --scan --pattern "${pattern}")
done

sort -u "${tmp}" > "${tmp}.sorted"
candidate_count="$(wc -l < "${tmp}.sorted" | tr -d ' ')"

echo "legacy redis key candidates: ${candidate_count}"

if [[ "${candidate_count}" == "0" ]]; then
  exit 0
fi

if [[ "${DRY_RUN}" != "0" ]]; then
  sed -n '1,200p' "${tmp}.sorted"
  if [[ "${candidate_count}" -gt 200 ]]; then
    echo "... truncated. rerun with DRY_RUN=0 to delete."
  fi
  exit 0
fi

deleted=0
batch=()
while IFS= read -r key; do
  batch+=("${key}")
  if [[ "${#batch[@]}" -ge 100 ]]; then
    count="$(redis_cli DEL "${batch[@]}" | tr -d '\r')"
    deleted=$((deleted + count))
    batch=()
  fi
done < "${tmp}.sorted"

if [[ "${#batch[@]}" -gt 0 ]]; then
  count="$(redis_cli DEL "${batch[@]}" | tr -d '\r')"
  deleted=$((deleted + count))
fi

echo "legacy redis keys deleted: ${deleted}"
