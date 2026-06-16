#!/usr/bin/env node
// magicJar status dashboard renderer (v2 — Task 파일 시스템 통합).
// 출력: _workspace/dashboard.html (auto-refreshing static page).

import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const OUT = '_workspace/dashboard.html';

function sh(cmd) {
  try { return execSync(cmd, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(); }
  catch { return ''; }
}
function readJsonSafe(path, fallback) {
  try { return JSON.parse(readFileSync(path, 'utf-8')); } catch { return fallback; }
}
function readFileSafe(path, fallback = '') {
  try { return readFileSync(path, 'utf-8'); } catch { return fallback; }
}
function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}
function relTime(iso) {
  if (!iso) return '?';
  const t = new Date(iso).getTime();
  if (!t) return iso;
  const d = (Date.now() - t) / 1000;
  if (d < 60) return `${Math.round(d)}초 전`;
  if (d < 3600) return `${Math.round(d / 60)}분 전`;
  if (d < 86400) return `${Math.round(d / 3600)}시간 전`;
  return `${Math.round(d / 86400)}일 전`;
}
function staleness(iso) {
  if (!iso) return 'unknown';
  const min = (Date.now() - new Date(iso).getTime()) / 60000;
  if (min < 5) return 'fresh';
  if (min < 30) return 'recent';
  if (min < 180) return 'cooling';
  return 'stale';
}

// ─── 데이터 수집 ──────────────────────────────────────────────

const harnessState = readJsonSafe('_workspace/_harness-state.json', {
  currentPhase: '?', phaseHistory: [], activeAgents: [], blockedAgents: [], updatedAt: null,
});

const mainLog = sh('git log --pretty=format:%H%x09%cI%x09%s origin/main -10')
  .split('\n').filter(Boolean).map(line => {
    const [hash, iso, ...rest] = line.split('\t');
    return { hash: hash.slice(0, 7), iso, subject: rest.join('\t') };
  });

let openPRs = [];
let mergedPRs = [];
try { openPRs = JSON.parse(sh('gh pr list --state open --json number,title,headRefName,baseRefName,isDraft,createdAt,updatedAt') || '[]'); } catch {}
try { mergedPRs = JSON.parse(sh('gh pr list --state merged --limit 8 --json number,title,mergedAt,headRefName,baseRefName,author') || '[]'); } catch {}

const agentsDir = '.claude/agents';
const agents = existsSync(agentsDir)
  ? readdirSync(agentsDir).filter(f => f.endsWith('.md')).map(f => {
      const content = readFileSafe(join(agentsDir, f));
      const fm = content.match(/^---\n([\s\S]*?)\n---/);
      const meta = { file: f, name: f.replace('.md', ''), description: '', model: 'opus' };
      if (fm) {
        fm[1].split('\n').forEach(line => {
          const idx = line.indexOf(':');
          if (idx > 0) {
            const k = line.slice(0, idx).trim();
            const v = line.slice(idx + 1).trim();
            meta[k] = v;
          }
        });
      }
      return meta;
    })
  : [];

const worktreesDir = '.claude/worktrees';
const worktrees = existsSync(worktreesDir)
  ? readdirSync(worktreesDir).filter(d => {
      try { return statSync(join(worktreesDir, d)).isDirectory(); } catch { return false; }
    }).map(d => {
      const path = join(worktreesDir, d);
      const branch = sh(`git -C "${path}" rev-parse --abbrev-ref HEAD 2>/dev/null`);
      const lastIso = sh(`git -C "${path}" log -1 --format=%cI 2>/dev/null`);
      const lastSubject = sh(`git -C "${path}" log -1 --format=%s 2>/dev/null`);
      return { dir: d, branch, lastIso, lastSubject, stale: staleness(lastIso) };
    })
  : [];

