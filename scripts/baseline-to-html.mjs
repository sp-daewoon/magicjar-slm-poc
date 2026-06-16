#!/usr/bin/env node
// scripts/baseline-to-html.mjs
// _workspace/baseline/<version>/*.md → html/*.html
// 사용: node scripts/baseline-to-html.mjs [version]
//   version 인자 생략 시 _workspace/baseline/ 안의 가장 최신 vX.Y.Z 폴더 자동 선택
// marked + mermaid CDN 클라이언트 사이드 렌더링 + 단일 파일 inline embed

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from 'fs';
import { execSync } from 'child_process';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const BASELINE_ROOT = join(ROOT, '_workspace/baseline');

function resolveVersion() {
  const arg = process.argv[2];
  if (arg) return arg.replace(/^v/, 'v');
  const dirs = readdirSync(BASELINE_ROOT, { withFileTypes: true })
    .filter((d) => d.isDirectory() && /^v\d+\.\d+\.\d+$/.test(d.name))
    .map((d) => d.name)
    .sort((a, b) => {
      const pa = a.replace(/^v/, '').split('.').map(Number);
      const pb = b.replace(/^v/, '').split('.').map(Number);
      for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pb[i] - pa[i];
      return 0;
    });
  if (dirs.length === 0) throw new Error('baseline 폴더 없음 — _workspace/baseline/vX.Y.Z 필요');
  return dirs[0];
}

const VERSION = resolveVersion();
const SRC = join(BASELINE_ROOT, VERSION);
const DEST = join(SRC, 'html');

const NAV_ITEMS = [
  { file: 'index.html', label: '📁 인덱스' },
  { file: '01-architecture.html', label: '01 Architecture' },
  { file: '02-data-flow.html', label: '02 Data Flow' },
  { file: '03-use-sequences.html', label: '03 Use Sequences' },
  { file: '04-feature-spec.html', label: '04 Feature Spec' },
  { file: '05-ui-inventory.html', label: '05 UI Inventory' },
  { file: '06-history-traceability.html', label: '06 History' },
  { file: '07-policy-evolution.html', label: '07 Policy Evolution' },
];

function navHtml(active) {
  return `<nav>${NAV_ITEMS.map(it =>
    `<a href="${it.file}"${active === it.file ? ' class="active"' : ''}>${it.label}</a>`
  ).join('')}</nav>`;
}

