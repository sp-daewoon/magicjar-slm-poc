/**
 * S-2 Orders 페이지 시각 회귀 baseline
 *
 * 검증:
 * - `/orders` 진입 → 주문 목록 + 종목명 표시
 * - 사용자 dogfooding 회귀 가드 (PR #631 — 종목명 mapping 결함 hotfix)
 * - 전체 페이지 baseline 스크린샷 비교
 *
 * E2E 모의 주문 발사 → 잔고 갱신 STOMP 수신 → UI 반영 evidence 흐름은 P1 확장 (S-2 E2E).
 * 본 P0는 Visual baseline 단독.
 */
import { expect, test } from '@playwright/test'

test.describe('S-2 Orders 페이지', () => {
  test('주문 내역 페이지 baseline', async ({ page }) => {
    await page.goto('/orders')
    await page.waitForLoadState('networkidle')

    await expect(page).toHaveScreenshot('s2-orders-default.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
