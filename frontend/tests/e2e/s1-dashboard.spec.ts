/**
 * S-1 Dashboard 진입 시각 회귀 baseline
 *
 * 검증:
 * - `/` 진입 → 헤더 STOMP·KIS WS indicator 정상 표시
 * - WatchlistPanel 렌더
 * - AccountSummary (T-ACCT Phase 4 — ADR 0081) 렌더
 * - 전체 페이지 baseline 스크린샷 비교 (maxDiffPixelRatio 0.01)
 *
 * baseline 생성: `pnpm qa:visual:update`
 * 회귀 검증: `pnpm qa:visual`
 *
 * 사용자 dogfooding 회귀 가드:
 * - PR #595 SymbolMetaService currentPrice 매핑 hotfix
 * - PR #597 좌측 시세창 가격 path = candle DB 1d single source
 */
import { expect, test } from '@playwright/test'

test.describe('S-1 Dashboard 진입', () => {
  test('대시보드 전체 baseline', async ({ page }) => {
    await page.goto('/')

    // 헤더 + 메인 컨텐츠 로드 대기 (STOMP·KIS WS indicator + AccountSummary 안정화)
    await page.waitForLoadState('networkidle')

    // 전체 페이지 baseline (애니메이션·시계 등 동적 요소는 mask 처리로 P1 확장)
    await expect(page).toHaveScreenshot('s1-dashboard-default.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
