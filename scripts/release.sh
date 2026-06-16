#!/usr/bin/env bash
set -euo pipefail

# magicJar release.sh — GitHub Actions 없이 tag → GitHub Release 자동 승격
#
# 사용 메모리 정합 (feedback_no_github_actions.md):
#   GHA workflow 폐기 → 로컬 명령으로 release 자동화
#
# 절차 (ADR 0052 §X release notes 작성 절차):
#   1. tag push 확인 (이미 push됨 가정)
#   2. release/vX.Y.Z brunch 확인 (ADR 0031 immutable baseline)
#   3. CHANGELOG.md에서 해당 버전 row 추출 → Hero
#   4. git log <prev>..<this> --oneline → PR 시계열
#   5. git diff <prev>..<this> -- _workspace/arch/adr/ → 신규 ADR
#   6. git diff <prev>..<this> --name-only → 운영 영향 (Flyway·Kafka·STOMP·REST)
#   7. _workspace/baseline/<minor.0>/ 존재 시 baseline 링크 add
#   8. gh release create vX.Y.Z --notes "..." (--edit 모드 시 update)
#
# 사용법:
#   scripts/release.sh v3.5.0
#   scripts/release.sh --backfill         # 누락된 tag 전부 backfill
#   scripts/release.sh --backfill --edit  # 기존 release 전부 풍부 본문으로 update
#   scripts/release.sh v3.5.0 --edit      # 단일 release update
#   scripts/release.sh --backfill --dry-run

usage() {
  cat <<EOF
Usage:
  scripts/release.sh <version> [--edit]       # 단일 GitHub Release 발행 또는 update
  scripts/release.sh --backfill [--edit]      # 누락된 tag 전부 backfill / 기존 update
  scripts/release.sh --help

Example:
  scripts/release.sh v3.5.0
  scripts/release.sh v3.5.0 --edit
  scripts/release.sh --backfill --edit
  scripts/release.sh --backfill --dry-run
EOF
}

# prev_tag 추정 — 직전 semver tag (vX.Y.Z 패턴)
find_prev_tag() {
  local version="$1"
  local prev
  prev=$(git tag -l 'v*.*.*' | sort -V | awk -v v="$version" '$0==v{print prev; exit} {prev=$0}')
  echo "${prev:-}"
}

changelog_sources() {
  echo "CHANGELOG.md"
  find _workspace/changelog -maxdepth 1 -type f -name 'CHANGELOG-*.md' 2>/dev/null | sort -r || true
}

# CHANGELOG row 추출 (Hero 후보)
extract_changelog_row() {
  local version="$1"
  local source
  while IFS= read -r source; do
    [[ -f "$source" ]] || continue
    local row
    row=$(grep -E "release: ${version}\b" "$source" | head -1 | sed 's/^| [0-9-]\{10\} | //; s/ | release | .*$//' || true)
    if [[ -n "$row" ]]; then
      echo "$row"
      return
    fi
  done < <(changelog_sources)
  echo ""
}

# PR 시계열 — git log <prev>..<this> --oneline (PR # 추출)
extract_pr_timeline() {
  local prev="$1"
  local this="$2"
  if [[ -z "$prev" ]] || ! git rev-parse "$prev" >/dev/null 2>&1; then
    echo "(이전 tag 미식별 — git log $this --oneline -10 참조)"
    return
  fi
  git log "${prev}..${this}" --oneline --no-merges 2>/dev/null | head -30 | \
    awk '{
      msg = ""
      for (i=2; i<=NF; i++) msg = msg " " $i
      sub(/^ /, "", msg)
      printf "- `%s` %s\n", $1, msg
    }' || echo "(PR 시계열 추출 실패)"
}

# 신규 ADR — git diff --name-only --diff-filter=A 으로 새로 추가된 ADR
extract_new_adrs() {
  local prev="$1"
  local this="$2"
  if [[ -z "$prev" ]] || ! git rev-parse "$prev" >/dev/null 2>&1; then
    echo "(이전 tag 미식별 — _workspace/arch/adr/README.md 참조)"
    return
  fi
  local adrs
  adrs=$(git diff --name-only --diff-filter=A "${prev}..${this}" -- '_workspace/arch/adr/*.md' 2>/dev/null | grep -v README.md || true)
  if [[ -z "$adrs" ]]; then
    echo "(신규 ADR 0건)"
    return
  fi
  while IFS= read -r adr; do
    [[ -z "$adr" ]] && continue
    local fname
    fname=$(basename "$adr")
    local title
    title=$(git show "${this}:${adr}" 2>/dev/null | head -1 | sed 's/^# //' || echo "$fname")
    echo "- [\`${fname}\`](./${adr}) — ${title}"
  done <<< "$adrs"
}

