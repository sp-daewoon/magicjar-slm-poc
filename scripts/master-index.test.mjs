#!/usr/bin/env node
// scripts/master-index.test.mjs
//
// Node native test runner — node --test scripts/master-index.test.mjs
// magicJar 룰 4번 정합 — Phase 3 일괄 test (Phase 1·2는 코드 우선)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { firstH2, firstParagraph, parseAdrFile, parseBacklogSections, fileMeta } from './master-index.mjs';

test('firstH2 — 첫 H2 추출', () => {
  assert.equal(firstH2('# H1\n## H2 title\n본문'), 'H2 title');
  assert.equal(firstH2('# H1 only'), null);
});

test('firstParagraph — frontmatter + heading 건너뛰고 첫 단락', () => {
  const md = `---\nname: x\n---\n\n# title\n\n첫 단락 텍스트.\n\n두 번째 단락.`;
  assert.equal(firstParagraph(md, 100), '첫 단락 텍스트.');
});

test('firstParagraph — maxLen 초과 시 … 잘림', () => {
  const long = 'a'.repeat(300);
  const r = firstParagraph(long, 50);
  assert.equal(r.length, 51);
  assert.ok(r.endsWith('…'));
});

test('parseAdrFile — 정상 frontmatter + 본문', () => {
  const md = `# ADR 0078 — 내부 자산 추출\n\n**Status**: Proposed\n\n발행일: 2026-05-16\n\n본문…`;
  const r = parseAdrFile(md, '0078-internal-asset-extraction.md');
  assert.equal(r.number, 78);
  assert.equal(r.status, 'Proposed');
  assert.equal(r.date, '2026-05-16');
  assert.match(r.title, /내부 자산 추출/);
});

test('parseAdrFile — 파일명 번호 누락 시 null', () => {
  const r = parseAdrFile('# Some ADR', 'no-number.md');
  assert.equal(r, null);
});

test('parseAdrFile — superseded keyword 추출', () => {
  const md = `# ADR 0073 — Something Superseded by 0075`;
  const r = parseAdrFile(md, '0073-something.md');
  assert.equal(r.status, 'Superseded');
});

test('parseBacklogSections — § 번호 + status + date 추출', () => {
  const md = `## §1. 첫 의제\n\nProposed 2026-05-15\n\n본문\n\n## §2. ✅ 처리 완료 — 두 번째\n\n완료 사유\n\n## §3. 세 번째 의제\n\nProposed 2026-05-17`;
  const sections = parseBacklogSections(md);
  assert.equal(sections.length, 3);
  assert.equal(sections[0].number, 1);
  assert.equal(sections[0].status, 'Proposed');
  assert.equal(sections[0].date, '2026-05-15');
  assert.equal(sections[1].number, 2);
  assert.equal(sections[1].status, 'Done');
  assert.equal(sections[2].number, 3);
  assert.equal(sections[2].date, '2026-05-17');
});

test('fileMeta — 존재하지 않는 파일 null', () => {
  assert.equal(fileMeta('/nonexistent/file.md'), null);
});

test('idempotent — 같은 입력 → 같은 ADR 정렬 결과', () => {
  const md = '# ADR Test\n\n**Status**: Accepted';
  const r1 = parseAdrFile(md, '0001-a.md');
  const r2 = parseAdrFile(md, '0001-a.md');
  assert.deepEqual(r1, r2);
});
