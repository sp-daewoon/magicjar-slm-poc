#!/usr/bin/env node
// magicJar Task 파일 시스템 CLI 헬퍼.
// _workspace/tasks/<id>.md 를 생성·갱신·archive 한다.
//
// v3 (ADR 0036 §2-2) — 멀티 agent 호환:
//   각 sub-agent (architect · backend-core · market-data · strategy · ai · frontend · qa)
//   가 독립 발급·자기 git author로 commit. 병렬 디스패치 안전.
//   task ID 충돌 방지: slug에 agent 이름 권장 (T-YYYYMMDD-HHMM-<agent>-<topic>).
//
// 사용 예:
//   node scripts/task.mjs start --agent backend-core --branch agent/backend-core/X --goal "Gradle 멀티모듈 뼈대"
//   node scripts/task.mjs check "core 도메인 모델"
//   node scripts/task.mjs touch "DART 단위 테스트 통과"
//   node scripts/task.mjs block --reason "DART API 키 필요"
//   node scripts/task.mjs done --pr 12
//   node scripts/task.mjs archive T-20260425-1605
//   node scripts/task.mjs list

import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { execSync } from 'node:child_process';

const TASKS_DIR = '_workspace/tasks';
const DONE_DIR = '_workspace/tasks/done';

function sh(cmd) {
  try { return execSync(cmd, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(); }
  catch { return ''; }
}
// PR-22 — 사용자 보고: task 파일 시간이 UTC로 찍혀 한국시간보다 9시간 빠름.
// startedAt·completedAt 등은 KST(+09:00) ISO 8601로 표기.
function nowIso() {
  const now = new Date();
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().slice(0, -1) + '+09:00';
}
function nowKstStamp() {
  // YYYYMMDD-HHMM in Asia/Seoul
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const parts = Object.fromEntries(fmt.formatToParts(new Date()).map(p => [p.type, p.value]));
  return `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}`;
}

function ensureDirs() {
  if (!existsSync(TASKS_DIR)) mkdirSync(TASKS_DIR, { recursive: true });
  if (!existsSync(DONE_DIR)) mkdirSync(DONE_DIR, { recursive: true });
}

// PR-26 — boolean flag 인식 보강.
// 기존: `--force` 호출 시 다음 토큰을 value로 가져감 → boolean flag 미지원.
// 정정: 다음 토큰이 없거나 `--`로 시작하면 boolean true로 인식.
// 사용 예: `task.mjs archive T-id --force` → out.force = true
//         `task.mjs start --agent architect` → out.agent = 'architect' (기존 동작 유지)
function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) {
        out[key] = true;
      } else {
        out[key] = next;
        i++;
      }
    } else {
      out._.push(a);
    }
  }
  return out;
}

