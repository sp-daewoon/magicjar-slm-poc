/**
 * S-5 알람센터 시각 회귀 baseline
 *
 * 검증:
 * - 대시보드 진입 → 헤더 🔔 클릭 → NotificationCenter 드롭다운 열림
 * - unread count badge 표시
 * - 드롭다운 baseline 스크린샷 비교
 */
import { expect, test } from '@playwright/test'

test.describe('S-5 알람센터', () => {
  test('NotificationCenter 드롭다운 baseline', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // 헤더 🔔 버튼 — aria-label/title 기반 fallback
    const bellBtn = page
      .getByRole('button', { name: /알람|notification|🔔/i })
      .or(page.locator('[aria-label*="알람"]'))
      .or(page.locator('[aria-label*="notification"]'))
      .or(page.locator('button:has-text("🔔")'))
      .first()

    await bellBtn.click()

    // 드롭다운/팝오버 안정화 대기
    await page.waitForTimeout(500)

    await expect(page).toHaveScreenshot('s5-notification-center.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