const qaDir = '_workspace/qa';
const qaScreenshotsDir = join(qaDir, 'screenshots');
const qaIssuesPath = join(qaDir, 'issues.md');
const qaReports = existsSync(qaDir)
  ? readdirSync(qaDir).filter(f => f.startsWith('report-') && f.endsWith('.md'))
  : [];
const qaScreenshotDirs = existsSync(qaScreenshotsDir)
  ? readdirSync(qaScreenshotsDir).filter(d => {
      try { return statSync(join(qaScreenshotsDir, d)).isDirectory(); } catch { return false; }
    })
  : [];
const qaIssuesText = readFileSafe(qaIssuesPath, '');
const qaIssuesOpen = (qaIssuesText.match(/^- \[ \]/gm) || []).length
                  + (qaIssuesText.match(/^Status:\s*OPEN/gmi) || []).length;
const qaIssuesFixed = (qaIssuesText.match(/^- \[x\]/gmi) || []).length
                    + (qaIssuesText.match(/^Status:\s*FIXED/gmi) || []).length;

const adrDir = '_workspace/arch/adr';
const adrCount = existsSync(adrDir)
  ? readdirSync(adrDir).filter(f => f.match(/^\d{4}-.*\.md$/)).length
  : 0;

// Task files (ADR 0018)
const tasksDir = '_workspace/tasks';
function parseTaskFile(path) {
  const text = readFileSafe(path);
  const m = text.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!m) return null;
  const meta = {};
  m[1].split('\n').forEach(line => {
    const idx = line.indexOf(':');
    if (idx > 0) {
      const k = line.slice(0, idx).trim();
      const v = line.slice(idx + 1).trim();
      meta[k] = (v === 'null' || v === '') ? null : v;
    }
  });
  const body = m[2];
  const checklistAll = (body.match(/^- \[[ xX]\] /gm) || []).length;
  const checklistDone = (body.match(/^- \[[xX]\] /gm) || []).length;
  const lastM = body.match(/## 마지막 활동\s*\n([^\n]+)/);
  const lastActivity = lastM ? lastM[1].trim() : null;
  const goalM = body.match(/# 목표\s*\n([\s\S]*?)(?=\n## )/);
  const goal = goalM ? goalM[1].trim().slice(0, 200) : '';
  return { ...meta, checklistAll, checklistDone, lastActivity, goal };
}
const activeTasks = existsSync(tasksDir)
  ? readdirSync(tasksDir).filter(f => f.endsWith('.md') && f !== 'README.md')
      .map(f => parseTaskFile(join(tasksDir, f))).filter(Boolean)
      .sort((a, b) => (b.startedAt || '').localeCompare(a.startedAt || ''))
  : [];
const doneTasksDir = join(tasksDir, 'done');
const doneTasksRecent = existsSync(doneTasksDir)
  ? readdirSync(doneTasksDir).filter(f => f.endsWith('.md'))
      .map(f => parseTaskFile(join(doneTasksDir, f))).filter(Boolean)
      .filter(t => t.completedAt && (Date.now() - new Date(t.completedAt).getTime()) < 24 * 3600 * 1000)
      .sort((a, b) => (b.completedAt || '').localeCompare(a.completedAt || ''))
      .slice(0, 5)
  : [];

const now = new Date();
const generatedIso = now.toISOString();
const generatedKst = now.toLocaleString('ko-KR', {
  timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'medium',
});

// ─── HTML 생성 ────────────────────────────────────────────────

const refresh = parseInt(process.env.REFRESH_SECONDS || '60', 10);
const phaseIcon = {
  '0': '🌱', 'A': '📐', 'A_DONE': '📐', 'B': '🔨', 'B_DONE': '🔨',
  'C': '🔍', 'C_DONE': '🔍', 'DONE': '✅',
}[harnessState.currentPhase] || '◌';

const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<meta http-equiv="refresh" content="${refresh}" />
<title>magicJar — 상태 대시보드 v2</title>
<style>
:root {
  --bg:#0f0e17; --panel:#1a1925; --panel-2:#232133; --text:#fffffe;
  --muted:#a7a9be; --accent:#ff8906; --lilac:#a786df;
  --green:#2cb67d; --red:#e53170; --line:#393952; --code-bg:#0a0915;
}
@media (prefers-color-scheme: light) {
  :root {
    --bg:#fffffe; --panel:#f7f5fb; --panel-2:#efeaf6; --text:#2e2f3e;
    --muted:#6f6c90; --accent:#cc6c00; --lilac:#7a4fd9;
    --green:#1e8a5d; --red:#c41e5d; --line:#d8d4e2; --code-bg:#e8e4ee;
  }
}
* { box-sizing: border-box; }
html, body { margin:0; padding:0; background:var(--bg); color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Pretendard","Apple SD Gothic Neo","Noto Sans KR","Segoe UI",sans-serif;
  line-height:1.55; -webkit-font-smoothing:antialiased; }
body { padding:28px 16px 80px; }
.wrap { max-width:1180px; margin:0 auto; }
header.hero {
  border:1px solid var(--line); border-radius:16px; padding:22px 28px;
  background:linear-gradient(135deg,var(--panel),var(--panel-2));
  margin-bottom:16px; display:grid; grid-template-columns:1fr auto; gap:20px; align-items:center;
}
header h1 { margin:0; font-size:22px; letter-spacing:-0.02em; }
header h1 .accent { color:var(--accent); }
header .sub { color:var(--muted); font-size:13px; margin-top:4px; }
.phase-pill {
  display:inline-flex; align-items:center; gap:8px; padding:8px 14px; border-radius:999px;
  background:rgba(255,137,6,0.12); border:1px solid var(--accent); color:var(--accent);
  font-weight:700; font-size:14px;
}
.refresh-info { font-size:11px; color:var(--muted); text-align:right; }
.refresh-btn {
  display:inline-flex; align-items:center; gap:6px; padding:6px 12px; font-size:12px;
  font-weight:600; background:var(--accent); color:var(--bg); border:none; border-radius:6px;
  cursor:pointer; margin-top:6px; font-family:inherit;
}
.refresh-btn:hover { opacity:0.85; }
.staleness-banner {
  border-radius:10px; padding:12px 16px; margin-bottom:16px; font-size:13px;
  display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap;
}
.staleness-banner code {
  background:var(--code-bg); padding:2px 7px; border-radius:4px;
  font-family:"SF Mono",Menlo,monospace; font-size:12px; color:var(--text);
}
.staleness-banner.fresh { background:rgba(44,182,125,0.12); border:1px solid var(--green); color:var(--green); }
.staleness-banner.cooling { background:rgba(255,137,6,0.12); border:1px solid var(--accent); color:var(--accent); }
.staleness-banner.stale { background:rgba(229,49,112,0.15); border:1px solid var(--red); color:var(--red); }
.staleness-banner .age { font-weight:700; }
.staleness-banner .help-cmd { font-family:"SF Mono",Menlo,monospace; font-size:11.5px; color:var(--text); }
.grid { display:grid; gap:16px; grid-template-columns:repeat(12,1fr); }
.card { border:1px solid var(--line); border-radius:12px; background:var(--panel); padding:18px 20px; }
.card h2 {
  margin:0 0 12px; font-size:14px; letter-spacing:0.02em; text-transform:uppercase;
  color:var(--muted); display:flex; align-items:center; justify-content:space-between;
}
.card h2 .count {
  background:var(--panel-2); border-radius:999px; padding:2px 9px;
  font-size:11px; color:var(--text); font-weight:600; text-transform:none; letter-spacing:0;
}
.span-12 { grid-column:span 12; } .span-8 { grid-column:span 8; }
.span-6 { grid-column:span 6; } .span-4 { grid-column:span 4; }
@media (max-width:900px) { .span-8,.span-6,.span-4 { grid-column:span 12; } }

/* Task cards */
.task-list { list-style:none; padding:0; margin:0; display:grid; gap:10px; }
.task-card {
  border:1px solid var(--line); border-radius:10px; padding:14px 16px;
  background:var(--panel-2); position:relative;
}
.task-card.running { border-left:3px solid var(--accent); }
.task-card.blocked { border-left:3px solid var(--red); background:rgba(229,49,112,0.06); }
.task-card.done { border-left:3px solid var(--green); opacity:0.85; }
.task-card .top { display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; }
.task-card .id { font-family:"SF Mono",Menlo,monospace; font-size:11.5px; color:var(--lilac); }
.task-card .agent { font-weight:700; color:var(--accent); font-size:13px; }
.task-card .model {
  font-size:10.5px; padding:1px 6px; border-radius:3px;
  background:var(--code-bg); color:var(--text); font-family:"SF Mono",Menlo,monospace;
}
.task-card .status-pill {
  font-size:10px; padding:2px 7px; border-radius:999px; font-weight:700;
  letter-spacing:0.04em; text-transform:uppercase;
}
.task-card .status-pill.running { background:rgba(255,137,6,0.18); color:var(--accent); }
.task-card .status-pill.blocked { background:rgba(229,49,112,0.18); color:var(--red); }
.task-card .status-pill.done { background:rgba(44,182,125,0.18); color:var(--green); }
.task-card .branch { font-family:"SF Mono",Menlo,monospace; font-size:11.5px; color:var(--muted); margin-top:4px; }
.task-card .goal { font-size:13px; color:var(--text); margin:6px 0 8px; }
.task-card .progress-row { display:flex; align-items:center; gap:10px; margin:4px 0 6px; }
.task-card .progress-bar { flex:1; height:8px; border-radius:4px; background:var(--code-bg); overflow:hidden; }
.task-card .progress-fill {
  height:100%; background:linear-gradient(90deg,var(--lilac),var(--accent)); transition:width 0.3s ease;
}
.task-card .progress-text { font-size:11px; color:var(--muted); white-space:nowrap; }
.task-card .meta-row { font-size:11px; color:var(--muted); display:flex; gap:10px; flex-wrap:wrap; }
.task-card .pr-link { color:var(--lilac); }

/* Worktree list */
.wt-list { list-style:none; padding:0; margin:0; }
.wt-list li {
  border:1px solid var(--line); border-radius:9px; padding:10px 14px;
  background:var(--panel-2); margin-bottom:8px;
  display:grid; grid-template-columns:auto 1fr auto; gap:10px; align-items:center;
}
.wt-list li:last-child { margin-bottom:0; }
.wt-dot { width:10px; height:10px; border-radius:50%; flex-shrink:0; }
.wt-fresh { background:var(--green); box-shadow:0 0 0 4px rgba(44,182,125,0.18); }
.wt-recent { background:var(--accent); box-shadow:0 0 0 4px rgba(255,137,6,0.18); }
.wt-cooling { background:var(--lilac); }
.wt-stale { background:var(--red); }
.wt-list .branch { font-family:"SF Mono",Menlo,monospace; font-size:12px; color:var(--lilac); }
.wt-list .subject {
  font-size:12.5px; color:var(--text);
  white-space:nowrap; overflow:hidden; text-overflow:ellipsis;
}
.wt-list .time { font-size:11px; color:var(--muted); }

/* PR cards */
.pr-list { list-style:none; padding:0; margin:0; }
.pr-list li {
  border:1px solid var(--line); border-radius:9px; padding:10px 14px;
  background:var(--panel-2); margin-bottom:8px;
}
.pr-list li:last-child { margin-bottom:0; }
.pr-num { font-family:"SF Mono",Menlo,monospace; color:var(--accent); font-weight:700; }
.pr-title { font-size:13px; color:var(--text); margin:4px 0; }
.pr-meta { font-size:11px; color:var(--muted); display:flex; gap:10px; flex-wrap:wrap; }
.pr-meta .branch { font-family:"SF Mono",Menlo,monospace; color:var(--lilac); }
.pr-empty { color:var(--muted); font-size:13px; padding:8px 0; }
.pr-empty code { background:var(--code-bg); padding:1px 6px; border-radius:4px; font-family:"SF Mono",Menlo,monospace; font-size:11px; }

/* Commit list */
.commit-list { list-style:none; padding:0; margin:0; font-family:"SF Mono",Menlo,monospace; font-size:12px; }
.commit-list li {
  padding:6px 0; border-bottom:1px dashed var(--line);
  display:grid; grid-template-columns:70px auto 1fr; gap:12px; align-items:baseline;
}
.commit-list li:last-child { border-bottom:none; }
.commit-list .sha { color:var(--lilac); }
.commit-list .when { color:var(--muted); font-size:11px; }
.commit-list .msg { color:var(--text); }

/* Phase timeline */
.phase-row { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; }
.phase-cell {
  border:1px solid var(--line); border-radius:8px; padding:10px 12px;
  background:var(--panel-2); position:relative;
}
.phase-cell.done { border-color:var(--green); }
.phase-cell.active { border-color:var(--accent); box-shadow:0 0 0 2px rgba(255,137,6,0.15); }
.phase-cell .label { font-size:10px; color:var(--muted); text-transform:uppercase; letter-spacing:0.08em; }
.phase-cell .title { font-weight:700; font-size:13px; margin:3px 0 4px; }
.phase-cell .body { font-size:11.5px; color:var(--muted); }
.phase-cell.active::after {
  content:"●"; color:var(--accent); position:absolute; top:8px; right:10px;
  font-size:10px; animation:pulse 1.5s infinite;
}
.phase-cell.done::after {
  content:"✓"; color:var(--green); position:absolute; top:8px; right:10px;
  font-size:13px; font-weight:700;
}
@keyframes pulse { 0%,100%{opacity:1;} 50%{opacity:0.3;} }

/* Quick stats */
.stats { display:grid; grid-template-columns:repeat(4,1fr); gap:10px; margin-top:4px; }
.stat {
  background:var(--panel-2); border-radius:9px; padding:12px 14px;
  border-left:3px solid var(--accent);
}
.stat .v { font-size:20px; font-weight:800; color:var(--text); }
.stat .l { font-size:11px; color:var(--muted); margin-top:2px; }
.stat.green { border-left-color:var(--green); }
.stat.red { border-left-color:var(--red); }
.stat.lilac { border-left-color:var(--lilac); }

/* Agents */
.agent-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:8px; }
.agent-card {
  border:1px solid var(--line); border-radius:8px; padding:10px 12px;
  background:var(--panel-2);
}
.agent-card .n { font-weight:700; color:var(--accent); font-size:13px; }
.agent-card .m {
  font-size:10px; padding:1px 5px; border-radius:3px; background:var(--code-bg);
  font-family:"SF Mono",Menlo,monospace; color:var(--text); margin-left:6px;
}
.agent-card .d {
  font-size:11.5px; color:var(--muted); margin-top:3px;
  display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical;
  overflow:hidden; line-height:1.4;
}