function parseFrontmatter(text) {
  const m = text.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!m) return { meta: {}, body: text };
  const meta = {};
  m[1].split('\n').forEach(line => {
    const idx = line.indexOf(':');
    if (idx > 0) {
      const k = line.slice(0, idx).trim();
      const v = line.slice(idx + 1).trim();
      meta[k] = (v === 'null' || v === '') ? null : v;
    }
  });
  return { meta, body: m[2] };
}
function serializeFrontmatter(meta, body) {
  const order = ['id', 'agent', 'model', 'branch', 'status', 'startedAt', 'completedAt', 'pr', 'parentPhase'];
  const lines = [];
  for (const k of order) {
    if (meta[k] !== undefined) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  for (const k of Object.keys(meta)) {
    if (!order.includes(k)) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  return `---\n${lines.join('\n')}\n---\n${body}`;
}

function findActiveTaskFile(branchHint) {
  ensureDirs();
  const branch = branchHint || sh('git rev-parse --abbrev-ref HEAD');
  const files = readdirSync(TASKS_DIR).filter(f => f.endsWith('.md') && f !== 'README.md');
  for (const f of files) {
    const path = join(TASKS_DIR, f);
    const { meta } = parseFrontmatter(readFileSync(path, 'utf-8'));
    if (meta.branch === branch && meta.status !== 'DONE') return { path, meta };
  }
  return null;
}

function cmdStart(args) {
  ensureDirs();
  const agent = args.agent || (() => { throw new Error('--agent 필수'); })();
  const branch = args.branch || sh('git rev-parse --abbrev-ref HEAD');
  const goal = args.goal || (() => { throw new Error('--goal 필수'); })();
  const model = args.model || (() => {
    const agentFile = `.claude/agents/${agent}.md`;
    if (!existsSync(agentFile)) return 'opus';
    const { meta } = parseFrontmatter(readFileSync(agentFile, 'utf-8'));
    return meta.model || 'opus';
  })();
  const slug = (args.slug || branch.split('/').pop() || 'task')
    .toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 40);
  const id = `T-${nowKstStamp()}-${slug}`;
  const path = join(TASKS_DIR, `${id}.md`);
  if (existsSync(path)) throw new Error(`이미 존재: ${path}`);

  const checklistRaw = (args.deliverables || '').trim();
  const checklistLines = checklistRaw
    ? checklistRaw.split(/\s*[,;\n]\s*/).filter(Boolean).map(d => `- [ ] ${d}`).join('\n')
    : '- [ ] (작업 시작 시 결과물 항목을 채워주세요)';

  const meta = {
    id, agent, model, branch,
    status: 'RUNNING',
    startedAt: nowIso(),
    completedAt: null,
    pr: null,
    parentPhase: args.phase || (sh('cat _workspace/_harness-state.json | grep currentPhase | head -1').match(/"([^"]+)"/g)?.[1]?.replace(/"/g, '') || '?'),
  };
  const body = `\n# 목표\n${goal}\n\n## 결과물 체크리스트\n${checklistLines}\n\n## 의존성\n- (필요 시 기록)\n\n## 마지막 활동\n${nowIso()} — 작업 시작\n\n## 비고\n`;
  writeFileSync(path, serializeFrontmatter(meta, body), 'utf-8');
  console.log(`✓ task 생성: ${path}`);
  console.log(`  id: ${id}  agent: ${agent}  model: ${model}  branch: ${branch}`);
}

function cmdCheck(args) {
  const item = args._[0] || args.item || (() => { throw new Error('체크리스트 항목 인자 필요'); })();
  const found = findActiveTaskFile(args.branch);
  if (!found) throw new Error('현재 브랜치의 활성 task 파일 없음. start 먼저.');
  const text = readFileSync(found.path, 'utf-8');
  const re = new RegExp(`^- \\[ \\] (.*${item.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\$&')}.*)$`, 'mi');
  const m = text.match(re);
  if (!m) throw new Error(`체크리스트에서 매칭 항목 없음: "${item}"`);
  const replaced = text.replace(re, `- [x] ${m[1]}`);
  writeFileSync(found.path, replaced, 'utf-8');
  console.log(`✓ check: ${m[1]}`);
}

function cmdTouch(args) {
  const note = args._[0] || args.note || '';
  const found = findActiveTaskFile(args.branch);
  if (!found) throw new Error('현재 브랜치의 활성 task 파일 없음.');
  const text = readFileSync(found.path, 'utf-8');
  const re = /## 마지막 활동\n[\s\S]*?(?=\n## |\n*$)/;
  const replaced = text.replace(re, `## 마지막 활동\n${nowIso()} — ${note || '(메모 없음)'}\n`);
  writeFileSync(found.path, replaced, 'utf-8');
  console.log(`✓ touch: ${note || '(메모 없음)'}`);
}

function cmdBlock(args) {
  const reason = args.reason || (() => { throw new Error('--reason 필수'); })();
  const found = findActiveTaskFile(args.branch);
  if (!found) throw new Error('현재 브랜치의 활성 task 파일 없음.');
  const { meta, body } = parseFrontmatter(readFileSync(found.path, 'utf-8'));
  meta.status = 'BLOCKED';
  const newBody = body.replace(/## 마지막 활동\n[\s\S]*?(?=\n## |\n*$)/,
    `## 마지막 활동\n${nowIso()} — BLOCKED: ${reason}\n`);
  writeFileSync(found.path, serializeFrontmatter(meta, newBody), 'utf-8');
  console.log(`⛔ blocked: ${reason}`);
}

