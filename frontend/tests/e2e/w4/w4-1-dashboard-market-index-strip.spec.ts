/**
 * W4-1 DashboardPage MarketIndexStrip 시각 회귀 baseline (R3 qa §케이스 1)
 *
 * 검증:
 * - `/` 진입 → MarketIndexStrip 4 지수 카드 (KOSPI · KOSDAQ · NASDAQ · S&P 500)
 * - 각 카드 추세 라벨 (UP·SIDEWAYS·DOWN 배지) 또는 "데이터 없음/수집 중" graceful 한국어
 * - MA periods 자막 default (5·20·60) 노출 (사용자 prefer 없을 시)
 * - "시장 지수" heading + "상세 추세 →" 링크 정상
 * - 전체 페이지 baseline 스크린샷 비교
 *
 * 한국어 contract:
 * - "시장 지수" heading
 * - 데이터 empty 시 "시장 지수 데이터 수집 중입니다. 시장 개장 시간을 기다려 주세요."
 *
 * 회귀 가드: PR #680 (W4 R2 frontend) 후속 dogfooding 결함 변환 path.
 */
import { expect, test } from '@playwright/test'

test.describe('W4-1 DashboardPage MarketIndexStrip', () => {
  test('대시보드 진입 — MarketIndexStrip 4 카드 + 추세 라벨 + heading + 상세 링크', async ({ page }) => {
    await page.goto('/')

    // 헤더 + 메인 컨텐츠 로드 + STOMP 안정화 대기
    await page.waitForLoadState('networkidle')

    // MarketIndexStrip section heading 노출 검증
    const strip = page.locator('section[aria-labelledby="market-index-strip-heading"]')
    await expect(strip).toBeVisible({ timeout: 10000 })

    const heading = strip.getByRole('heading', { name: '시장 지수' })
    await expect(heading).toBeVisible()

    // 상세 추세 진입 링크 검증 (한국어 ARIA + href /market/trend)
    const detailLink = strip.getByRole('link', { name: /시장 추세 상세 보기|상세 추세/ })
    await expect(detailLink).toBeVisible()
    await expect(detailLink).toHaveAttribute('href', '/market/trend')

    // 베이스라인 스크린샷 (전체 페이지 — DashboardPage 상단에 MarketIndexStrip 마운트 시점 LOCK)
    await expect(page).toHaveScreenshot('w4-1-dashboard-market-index-strip.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})
