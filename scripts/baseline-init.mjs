#!/usr/bin/env node
// scripts/baseline-init.mjs <version>
// 새 baseline 사이클 진입 — 폴더 + README skeleton + CHANGELOG slot 4 anchor
//
// 후속 단계 (수동 또는 magicjar-baseline skill):
//   1. orchestrator가 4 sub-agent worktree + task 발행
//   2. 4 sub-agent 병렬 디스패치 (architect=01·06·07·README, backend-core=02·04, market-data=03, frontend=05)
//   3. 머지 후 자동 정리 4단계 ×4
//   4. node scripts/fix-mermaid-syntax.mjs && node scripts/baseline-to-html.mjs

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

const version = process.argv[2];
if (!version || !/^v\d+\.\d+\.\d+$/.test(version)) {
  console.error('사용: node scripts/baseline-init.mjs <version>');
  console.error('예: node scripts/baseline-init.mjs v3.2.0');
  process.exit(1);
}

const BASELINE_DIR = join(ROOT, '_workspace/baseline', version);
if (existsSync(BASELINE_DIR)) {
  console.error(`✗ 이미 존재: ${BASELINE_DIR}`);
  process.exit(1);
}

mkdirSync(BASELINE_DIR, { recursive: true });

const today = new Date().toISOString().slice(0, 10);

// README skeleton
const readme = `# magicJar ${version} Baseline — (사이클 명) 시점 종합 기능 정의서

> **작성 시점**: ${today} ${version} 태그 컷 (main \`<TBD-commit>\`, release/<TBD-branch>)
> **사이클**: PR-? ~ PR-? (? 사이클)
> **상태**: skeleton — sub-agent 본격 작성 대기

## 목적

(이번 Wave 진입 직전 / release 시점에 시스템이 무엇을 할 수 있는지 시각화 가능한 수준으로 종합 정리.)

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | Mermaid 4 모듈 + 외부 통합 + frontend 아키텍처 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름 + Kafka 토픽 매트릭스 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 + KIS-first raw 출처 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | 트랙 기능 명세 상세 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리 |
| [06-history-traceability.md](06-history-traceability.md) | architect | PR·ADR·기능 매핑 |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 운영 헌법 + ADR 진화 |

## 한 장 요약 (TBD)

(architect가 본격 작성)

## 작성 규약

- 본문 한국어, 코드/명령은 영어 그대로
- Mermaid 다이어그램 의무
- raw 출처 인용 의무 (KIS API · DART · 코드 path)
- 추측·관념적 서술 금지
- TBD 표시 — 미작성 영역
`;

writeFileSync(join(BASELINE_DIR, 'README.md'), readme);

// CHANGELOG slot 발행
const changelogPath = join(ROOT, 'CHANGELOG.md');
const changelog = readFileSync(changelogPath, 'utf-8');
const cycleId = (() => {
  // 가장 최근 PR-N 번호 추정 (PR-30 다음 → PR-31)
  const match = changelog.match(/PR-(\d+)/);
  return match ? `PR-${parseInt(match[1]) + 1}` : 'PR-?';
})();

const slotBlock = `| ${today} | **chore(meta): ${cycleId} ${version} baseline 폴더 신설 + sub-agent 슬롯 4 anchor 발행** — \`_workspace/baseline/${version}/\` 폴더 신설(README skeleton + 7 파일 인덱스). CHANGELOG sub-agent 슬롯 4 anchor(architect/backend-core/market-data/frontend). 4 sub-agent 병렬 디스패치 add-only LOCK. (orchestrator) | meta/baseline | ${cycleId} ${version} baseline 슬롯 발행 |
<!-- ${cycleId.toLowerCase()}-slot-orchestrator -->
| 2026-05-?? | **TBD — ${cycleId} architect: 01-architecture + 06-history + 07-policy + README** | baseline/${version} | ${cycleId} architect 4 파일 |
<!-- ${cycleId.toLowerCase()}-slot-architect -->
| 2026-05-?? | **TBD — ${cycleId} backend-core: 02-data-flow + 04-feature-spec** | baseline/${version} | ${cycleId} backend-core 2 파일 |
<!-- ${cycleId.toLowerCase()}-slot-backend-core -->
| 2026-05-?? | **TBD — ${cycleId} market-data: 03-use-sequences (KIS-first raw 출처)** | baseline/${version} | ${cycleId} market-data 1 파일 |
<!-- ${cycleId.toLowerCase()}-slot-market-data -->
| 2026-05-?? | **TBD — ${cycleId} frontend: 05-ui-inventory** | baseline/${version} | ${cycleId} frontend 1 파일 |
<!-- ${cycleId.toLowerCase()}-slot-frontend -->
`;

const HEAD = '|------|----------|------|------|';
const idx = changelog.indexOf(HEAD);
if (idx === -1) {
  console.error('✗ CHANGELOG.md 표 헤더 발견 실패');
  process.exit(1);
}
const insertAt = idx + HEAD.length + 1;
const newChangelog = changelog.slice(0, insertAt) + '\n' + slotBlock + changelog.slice(insertAt);
writeFileSync(changelogPath, newChangelog);

console.log(`✓ ${version} baseline 진입 완료`);
console.log(`  폴더: _workspace/baseline/${version}/`);
console.log(`  README skeleton: 작성됨`);
console.log(`  CHANGELOG slot: 4 anchor + 5 row prepend (cycle=${cycleId})`);
console.log('');
console.log('다음 단계:');
console.log('  1. orchestrator: 4 sub-agent worktree + task 발행 (magicjar-baseline skill)');
console.log('  2. 4 sub-agent 병렬 디스패치 (architect/backend-core/market-data/frontend)');
console.log('  3. 머지 후 정리 4단계 (헌법 §2)');
console.log(`  4. node scripts/fix-mermaid-syntax.mjs && node scripts/baseline-to-html.mjs`);