function cmdDone(args) {
  const pr = args.pr || null;
  const found = findActiveTaskFile(args.branch);
  if (!found) throw new Error('현재 브랜치의 활성 task 파일 없음.');
  const { meta, body } = parseFrontmatter(readFileSync(found.path, 'utf-8'));
  meta.status = 'DONE';
  meta.completedAt = nowIso();
  if (pr) meta.pr = `#${String(pr).replace(/^#/, '')}`;
  writeFileSync(found.path, serializeFrontmatter(meta, body), 'utf-8');
  console.log(`✓ done${pr ? ` (PR ${meta.pr})` : ''}: ${meta.id}`);

  // 안정성 Cycle 2 (2026-05-18) — task 파일 자동 git add (헌법 §1-C 정합).
  // sub-agent가 task 파일 commit 누락 시 main 흔적 부재 → orchestrator archive 부담.
  // 본 add는 graceful — git repo 아니거나 add 실패해도 done 자체는 성공.
  // commit은 수행하지 않음(다음 코드 commit에 자연 stage). 무관 코드 stage risk 회피.
  try {
    execSync(`git add ${found.path}`, { stdio: ['ignore', 'pipe', 'pipe'] });
    console.log(`  ↳ git add: ${found.path} (다음 commit에 stage)`);
  } catch (e) {
    console.warn(`  ⚠ git add 실패 (graceful skip): ${e.message?.slice(0, 80) || ''}`);
  }
}

function cmdArchive(args) {
  const id = args._[0] || (() => { throw new Error('task id 인자 필요'); })();
  const force = args.force === true || args.force === 'true';
  ensureDirs();
  const src = join(TASKS_DIR, `${id}.md`);
  const dst = join(DONE_DIR, `${id}.md`);
  if (!existsSync(src)) throw new Error(`task 파일 없음: ${src}`);

  // PR-18 무결성 보강 — status=DONE 검증 (사용자 보고: RUNNING 상태로 done/ 누적)
  const { meta, body } = parseFrontmatter(readFileSync(src, 'utf-8'));
  if (meta.status !== 'DONE') {
    if (!force) {
      throw new Error(
        `archive 거부 — task '${id}'의 status='${meta.status}' (DONE 아님). ` +
        `'task.mjs done --pr <N>'으로 완료 마킹 후 재시도, 또는 '--force'로 우회.`,
      );
    }
    // force 모드: status·completedAt 자동 보강 후 archive
    console.warn(`⚠ force-archive: status='${meta.status}' → 'DONE' 강제 정정`);
    meta.status = 'DONE';
    if (!meta.completedAt || meta.completedAt === 'null' || meta.completedAt === null) meta.completedAt = nowIso();
    writeFileSync(src, serializeFrontmatter(meta, body), 'utf-8');
  }

  renameSync(src, dst);
  console.log(`✓ archive: ${id} → ${dst}`);
}

function cmdList() {
  ensureDirs();
  const files = readdirSync(TASKS_DIR).filter(f => f.endsWith('.md') && f !== 'README.md');
  if (!files.length) { console.log('(활성 task 없음)'); return; }
  for (const f of files.sort()) {
    const { meta } = parseFrontmatter(readFileSync(join(TASKS_DIR, f), 'utf-8'));
    console.log(`${meta.status === 'BLOCKED' ? '⛔' : meta.status === 'DONE' ? '✓' : '●'} ${meta.id}  ${meta.agent}  [${meta.status}]  ${meta.branch}`);
  }
}

const args = parseArgs(process.argv.slice(3));
const cmd = process.argv[2];
try {
  switch (cmd) {
    case 'start':   cmdStart(args); break;
    case 'check':   cmdCheck(args); break;
    case 'touch':   cmdTouch(args); break;
    case 'block':   cmdBlock(args); break;
    case 'done':    cmdDone(args); break;
    case 'archive': cmdArchive(args); break;
    case 'list':    cmdList(); break;
    default:
      console.error('Usage: task.mjs <start|check|touch|block|done|archive|list> [args...]');
      process.exit(1);
  }
} catch (e) {
  console.error(`✗ ${e.message}`);
  process.exit(1);
}
