#!/usr/bin/env node
// 30초마다 render-status.mjs를 다시 실행해 _workspace/dashboard.html을 갱신.

import { spawn } from 'node:child_process';

const INTERVAL = parseInt(process.env.WATCH_INTERVAL || '30', 10) * 1000;

function tick() {
  const t0 = Date.now();
  const child = spawn('node', ['scripts/render-status.mjs'], { stdio: 'inherit' });
  child.on('close', code => {
    const dt = ((Date.now() - t0) / 1000).toFixed(1);
    if (code !== 0) console.error(`✗ render 실패 (${dt}s, code=${code})`);
  });
}

console.log(`magicJar status watcher · ${INTERVAL / 1000}초 간격 (Ctrl+C 종료)`);
tick();
setInterval(tick, INTERVAL);
