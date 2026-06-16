#!/usr/bin/env bash
# magicJar worktree config 1회 적용 + 기존 worktree config.worktree 발행 보강
# (헌법 §1-A-extended 정합)
#
# 목적:
#   1. extensions.worktreeConfig=true 활성화 — worktree 간 `.git/config` 공유 차단
#      → 한 worktree의 `git config --local` 설정이 main 또는 다른 worktree에 전파되지 않도록 격리
#   2. 기존 모든 worktree에 `.git/worktrees/<name>/config.worktree` 파일 보장 발행
#      → 각 worktree author 격리 LOCK (라운드 3 evidence — extensions 활성만으로는 부족)
#
# root cause (안정성 Cycle 3·4 — 2026-05-19, 8 sub-agent system audit + 라운드 2 dispatch evidence):
#   - 기본 상태(extensions.worktreeConfig=false)에서 worktree는 main `.git/config`를 공유
#   - 한 worktree에서 `git config user.email` 호출 시 → main `.git/config` 흡수
#   - 결과: 다른 worktree·sub-agent에 휘발성 전파 → author 결함 PR 10+건 누적
#   - 헌법 §1-A 호출만으로 차단 불가 — 본 옵션 활성화가 본질
#   - 그러나 옵션 활성만으로도 부족 — 각 worktree에 config.worktree 파일이 발행되어야
#     `git config --worktree` 호출이 본 worktree-local로 격리됨
#   - 라운드 3 evidence: PR #562·#564·#565 모두 author 결함 발생 — extensions 활성 상태에서도
#     config.worktree 파일 미발행 시 fallback path 모호함 → 명시 발행 헬퍼 의무
#
# 검증:
#   1. `git config extensions.worktreeConfig` = true
#   2. 각 worktree의 `.git/worktrees/<name>/config.worktree` 파일 존재
#
# 사용법:
#   scripts/setup-worktree-config.sh                # main + 모든 기존 worktree 일괄
#   scripts/setup-worktree-config.sh --extensions-only   # main extensions만 (구버전 호환)

set -euo pipefail

EXTENSIONS_ONLY=false
if [ "${1:-}" = "--extensions-only" ]; then
  EXTENSIONS_ONLY=true
fi

# Repo root + main common dir 확인
COMMON_GIT_DIR="$(git rev-parse --git-common-dir)"
if [ -z "$COMMON_GIT_DIR" ]; then
  echo "✗ git repository가 아닙니다 (git rev-parse --git-common-dir 실패)" >&2
  exit 1
fi

# 절대경로 변환 (worktree 안에서 호출 시 .git/worktrees/<name>/.. 상대경로 회피)
COMMON_GIT_DIR="$(cd "$COMMON_GIT_DIR" && pwd)"
MAIN_CONFIG_FILE="${COMMON_GIT_DIR}/config"

echo "=== Step 1/2: main extensions.worktreeConfig=true ==="

# 기존 값 확인
CURRENT_VAL="$(git config --file "$MAIN_CONFIG_FILE" --get extensions.worktreeConfig 2>/dev/null || echo "")"
if [ "$CURRENT_VAL" = "true" ]; then
  echo "✓ extensions.worktreeConfig=true 이미 적용됨 ($MAIN_CONFIG_FILE)"
else
  git config --file "$MAIN_CONFIG_FILE" extensions.worktreeConfig true
  echo "✓ extensions.worktreeConfig=true 적용 (헌법 §1-A-extended 정합)"
  echo "  대상 파일: $MAIN_CONFIG_FILE"
fi

# 검증
VAL="$(git config --file "$MAIN_CONFIG_FILE" --get extensions.worktreeConfig)"
if [ "$VAL" != "true" ]; then
  echo "✗ 검증 FAIL: extensions.worktreeConfig=$VAL" >&2
  exit 1
fi

if [ "$EXTENSIONS_ONLY" = "true" ]; then
  echo ""
  echo "✓ Step 1만 실행 (--extensions-only)"
  exit 0
fi

echo ""
echo "=== Step 2/2: 기존 worktree config.worktree 발행 보강 ==="

# worktree 디렉토리 목록 (main 자체는 .git/worktrees/ 하위 아님 — `git worktree list` 첫 row가 main)
WORKTREES_DIR="${COMMON_GIT_DIR}/worktrees"

if [ ! -d "$WORKTREES_DIR" ]; then
  echo "  worktree 디렉토리 부재 — main repo 단독 ($WORKTREES_DIR)"
  echo "✓ Step 2 skip (worktree 0건)"
  exit 0
fi

PUBLISHED_COUNT=0
EXISTING_COUNT=0
TOTAL_COUNT=0

for wt_dir in "$WORKTREES_DIR"/*/; do
  [ -d "$wt_dir" ] || continue
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  wt_name="$(basename "$wt_dir")"
  config_file="${wt_dir}config.worktree"

  if [ ! -f "$config_file" ]; then
    # 빈 파일로 발행 (git가 자동 채움)
    touch "$config_file"
    echo "✓ $wt_name → config.worktree 신규 발행"
    PUBLISHED_COUNT=$((PUBLISHED_COUNT + 1))
  else
    echo "  $wt_name → config.worktree 이미 존재"
    EXISTING_COUNT=$((EXISTING_COUNT + 1))
  fi
done

echo ""
echo "=== 검증 GREEN — worktree config 격리 활성 ==="
echo "  대상 worktree 총 $TOTAL_COUNT 건"
echo "  config.worktree 신규 발행: $PUBLISHED_COUNT 건"
echo "  config.worktree 이미 존재: $EXISTING_COUNT 건"
echo ""
echo "다음 단계 (각 worktree에서 sub-agent 진입 시):"
echo "  git config user.email \"bot+<agent>@magicjar.local\""
echo "  git config user.name \"magicjar-<agent>\""
echo "  git config --worktree user.email \"bot+<agent>@magicjar.local\"   # 명시 worktree-local LOCK"
echo "  git config --worktree user.name \"magicjar-<agent>\""
echo ""
echo "검증 명령 (sub-agent 첫 commit 직전):"
echo "  git config --get --worktree user.email   # 본 worktree-local author 확인"