footer { text-align:center; color:var(--muted); font-size:11px; margin-top:32px; }
footer a { color:var(--lilac); }
footer code { background:var(--code-bg); padding:1px 6px; border-radius:4px; font-family:"SF Mono",Menlo,monospace; }
</style>
</head>
<body>
<div class="wrap">

  <header class="hero">
    <div>
      <h1><span class="accent">magicJar</span> · 상태 대시보드 v2</h1>
      <div class="sub">생성 시각: <strong>${escapeHtml(generatedKst)}</strong> (KST)</div>
      <div class="sub" id="age-line" style="margin-top:4px">데이터 신선도: 계산 중…</div>
    </div>
    <div>
      <div class="phase-pill">${phaseIcon} Phase ${escapeHtml(harnessState.currentPhase || '?')}</div>
      <button class="refresh-btn" onclick="location.reload()" title="dashboard.html을 브라우저에서 다시 읽기">
        ↻ 페이지 새로고침
      </button>
      <div class="refresh-info" style="margin-top:6px">
        브라우저는 ${refresh}초마다 자동 reload
      </div>
    </div>
  </header>

  <div id="staleness" class="staleness-banner cooling" style="display:none">
    <div><span class="age">데이터 신선도 확인 중</span></div>
  </div>

  <script>
    const GENERATED_AT = new Date(${JSON.stringify(generatedIso)});
    function fmtAge(sec) {
      if (sec < 60) return Math.round(sec) + '초 전';
      if (sec < 3600) return Math.round(sec / 60) + '분 전';
      return (sec / 3600).toFixed(1) + '시간 전';
    }
    function tickAge() {
      const sec = (Date.now() - GENERATED_AT.getTime()) / 1000;
      const ageStr = fmtAge(sec);
      const line = document.getElementById('age-line');
      if (line) line.innerHTML = '데이터 신선도: <strong>' + ageStr + '</strong>';
      const banner = document.getElementById('staleness');
      if (!banner) return;
      banner.style.display = '';
      let cls, html;
      if (sec < 90) {
        cls = 'fresh';
        html = '<span><span class="age">✓ 신선 (' + ageStr + ')</span> — watcher 또는 새로고침이 정상 동작 중입니다.</span>';
      } else if (sec < 300) {
        cls = 'cooling';
        html = '<span><span class="age">~~ 식는 중 (' + ageStr + ')</span> — 곧 갱신되거나, watcher가 안 떠있을 수 있어요.</span>'
             + '<span class="help-cmd">백그라운드 갱신: <code>nohup npm run status:watch &gt; /tmp/magicjar-status.log 2&gt;&amp;1 &amp;</code></span>';
      } else {
        cls = 'stale';
        html = '<span><span class="age">✗ 오래됨 (' + ageStr + ')</span> — 데이터가 신선하지 않습니다.</span>'
             + '<span class="help-cmd"><code>nohup npm run status:watch &gt; /tmp/magicjar-status.log 2&gt;&amp;1 &amp;</code></span>';
      }
      banner.className = 'staleness-banner ' + cls;
      banner.innerHTML = html;
    }
    tickAge();
    setInterval(tickAge, 5000);
  </script>

  <!-- Quick stats -->
  <div class="card span-12" style="margin-bottom:16px">
    <div class="stats">
      <div class="stat"><div class="v">${agents.length}</div><div class="l">에이전트</div></div>
      <div class="stat lilac"><div class="v">${activeTasks.length}</div><div class="l">활성 Task</div></div>
      <div class="stat ${openPRs.length > 0 ? '' : 'green'}"><div class="v">${openPRs.length}</div><div class="l">열린 PR</div></div>
      <div class="stat green"><div class="v">${adrCount}</div><div class="l">ADR</div></div>
    </div>
  </div>

  <div class="grid">

    <div class="card span-12">
      <h2>Phase 진행</h2>
      <div class="phase-row">
        ${['0','A','B','C'].map(p => {
          const isActive = harnessState.currentPhase === p;
          const isDone = harnessState.phaseHistory?.some(h =>
            (h.phase === p || h.phase === `${p}_DONE`) && h.finishedAt
          );
          const cls = isActive ? 'active' : isDone ? 'done' : '';
          const labels = { '0': '컨텍스트', 'A': '설계', 'B': '구현', 'C': 'QA 통합' };
          return `<div class="phase-cell ${cls}">
            <div class="label">Phase ${p}</div>
            <div class="title">${labels[p]}</div>
            <div class="body">${isActive ? '진행 중' : isDone ? '완료' : '대기'}</div>
          </div>`;
        }).join('')}
      </div>
    </div>

    <!-- 활성 Task (ADR 0018) -->
    <div class="card span-12">
      <h2>활성 Task <span class="count">${activeTasks.length}</span></h2>
      ${activeTasks.length === 0
        ? '<div class="pr-empty">활성 task 없음 — <code>node scripts/task.mjs start --agent ... --goal ...</code></div>'
        : `<ul class="task-list">
        ${activeTasks.map(t => {
          const pct = t.checklistAll > 0 ? Math.round((t.checklistDone / t.checklistAll) * 100) : 0;
          const statusClass = (t.status || 'RUNNING').toLowerCase();
          return `<li class="task-card ${statusClass}">
            <div class="top">
              <span class="agent">${escapeHtml(t.agent || '?')}</span>
              <span class="model">${escapeHtml(t.model || '?')}</span>
              <span class="status-pill ${statusClass}">${escapeHtml(t.status || 'RUNNING')}</span>
              <span class="id">${escapeHtml(t.id || '')}</span>
            </div>
            <div class="branch">${escapeHtml(t.branch || '?')}</div>
            <div class="goal">🎯 ${escapeHtml(t.goal || '(목표 미기재)')}</div>
            <div class="progress-row">
              <div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>
              <span class="progress-text">${t.checklistDone}/${t.checklistAll} (${pct}%)</span>
            </div>
            <div class="meta-row">
              <span>시작 ${escapeHtml(relTime(t.startedAt))}</span>
              ${t.lastActivity ? `<span>· 최근: ${escapeHtml(t.lastActivity).slice(0, 80)}</span>` : ''}
              ${t.pr ? `<span class="pr-link">· PR ${escapeHtml(t.pr)}</span>` : ''}
            </div>
          </li>`;
        }).join('')}
      </ul>`}
    </div>

    ${doneTasksRecent.length > 0 ? `
    <div class="card span-12">
      <h2>최근 완료된 Task (24시간) <span class="count">${doneTasksRecent.length}</span></h2>
      <ul class="task-list">
        ${doneTasksRecent.map(t => `<li class="task-card done">
          <div class="top">
            <span class="agent">${escapeHtml(t.agent || '?')}</span>
            <span class="model">${escapeHtml(t.model || '?')}</span>
            <span class="status-pill done">DONE</span>
            <span class="id">${escapeHtml(t.id || '')}</span>
          </div>
          <div class="goal">${escapeHtml(t.goal || '').slice(0, 120)}</div>
          <div class="meta-row">
            <span>완료 ${escapeHtml(relTime(t.completedAt))}</span>
            ${t.pr ? `<span class="pr-link">· PR ${escapeHtml(t.pr)}</span>` : ''}
          </div>
        </li>`).join('')}
      </ul>
    </div>` : ''}

    ${worktrees.length > 0 ? `
    <div class="card span-8">
      <h2>worktree (예외 사용) <span class="count">${worktrees.length}</span></h2>
      <ul class="wt-list">
        ${worktrees.map(w => `<li>
          <span class="wt-dot wt-${w.stale}"></span>
          <div>
            <div class="branch">${escapeHtml(w.branch)}</div>
            <div class="subject">${escapeHtml(w.lastSubject || '(커밋 없음)')}</div>
          </div>
          <div class="time">${escapeHtml(relTime(w.lastIso))}</div>
        </li>`).join('')}
      </ul>
      <div style="font-size:11px; color:var(--muted); margin-top:8px">
        v2 정책(ADR 0013): 단일 worktree 기본. worktree가 보이면 정리 대상이거나 예외 케이스.
      </div>
    </div>` : ''}

    <div class="card ${worktrees.length > 0 ? 'span-4' : 'span-4'}">
      <h2>열린 PR <span class="count">${openPRs.length}</span></h2>
      ${openPRs.length === 0 ? '<div class="pr-empty">열린 PR 없음</div>' : `
      <ul class="pr-list">
        ${openPRs.map(p => `<li>
          <div><span class="pr-num">#${p.number}</span> ${p.isDraft ? '<span style="color:var(--muted);font-size:11px">(draft)</span>' : ''}</div>
          <div class="pr-title">${escapeHtml(p.title)}</div>
          <div class="pr-meta">
            <span class="branch">${escapeHtml(p.headRefName)} → ${escapeHtml(p.baseRefName)}</span>
            <span>${escapeHtml(relTime(p.updatedAt || p.createdAt))}</span>
          </div>
        </li>`).join('')}
      </ul>`}
    </div>

    <div class="card span-6">
      <h2>최근 main 커밋 <span class="count">${mainLog.length}</span></h2>
      <ul class="commit-list">
        ${mainLog.map(c => `<li>
          <span class="sha">${escapeHtml(c.hash)}</span>
          <span class="when">${escapeHtml(relTime(c.iso))}</span>
          <span class="msg">${escapeHtml(c.subject)}</span>
        </li>`).join('')}
      </ul>
    </div>

    <div class="card span-6">
      <h2>최근 머지된 PR <span class="count">${mergedPRs.length}</span></h2>
      ${mergedPRs.length === 0 ? '<div class="pr-empty">머지된 PR 없음</div>' : `
      <ul class="pr-list">
        ${mergedPRs.map(p => `<li>
          <div><span class="pr-num">#${p.number}</span></div>
          <div class="pr-title">${escapeHtml(p.title)}</div>
          <div class="pr-meta">
            <span class="branch">${escapeHtml(p.headRefName)} → ${escapeHtml(p.baseRefName)}</span>
            <span>${escapeHtml(relTime(p.mergedAt))}</span>
          </div>
        </li>`).join('')}
      </ul>`}
    </div>

    <div class="card span-4">
      <h2>QA 상태 (On-Demand)</h2>
      <div class="stats" style="grid-template-columns:1fr 1fr;">
        <div class="stat ${qaReports.length > 0 ? 'green' : ''}">
          <div class="v">${qaReports.length}</div><div class="l">보고서</div>
        </div>
        <div class="stat ${qaIssuesOpen > 0 ? 'red' : 'green'}">
          <div class="v">${qaIssuesOpen}</div><div class="l">OPEN 이슈</div>
        </div>
      </div>
      <div style="font-size:11.5px; color:var(--muted); margin-top:10px;">
        FIXED ${qaIssuesFixed}건 · ADR 0016 — 7개 트리거 시에만 호출
      </div>
    </div>

    <div class="card span-8">
      <h2>에이전트 <span class="count">${agents.length}</span></h2>
      <div class="agent-grid">
        ${agents.map(a => `<div class="agent-card">
          <div><span class="n">${escapeHtml(a.name)}</span><span class="m">${escapeHtml(a.model || 'opus')}</span></div>
          <div class="d">${escapeHtml(a.description || '').slice(0, 200)}</div>
        </div>`).join('')}
      </div>
    </div>

  </div>

  <footer>
    데이터 소스 — <code>_workspace/_harness-state.json</code> · <code>_workspace/tasks/*.md</code> · <code>git log</code> ·
    <code>gh pr list</code> · <code>.claude/agents/*.md</code> · <code>.claude/worktrees/*</code> · <code>_workspace/qa/*</code><br>
    워크플로우 다이어그램 → <a href="arch/workflow.html">arch/workflow.html</a> · 운영 가이드 → <a href="arch/branching-and-qa-gates.md">arch/branching-and-qa-gates.md</a>
  </footer>

</div>
</body>
</html>
`;

const outDir = '_workspace';
if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true });
writeFileSync(OUT, html, 'utf-8');

const size = (html.length / 1024).toFixed(1);
console.log(`✓ ${OUT}  (${size} KB · 다음 갱신 ${refresh}s)`);
console.log(`  open ${OUT}`);
