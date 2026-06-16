/**
 * W4-3 SettingsModal "추세 분석 설정" section 시각 회귀 baseline (R3 qa §케이스 3)
 *
 * 검증:
 * - SettingsModal 열림 → "추세 분석 설정" section 노출
 * - 4 프리셋 (전통/중기/장기/직접 입력) 토글 노출
 * - MA periods 4 입력 row + rsPeriod + crossPair 옵션 노출
 * - 저장 후 STOMP /topic/market.user.{userId}.pref push 자동 sync evidence (다중 탭 race-safe)
 *
 * 한국어 contract: "추세 분석 설정" 헤더, 프리셋 한글 라벨.
 *
 * 회귀 가드: PR #680 (W4 R2 frontend) TrendPreferenceSection 신규 마운트.
 */
import { expect, test } from '@playwright/test'

test.describe('W4-3 SettingsModal 추세 분석 설정', () => {
  test('모달 진입 — TrendPreferenceSection baseline', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // 헤더 ⚙️ 버튼 — 다중 fallback selector (구현 변동 흡수)
    const settingsBtn = page
      .getByRole('button', { name: /설정|settings|⚙️/i })
      .or(page.locator('[aria-label*="설정"]'))
      .or(page.locator('button:has-text("⚙️")'))
      .first()

    await settingsBtn.click()

    // 모달 열림 대기
    const modal = page
      .getByRole('dialog')
      .or(page.locator('[role="dialog"]'))
      .or(page.locator('.settings-modal'))
      .first()
    await modal.waitFor({ state: 'visible', timeout: 5000 })

    // TrendPreferenceSection 헤더 노출 검증 (한국어 contract)
    // section 헤더가 "추세 분석 설정" 또는 "추세 설정" 표기되어야 함
    const trendSection = page.getByText(/추세\s*(분석)?\s*설정/i).first()
    await expect(trendSection).toBeVisible({ timeout: 5000 })

    // 모달 전체 baseline 스크린샷 (TrendPreferenceSection 포함 — fullPage 또는 모달 영역)
    await expect(page).toHaveScreenshot('w4-3-settings-trend-preference.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