function template(title, mdContent, activeFile) {
  // </script> 시퀀스만 안전 처리 (md 안에 들어가면 script 태그가 닫힘)
  const safeMd = mdContent.replace(/<\/script>/gi, '<\\/script>');
  return `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} — magicJar ${VERSION} Baseline</title>
<style>
  :root { --fg: #1a1a1a; --bg: #ffffff; --muted: #6b7280; --accent: #4a90e2; --code-bg: #f5f5f5; --border: #e5e7eb; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Pretendard', 'Apple SD Gothic Neo', sans-serif; max-width: 1180px; margin: 1.5rem auto; padding: 0 1.2rem; line-height: 1.65; color: var(--fg); background: var(--bg); }
  h1 { font-size: 1.8rem; color: #0d2438; border-bottom: 2px solid var(--accent); padding-bottom: 0.4rem; margin-top: 0; }
  h2 { font-size: 1.4rem; color: #0d2438; border-bottom: 1px solid var(--border); padding-bottom: 0.3rem; margin-top: 2rem; }
  h3 { font-size: 1.15rem; color: #0d2438; margin-top: 1.6rem; }
  h4 { font-size: 1rem; color: #34506e; }
  p, li { font-size: 0.95rem; }
  pre { background: var(--code-bg); padding: 0.8rem 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.83rem; line-height: 1.5; border: 1px solid var(--border); }
  pre code { background: transparent; padding: 0; font-size: inherit; }
  code { background: #f0f0f0; padding: 0.1em 0.35em; border-radius: 3px; font-size: 0.88em; font-family: 'SF Mono', Menlo, Consolas, monospace; }
  table { border-collapse: collapse; margin: 1rem 0; font-size: 0.88rem; width: 100%; }
  th, td { border: 1px solid var(--border); padding: 0.45rem 0.7rem; text-align: left; vertical-align: top; }
  th { background: #f0f4f8; font-weight: 600; }
  tr:nth-child(even) td { background: #fafafa; }
  blockquote { border-left: 3px solid var(--accent); margin: 1rem 0; padding: 0.3rem 1rem; color: var(--muted); background: #f8fafc; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  hr { border: none; border-top: 1px solid var(--border); margin: 2rem 0; }
  .mermaid { background: #fafafa; padding: 1rem; border-radius: 8px; margin: 1.2rem 0; border: 1px solid var(--border); text-align: center; overflow-x: auto; }
  .mermaid svg { max-width: 100%; height: auto; }
  nav { background: #f0f4f8; padding: 0.8rem; border-radius: 8px; margin-bottom: 2rem; border: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
  nav a { display: inline-block; margin: 0.15rem 0.3rem 0.15rem 0; padding: 0.35rem 0.7rem; background: white; border-radius: 5px; text-decoration: none; color: #0d2438; font-size: 0.85rem; border: 1px solid var(--border); transition: all 0.15s; }
  nav a:hover { background: var(--accent); color: white; border-color: var(--accent); text-decoration: none; }
  nav a.active { background: var(--accent); color: white; border-color: var(--accent); font-weight: 600; }
  .footer { margin-top: 4rem; padding: 1rem 0; border-top: 1px solid var(--border); color: var(--muted); font-size: 0.82rem; text-align: center; }
  ul, ol { padding-left: 1.5rem; }
  li > p { margin: 0.3rem 0; }
</style>
</head>
<body>
${navHtml(activeFile)}
<main id="content">로딩 중…</main>
<div class="footer">magicJar ${VERSION} Baseline · ${new Date().toISOString().slice(0,10)} 생성 · 더블클릭으로 열람</div>

<script src="https://cdn.jsdelivr.net/npm/marked@11.1.1/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.6.1/dist/mermaid.min.js"></script>
<script id="md-source" type="text/markdown">${safeMd}</script>
<script>
(async function() {
  mermaid.initialize({
    startOnLoad: false,
    theme: 'default',
    flowchart: { useMaxWidth: true, htmlLabels: true },
    sequence: { useMaxWidth: true },
    securityLevel: 'loose',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Pretendard", "Apple SD Gothic Neo", sans-serif',
  });

  const md = document.getElementById('md-source').textContent;
  let html = marked.parse(md, { gfm: true, breaks: false });

  // \`\`\`mermaid 블록을 mermaid div로 변환 (entity decode)
  html = html.replace(/<pre><code class="language-mermaid">([\\s\\S]*?)<\\/code><\\/pre>/g, function(_, code) {
    const decoded = code
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'");
    return '<pre class="mermaid">' + decoded + '</pre>';
  });

  document.getElementById('content').innerHTML = html;

  // mermaid 렌더링
  try {
    await mermaid.run({ querySelector: '.mermaid' });
  } catch (e) {
    console.error('mermaid render error:', e);
  }
})();
</script>
</body>
</html>`;
}

if (!existsSync(DEST)) mkdirSync(DEST, { recursive: true });

const mdFiles = readdirSync(SRC).filter(f => f.endsWith('.md'));

for (const file of mdFiles) {
  const content = readFileSync(join(SRC, file), 'utf-8');
  const baseName = file.replace('.md', '');
  const htmlName = file === 'README.md' ? 'index.html' : `${baseName}.html`;
  const title = file === 'README.md' ? '인덱스' : baseName;
  const html = template(title, content, htmlName);
  writeFileSync(join(DEST, htmlName), html);
  console.log(`✓ ${file} → html/${htmlName}`);
}

console.log(`\n✓ 변환 완료: ${mdFiles.length} 파일`);
console.log(`  열람: open ${DEST}/index.html`);

// README "Releases & Baseline 진화" 매트릭스 자동 갱신 (ADR 0052 §X 정합)
const SYNC_SCRIPT = join(ROOT, 'scripts/sync-readme-releases.mjs');
if (existsSync(SYNC_SCRIPT)) {
  try {
    console.log('\n▶ README 매트릭스 자동 갱신 …');
    const out = execSync(`node ${SYNC_SCRIPT}`, { encoding: 'utf8', cwd: ROOT });
    console.log(out.split('\n').filter(l => l).slice(-1).join('\n'));
  } catch (e) {
    console.log('  (sync-readme-releases.mjs fail — skip)');
  }
}
