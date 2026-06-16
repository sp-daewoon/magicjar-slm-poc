/**
 * Playwright config — magicJar qa Visual 첫 셋업 (ADR 0094 D-7 + spec 2026-05-21-qa-visual-uplift)
 *
 * Stage 1 Round 1 dispatch 본문 LOCK:
 * - testDir: ./tests/e2e
 * - fullyParallel: false (운영 부담 ↓ + Visual diff 재현성 ↑)
 * - forbidOnly: CI 환경 only
 * - retries: 0 (사용자 메모리 feedback_no_github_actions 정합 — local 단독 실행)
 * - baseURL: http://localhost:3000 (docker compose frontend 컨테이너)
 * - trace: on-first-retry, screenshot: only-on-failure
 * - toHaveScreenshot maxDiffPixelRatio 0.01 (1% threshold)
 * - chromium 단일 브라우저 (운영 부담 ↓ — Firefox·WebKit·multi-viewport 확장은 P1 backlog)
 *
 * spec §2-4 본문 대비 dispatch LOCK 차이:
 * - spec multi-viewport(desktop 1440x900 + mobile Pixel 5) → dispatch single chromium-desktop (P0 운영 부담 회피)
 * - spec fullyParallel true → dispatch false (Visual baseline 재현성 우선)
 * - spec retries CI 2 → dispatch 0 (사용자 메모리 정합 — GHA 폐기)
 *
 * 사용법:
 *   pnpm qa:visual              # 5 시나리오 실행 (HTML 보고서)
 *   pnpm qa:visual:update       # baseline 스크린샷 갱신
 *   pnpm qa:e2e                 # @e2e 태그 시나리오만 실행 (P1 — 현재 0건)
 */
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: process.env.MAGICJAR_FRONTEND_URL ?? 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
  },
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.01 },
  },
  projects: [
    {
      name: 'chromium',
      use: {
        channel: 'chromium',
        viewport: { width: 1440, height: 900 },
      },
    },
  ],
  // docker compose가 frontend 컨테이너를 이미 띄우므로 webServer는 사용하지 않음
  webServer: undefined,
})
