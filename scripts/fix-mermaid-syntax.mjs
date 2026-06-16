#!/usr/bin/env node
// scripts/fix-mermaid-syntax.mjs
// baseline mermaid 코드 블록의 mermaid 10 syntax error 일괄 정정
//
// 변환 룰:
//   1. dotted edge label `-.text.->` → `-->|"text"|` (solid + 따옴표)
//      mermaid 10에서 dotted edge label에 점/슬래시/공백/한글 들어가면 파싱 fail
//   2. 노드 라벨 [text] 안에 콜론/슬래시/등호 들어가면 따옴표 보강
//      [text] → ["text"]
//   3. 라벨에 이미 따옴표 있으면 skip

import { readFileSync, writeFileSync, readdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const SRC = join(ROOT, '_workspace/baseline/v3.1.0');

function fixMermaidBlock(code) {
  let fixed = code;

  // diagram type 식별 (첫 비-주석 라인 기준)
  const firstLine = code.split('\n').find(l => l.trim() && !l.trim().startsWith('%')) || '';
  const isFlowchart = /^(graph|flowchart)\s/i.test(firstLine.trim());
  const isSequence = /^sequenceDiagram/i.test(firstLine.trim());

  // 0) `\n` literal → `<br/>` (모든 다이어그램 적용)
  fixed = fixed.replace(/\\n/g, '<br/>');

  // S) sequence diagram 전용 정리
  //    - message text 안 따옴표 비대칭 (`("text")`, `(""text", a, b")`) 제거
  //    - participant alias 안 괄호·콜론·슬래시 → underscore 단순화
  if (isSequence) {
    // message line + Note line 안 따옴표 모두 제거 (line 단위)
    fixed = fixed.split('\n').map(line => {
      const m = line.match(/^(\s*)((?:\w+\s*(?:->>|-->>|->|-->|--x|--\)\s*)\s*\w+|Note\s+(?:over|left of|right of)\s+[^:]+|alt|else|opt|loop|par|and|critical|option|end|activate|deactivate|rect|autonumber)(?:\s*:)?)(.*)$/i);
      if (!m) return line;
      const prefix = m[1] + m[2];
      const tail = m[3];
      // message body의 따옴표 모두 제거
      const cleaned = tail.replace(/"/g, '');
      return prefix + cleaned;
    }).join('\n');

    // participant <name> as <alias> — alias 안 괄호 제거 + 콜론·슬래시 → underscore
    fixed = fixed.replace(/^(\s*(?:participant|actor)\s+\w+\s+as\s+)(.*)$/gm, (_, prefix, alias) => {
      let safe = alias
        .replace(/\([^)]*\)/g, '')   // (...) 괄호 부분 제거
        .replace(/[:/]/g, '_')         // : / → _
        .replace(/\s+/g, ' ')           // 공백 정규화
        .trim();
      if (!safe) safe = 'X';
      return prefix + safe;
    });
    return fixed;
  }

  // 1·2) flowchart/graph 전용 변환
  if (!isFlowchart) return fixed;

  // 1) dotted edge label `-.text.->` → `-->|"text"|`
  fixed = fixed.replace(/-\.\s*([^>\n][^>\n]*?)\s*\.->/g, (m, label) => {
    const cleanLabel = label.trim();
    if (!cleanLabel) return m;
    const safe = cleanLabel.replace(/"/g, '#quot;');
    return `-->|"${safe}"|`;
  });

  // 2) 노드 라벨 따옴표 보강
  const SPECIALS = /[:\/=+\s,]/;
  function quoteIfNeeded(prefix, label, suffix) {
    if (label.startsWith('"') && label.endsWith('"')) return prefix + label + suffix;
    if (!SPECIALS.test(label)) return prefix + label + suffix;
    if (label.includes('"')) return prefix + label + suffix;
    return prefix + `"${label}"` + suffix;
  }

  fixed = fixed.replace(/(\w+\[\()([^\[\]\(\)\n]+?)(\)\])/g, (_, p, l, s) => quoteIfNeeded(p, l, s));
  fixed = fixed.replace(/(\w+\(\()([^\(\)\n]+?)(\)\))/g, (_, p, l, s) => quoteIfNeeded(p, l, s));
  fixed = fixed.replace(/(\w+\[)([^\[\]\n]+?)(\])/g, (_, p, l, s) => {
    if (l.startsWith('(') || l.endsWith(')')) return p + l + s;
    return quoteIfNeeded(p, l, s);
  });
  fixed = fixed.replace(/(\w+)\(([^\(\)\n]+?)\)/g, (_, name, l) => {
    if (l.startsWith('"') && l.endsWith('"')) return `${name}(${l})`;
    if (!SPECIALS.test(l)) return `${name}(${l})`;
    return `${name}("${l}")`;
  });

  return fixed;
}

const files = readdirSync(SRC).filter(f => f.endsWith('.md'));
let changedCount = 0;

for (const file of files) {
  const path = join(SRC, file);
  const original = readFileSync(path, 'utf-8');

  let blocksFound = 0;
  let blocksChanged = 0;

  const updated = original.replace(/```mermaid\n([\s\S]*?)\n```/g, (match, code) => {
    blocksFound++;
    const fixed = fixMermaidBlock(code);
    if (fixed !== code) blocksChanged++;
    return '```mermaid\n' + fixed + '\n```';
  });

  if (updated !== original) {
    writeFileSync(path, updated);
    console.log(`✓ ${file} — ${blocksChanged}/${blocksFound} blocks updated`);
    changedCount++;
  } else {
    console.log(`  ${file} — no change (${blocksFound} blocks)`);
  }
}

console.log(`\n✓ 변환 완료: ${changedCount} 파일 수정`);
