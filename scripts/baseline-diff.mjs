#!/usr/bin/env node
// scripts/baseline-diff.mjs <v1> <v2>
// 두 baseline 폴더 비교 → release notes 후보 markdown 출력
//
// 사용:
//   node scripts/baseline-diff.mjs v3.1.0 v3.2.0 > /tmp/diff.md
//   node scripts/baseline-diff.mjs v3.1.0 v3.2.0 --summary

import { readFileSync, readdirSync, existsSync, statSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

const v1 = process.argv[2];
const v2 = process.argv[3];
const summary = process.argv.includes('--summary');

if (!v1 || !v2) {
  console.error('사용: node scripts/baseline-diff.mjs <v1> <v2> [--summary]');
  console.error('예: node scripts/baseline-diff.mjs v3.1.0 v3.2.0');
  process.exit(1);
}

const D1 = join(ROOT, '_workspace/baseline', v1);
const D2 = join(ROOT, '_workspace/baseline', v2);
if (!existsSync(D1)) { console.error(`✗ 없음: ${D1}`); process.exit(1); }
if (!existsSync(D2)) { console.error(`✗ 없음: ${D2}`); process.exit(1); }

function listMd(dir) {
  return readdirSync(dir)
    .filter(f => f.endsWith('.md'))
    .filter(f => statSync(join(dir, f)).isFile());
}

const files1 = new Set(listMd(D1));
const files2 = new Set(listMd(D2));
const all = new Set([...files1, ...files2]);

const rows = [];
for (const f of [...all].sort()) {
  const in1 = files1.has(f);
  const in2 = files2.has(f);
  let status, lines, change;

  if (in1 && in2) {
    const c1 = readFileSync(join(D1, f), 'utf-8');
    const c2 = readFileSync(join(D2, f), 'utf-8');
    if (c1 === c2) {
      status = '동일';
      lines = c2.split('\n').length;
      change = '0';
    } else {
      status = '변경';
      lines = c2.split('\n').length;
      const l1 = c1.split('\n').length;
      change = `${lines - l1 >= 0 ? '+' : ''}${lines - l1}`;
    }
  } else if (in1) {
    status = '삭제';
    lines = 0;
    change = `-${readFileSync(join(D1, f), 'utf-8').split('\n').length}`;
  } else {
    status = '신규';
    lines = readFileSync(join(D2, f), 'utf-8').split('\n').length;
    change = `+${lines}`;
  }
  rows.push({ file: f, status, lines, change });
}

console.log(`# Baseline diff — ${v1} → ${v2}\n`);
console.log(`> 생성: ${new Date().toISOString().slice(0, 10)} · \`scripts/baseline-diff.mjs\`\n`);
console.log(`## 파일 매트릭스\n`);
console.log(`| 파일 | 상태 | ${v2} 라인 | 변화 |`);
console.log(`|------|------|------------|------|`);
for (const r of rows) {
  console.log(`| ${r.file} | ${r.status} | ${r.lines} | ${r.change} |`);
}

if (summary) {
  console.log(`\n## 요약\n`);
  console.log(`- 신규: ${rows.filter(r => r.status === '신규').length}`);
  console.log(`- 변경: ${rows.filter(r => r.status === '변경').length}`);
  console.log(`- 삭제: ${rows.filter(r => r.status === '삭제').length}`);
  console.log(`- 동일: ${rows.filter(r => r.status === '동일').length}`);
} else {
  console.log(`\n## 변경 파일별 git diff (--stat)\n`);
  for (const r of rows.filter(r => r.status === '변경')) {
    const path = `_workspace/baseline/${v2}/${r.file}`;
    try {
      const stat = execSync(
        `git diff --stat _workspace/baseline/${v1}/${r.file} _workspace/baseline/${v2}/${r.file} 2>&1 || true`,
        { cwd: ROOT, encoding: 'utf-8' },
      );
      console.log(`### ${r.file}\n\n\`\`\`\n${stat.trim() || '(no stat)'}\n\`\`\`\n`);
    } catch {
      console.log(`### ${r.file}\n\n(diff 실패)\n`);
    }
  }
}
