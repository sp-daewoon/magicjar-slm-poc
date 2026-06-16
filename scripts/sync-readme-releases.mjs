#!/usr/bin/env node
// scripts/sync-readme-releases.mjs
//
// README.md "📦 Releases & Baseline 진화" 매트릭스 idempotent 재생성.
// scripts/release.sh + scripts/baseline-to-html.mjs 후속에서 자동 호출.
//
// 동작:
//   1. git tag -l 'v*.*.*' 시계열 추출 (semver 내림차순)
//   2. 각 tag에 대해:
//      - GitHub Release 존재 확인 (gh release view, 결제 무관)
//      - baseline html 존재 확인 (_workspace/baseline/vX.Y.0/html/index.html)
//      - 미러스톤 한 줄 (CHANGELOG row에서 추출)
//   3. README anchor 사이 표 재생성
//
// README anchor 의무:
//   <!-- releases-table:start -->
//   ...
//   <!-- releases-table:end -->
//
// ADR 0052 §X release 절차 정합.

import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const README = 'README.md';
const START = '<!-- releases-table:start -->';
const END = '<!-- releases-table:end -->';
const REPO = 'sp-daewoon/magicJar';

function semverCompare(a, b) {
  const pa = a.slice(1).split('.').map(Number);
  const pb = b.slice(1).split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (pa[i] !== pb[i]) return pb[i] - pa[i];
  }
  return 0;
}

function listTags() {
  try {
    const out = execSync("git tag -l 'v*.*.*'", { encoding: 'utf8' });
    return out.trim().split('\n').filter(t => t).sort(semverCompare);
  } catch {
    return [];
  }
}

function tagDate(tag) {
  try {
    const ts = execSync(`git log -1 --format=%ai ${tag}`, { encoding: 'utf8' });
    return ts.trim().slice(0, 10);
  } catch {
    return '';
  }
}

function hasRelease(tag) {
  try {
    execSync(`gh release view ${tag}`, { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function baselineHtml(tag) {
  const m = tag.match(/^v(\d+)\.(\d+)\.\d+$/);
  if (!m) return null;
  const baseline = `v${m[1]}.${m[2]}.0`;
  const path = `_workspace/baseline/${baseline}/html/index.html`;
  return existsSync(path) ? path : null;
}

function milestone(tag) {
  try {
    const lines = readFileSync('CHANGELOG.md', 'utf8').split('\n');
    const escTag = tag.replace(/\./g, '\\.');
    const re = new RegExp(`release:\\s+${escTag}\\s+—\\s+([^*(|]+?)(?:\\s*\\(|\\s*\\*\\*|$)`);
    for (const l of lines) {
      const m = l.match(re);
      if (m) {
        return m[1].trim().replace(/\s+/g, ' ');
      }
    }
  } catch {}
  return '(검토)';
}

function renderTable(tags) {
  const rows = tags.map(tag => {
    const d = tagDate(tag);
    const ms = milestone(tag);
    const releaseLink = hasRelease(tag)
      ? `[📋](https://github.com/${REPO}/releases/tag/${tag})`
      : '—';
    const bl = baselineHtml(tag);
    const baselineLink = bl ? `[📊](${bl})` : '—';
    return `| **${tag}** | ${d} | ${ms} | ${releaseLink} | ${baselineLink} |`;
  });
  return [
    '| 버전 | 날짜 | 미러스톤 | Release | Baseline |',
    '|------|------|----------|---------|----------|',
    ...rows,
  ].join('\n');
}

function main() {
  if (!existsSync(README)) {
    console.error(`✗ README.md not found`);
    process.exit(1);
  }
  const content = readFileSync(README, 'utf8');
  const idxStart = content.indexOf(START);
  const idxEnd = content.indexOf(END);
  if (idxStart === -1 || idxEnd === -1) {
    console.error(`✗ README anchor 미발견. 다음 마커를 README에 추가:`);
    console.error(`  ${START}`);
    console.error(`  ${END}`);
    process.exit(1);
  }

  const tags = listTags();
  if (tags.length === 0) {
    console.log('✓ git tag 0건 — 갱신 skip');
    return;
  }

  const table = renderTable(tags);
  const block = `${START}\n\n${table}\n\n${END}`;
  const newContent = content.slice(0, idxStart) + block + content.slice(idxEnd + END.length);

  if (newContent === content) {
    console.log(`✓ README "Releases & Baseline 진화" 매트릭스 정합 (no change, ${tags.length} tags)`);
    return;
  }

  writeFileSync(README, newContent);
  console.log(`✓ README "Releases & Baseline 진화" 매트릭스 갱신 (${tags.length} tags)`);
}

main();