# 운영 영향 — Flyway·Kafka·STOMP·REST endpoint
extract_ops_impact() {
  local prev="$1"
  local this="$2"
  if [[ -z "$prev" ]] || ! git rev-parse "$prev" >/dev/null 2>&1; then
    echo "(이전 tag 미식별 — 운영 영향 분석 skip)"
    return
  fi
  local changed_files
  changed_files=$(git diff --name-only "${prev}..${this}" 2>/dev/null || true)

  # Flyway 마이그레이션 신규
  local flyway
  flyway=$(echo "$changed_files" | grep -E 'db/migration/V[0-9]+' | grep -v __ | sort -u | head -10 || true)
  if [[ -n "$flyway" ]]; then
    echo "- **Flyway 마이그레이션**:"
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      local bn
      bn=$(basename "$f" .sql)
      echo "  - \`${bn}\`"
    done <<< "$flyway"
  fi

  # Kafka 토픽 (.kt·.yml diff에서 'X.Y.vN' 패턴)
  local kafka_hint
  kafka_hint=$(git diff "${prev}..${this}" -- '*.kt' '*.yml' '*.properties' 2>/dev/null | \
    grep -E '^\+.*(market\.|signal\.|risk\.|order\.|backtest\.).*\.v[0-9]' | \
    grep -oE '[a-z]+\.[a-z._]+\.v[0-9]' | sort -u | head -10 || true)
  if [[ -n "$kafka_hint" ]]; then
    echo "- **Kafka 토픽 변경 hint**:"
    while IFS= read -r t; do
      [[ -z "$t" ]] && continue
      echo "  - \`${t}\`"
    done <<< "$kafka_hint"
  fi

  # STOMP 토픽 (`/topic/...`)
  local stomp_hint
  stomp_hint=$(git diff "${prev}..${this}" -- '*.kt' '*.ts' '*.tsx' 2>/dev/null | \
    grep -E '^\+.*"/topic/' | \
    grep -oE '/topic/[a-zA-Z._{}]+' | sort -u | head -10 || true)
  if [[ -n "$stomp_hint" ]]; then
    echo "- **STOMP 토픽 변경 hint**:"
    while IFS= read -r t; do
      [[ -z "$t" ]] && continue
      echo "  - \`${t}\`"
    done <<< "$stomp_hint"
  fi

  # REST endpoint 추가 (@GetMapping/@PostMapping/@RequestMapping diff)
  local rest_count
  rest_count=$(git diff "${prev}..${this}" -- '*Controller.kt' 2>/dev/null | \
    grep -E '^\+[^+].*@(Get|Post|Put|Delete|Patch|Request)Mapping' | wc -l | tr -d ' ')
  rest_count="${rest_count:-0}"
  if [[ "$rest_count" -gt 0 ]] 2>/dev/null; then
    echo "- **REST endpoint 추가**: ${rest_count}건 (Controller diff 기준)"
  fi
}

# baseline 링크 (minor.0 폴더 있으면)
extract_baseline_link() {
  local version="$1"
  local minor
  minor=$(echo "$version" | sed -E 's/(v[0-9]+\.[0-9]+)\.[0-9]+/\1.0/')
  local baseline_dir="_workspace/baseline/${minor}"
  if [[ -d "$baseline_dir" ]]; then
    echo "- [\`${baseline_dir}/README.md\`](./${baseline_dir}/README.md) — 7 md 풀세트"
    if [[ -d "${baseline_dir}/html" ]] && [[ -f "${baseline_dir}/html/index.html" ]]; then
      echo "- [\`${baseline_dir}/html/index.html\`](./${baseline_dir}/html/index.html) — Mermaid 브라우저 렌더"
    fi
    if [[ "$minor" != "${version}" ]]; then
      echo ""
      echo "> 본 ${version}은 \`${minor}\` minor 사이클의 baseline을 forward 인용 (patch 컷 — baseline immutable)."
    fi
  fi
}

# 사용자 dogfooding 가이드 — CHANGELOG row 기반 트랙·키워드 추출
extract_dogfooding_guide() {
  local row="$1"
  if [[ -z "$row" ]]; then
    echo "(가이드 자동 추출 skip — CHANGELOG row 미발견)"
    return
  fi
  local tracks
  tracks=$(echo "$row" | grep -oE 'T-[A-Z]+[0-9]*' | sort -u | head -5 || true)
  if [[ -n "$tracks" ]]; then
    echo "**관련 트랙**:"
    while IFS= read -r t; do
      [[ -z "$t" ]] && continue
      echo "- \`${t}\` — \`docs/architecture/tracks-roadmap.md\` §1·§2 참조"
    done <<< "$tracks"
  fi
  echo ""
  echo "**진입 경로**: \`docs/architecture/master-architecture.md\` §1 비전 → §5 모듈 경계 → \`_workspace/INDEX.md\` 단일 진입점"
}

