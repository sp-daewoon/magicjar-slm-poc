/**
 * S-3 Positions 페이지 시각 회귀 baseline
 *
 * 검증:
 * - `/positions` 진입 → 보유 포지션 카드 표시
 * - 사용자 dogfooding 회귀 가드 (PR #643 — 보유 포지션 잔고 표시 결함 hotfix)
 * - 전체 페이지 baseline 스크린샷 비교
 */
import { expect, test } from '@playwright/test'

test.describe('S-3 Positions 페이지', () => {
  test('보유 포지션 페이지 baseline', async ({ page }) => {
    await page.goto('/positions')
    await page.waitForLoadState('networkidle')

    await expect(page).toHaveScreenshot('s3-positions-default.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
