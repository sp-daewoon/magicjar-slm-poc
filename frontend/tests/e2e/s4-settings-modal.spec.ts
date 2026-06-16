/**
 * S-4 Settings 모달 시각 회귀 baseline
 *
 * 검증:
 * - 대시보드 진입 → 헤더 ⚙️ 클릭 → SettingsModal 열림
 * - KIS 키 입력 영역 + KillSwitch 토글 표시
 * - 모달 baseline 스크린샷 비교
 *
 * KIS 키 입력 흐름 (저장 → DB AES 암호화 → 토큰 발급) E2E는 P1 확장.
 * 본 P0는 모달 Visual baseline 단독.
 */
import { expect, test } from '@playwright/test'

test.describe('S-4 Settings 모달', () => {
  test('Settings 모달 열림 baseline', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // 헤더 ⚙️ 버튼 — aria-label/title 기반 fallback (구현 변동 흡수)
    const settingsBtn = page
      .getByRole('button', { name: /설정|settings|⚙️/i })
      .or(page.locator('[aria-label*="설정"]'))
      .or(page.locator('button:has-text("⚙️")'))
      .first()

    await settingsBtn.click()

    // 모달 열림 대기 (role=dialog 또는 modal 컨테이너)
    const modal = page
      .getByRole('dialog')
      .or(page.locator('[role="dialog"]'))
      .or(page.locator('.settings-modal'))
      .first()
    await modal.waitFor({ state: 'visible', timeout: 5000 })

    await expect(page).toHaveScreenshot('s4-settings-modal.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