# 풍부 release notes 합성
generate_rich_notes() {
  local version="$1"
  local prev
  prev=$(find_prev_tag "$version")

  local row
  row=$(extract_changelog_row "$version")
  local hero
  if [[ -n "$row" ]]; then
    hero=$(echo "$row" | sed -E 's/.*\*\*release: '"$version"' — ([^*]*)\*\*.*/\1/' | head -c 200)
    [[ -z "$hero" ]] && hero="${version} 릴리즈"
  else
    hero="${version} 릴리즈"
  fi

  cat <<NOTES_EOF
# ${version} — ${hero}

> **이전 tag**: ${prev:-(미식별)} → **${version}**
> **권위 SoT**: \`docs/architecture/master-architecture.md\` v2 (ADR 0030) · \`_workspace/INDEX.md\` 단일 진입점

## 핵심 변경 (Hero)

${row:-${hero}}

## PR 시계열 (${prev:-?} → ${version})

$(extract_pr_timeline "$prev" "$version")

## 신규 ADR

$(extract_new_adrs "$prev" "$version")

## 운영 영향

$(extract_ops_impact "$prev" "$version")

## 베이스라인 / 종합 기능 정의서

$(extract_baseline_link "$version")

## 사용자 dogfooding 가이드

$(extract_dogfooding_guide "$row")

---

자세한 변경 이력은 [CHANGELOG.md](./CHANGELOG.md) 참조.
자세한 결정 이력은 [\`_workspace/arch/adr/README.md\`](./_workspace/arch/adr/README.md) 참조.
NOTES_EOF
}

release_one() {
  local version="$1"
  local dry="${2:-false}"
  local edit="${3:-false}"

  if [[ ! "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "✗ invalid version: $version (expected vX.Y.Z)"
    return 1
  fi

  # tag 존재 확인
  if ! git tag -l "$version" | grep -q "^${version}$"; then
    echo "✗ tag $version not found locally"
    return 1
  fi

  local exists="false"
  if gh release view "$version" >/dev/null 2>&1; then
    exists="true"
  fi

  if [[ "$exists" == "true" ]] && [[ "$edit" != "true" ]]; then
    echo "✓ ${version} GitHub Release already published (skip — use --edit to update)"
    return 0
  fi

  echo "▶ ${version} release notes 풍부 합성 중 …"
  local notes
  notes=$(generate_rich_notes "$version")

  if [[ "$dry" == "true" ]]; then
    echo "--- ${version} (dry-run) ---"
    echo "$notes"
    echo "---"
    return 0
  fi

  # title은 본문 첫 줄에서 추출
  local title
  title=$(echo "$notes" | head -1 | sed 's/^# //')

  if [[ "$exists" == "true" ]]; then
    echo "▶ ${version} GitHub Release UPDATE (--edit) …"
    echo "$notes" | gh release edit "$version" --title "$title" --notes-file - 2>&1 | head -3
    echo "✓ ${version} update 완료"
  else
    echo "▶ ${version} GitHub Release 발행 …"
    echo "$notes" | gh release create "$version" --title "$title" --notes-file - 2>&1 | head -3
    echo "✓ ${version} 발행 완료"
  fi
}

# argv 파싱
MODE="single"
DRY_RUN="false"
EDIT="false"
VERSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backfill) MODE="backfill"; shift ;;
    --dry-run)  DRY_RUN="true"; shift ;;
    --edit)     EDIT="true"; shift ;;
    --help|-h)  usage; exit 0 ;;
    v*.*.*)     VERSION="$1"; shift ;;
    *)          echo "unknown arg: $1"; usage; exit 1 ;;
  esac
done

if [[ "$MODE" == "single" ]]; then
  if [[ -z "$VERSION" ]]; then
    usage
    exit 1
  fi
  release_one "$VERSION" "$DRY_RUN" "$EDIT"
else
  # backfill — vX.Y.Z 패턴만 (semver 3-part)
  for tag in $(git tag -l 'v*.*.*' | sort -V); do
    release_one "$tag" "$DRY_RUN" "$EDIT"
  done
fi

# README "Releases & Baseline 진화" 매트릭스 자동 갱신 (ADR 0052 §X 정합)
if [[ "$DRY_RUN" != "true" ]] && [[ -f scripts/sync-readme-releases.mjs ]]; then
  echo ""
  echo "▶ README 매트릭스 자동 갱신 …"
  node scripts/sync-readme-releases.mjs 2>&1 | tail -2 || echo "  (sync-readme-releases.mjs fail — skip)"
fi

# magicJar 마스터 HTML 갱신 (ADR 0079)
if [[ "$DRY_RUN" != "true" ]] && [[ -f scripts/master-index.mjs ]]; then
  echo ""
  echo "▶ master.html 갱신 …"
  node scripts/master-index.mjs 2>&1 | tail -2 || echo "  (master-index.mjs fail — skip)"
fi
