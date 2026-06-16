/**
 * W4-2 /market/trend 페이지 시각 회귀 baseline (R3 qa §케이스 2)
 *
 * 검증:
 * - /market/trend 진입 → 3 패널 grid 노출 (좌 차트 + 중 추세 요약 + 우 섹터 ranking)
 * - LWC v4 차트 컨테이너 마운트
 * - 섹터 ranking todayTop / longTop 탭 노출
 * - graceful empty/error 한국어 처리
 *
 * 회귀 가드: PR #680 (W4 R2 frontend) /market/trend 신규 라우트.
 */
import { expect, test } from '@playwright/test'

test.describe('W4-2 /market/trend 페이지', () => {
  test('진입 — 3 패널 grid + 차트 + 섹터 ranking 탭 노출 baseline', async ({ page }) => {
    await page.goto('/market/trend')
    await page.waitForLoadState('networkidle')

    // 페이지 메인 콘텐츠 노출 대기
    // (정확한 selector는 backend 데이터 응답 여부에 의존하므로 graceful path 모두 흡수)
    await page.waitForTimeout(2000)

    // 베이스라인 스크린샷 — 좌·중·우 3 패널 + 4 카드 토글 + 섹터 ranking 탭 시각 정합
    await expect(page).toHaveScreenshot('w4-2-market-trend-page.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
