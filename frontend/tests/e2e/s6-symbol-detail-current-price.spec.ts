/**
 * S-6 종목상세 현재가 결함 hunt — qa 첫 실전 임무 (Visual QA "품질 헌터" 첫 가동)
 *
 * 사용자 dogfooding 함구:
 *   "종목상세에 지금 현재가에 문제가 있는게 보이거든??
 *    일단 내가 말을 안 해볼거야 뭐가 문제인지.
 *    이거 QA한테 한번 찾아보라 해보자 어떤게 문젠지 찾을 수 있는지 확인해야지."
 *
 * 본 시나리오는 다음 evidence 매트릭스를 단일 run에 일괄 수집:
 *   - Visual evidence: 전체 페이지 baseline 스크린샷 + price card 부분 스크린샷
 *   - DOM evidence: 현재가 element + class/text content
 *   - Network evidence: GET /api/symbols/{code} 응답 raw (currentPrice·source·asOf)
 *   - Console evidence: console.error / console.warn / console.log 수집
 *
 * 대상 종목 (사용자 잔고 보유 + 시가총액 상위 비교군):
 *   - 005930 삼성전자 (현재가 inflated 의심 — 5배 단위 mismatch hypothesis)
 *   - 000660 SK하이닉스 (PR #595 hotfix 후속 추적 — basePrice 1,840,000 vs HTS 1,719,000 evidence)
 *   - 004090 한국석유 (가격 정상 범위 — 비교 baseline)
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s6-symbol-detail-current-price.spec.ts --reporter=list
 *
 * baseline 미존재 (첫 실행) — toHaveScreenshot에서 신규 생성 후 본 보고서에 첨부.
 */
import { expect, test, type ConsoleMessage, type Response } from '@playwright/test'

interface CapturedNetwork {
  url: string
  status: number
  body: string
}

interface CapturedConsole {
  type: string
  text: string
}

const TARGETS = [
  { code: '005930', name: '삼성전자', expectRange: { min: 30_000, max: 200_000 } },
  { code: '000660', name: 'SK하이닉스', expectRange: { min: 100_000, max: 400_000 } },
  { code: '004090', name: '한국석유', expectRange: { min: 5_000, max: 50_000 } },
] as const

test.describe('S-6 종목상세 현재가 결함 hunt', () => {
  for (const target of TARGETS) {
    test(`${target.code} ${target.name} — evidence 매트릭스 수집`, async ({ page }) => {
      const networkLog: CapturedNetwork[] = []
      const consoleLog: CapturedConsole[] = []

      page.on('console', (msg: ConsoleMessage) => {
        consoleLog.push({ type: msg.type(), text: msg.text() })
      })

      page.on('response', async (res: Response) => {
        const url = res.url()
        if (url.includes(`/api/symbols/${target.code}`) && !url.includes('/candles') && !url.includes('/orderbook')) {
          try {
            const body = await res.text()
            networkLog.push({ url, status: res.status(), body })
          } catch {
            // 응답 read 실패 — graceful
          }
        }
      })

      // 페이지 진입
      await page.goto(`/symbol/${target.code}`)
      await page.waitForLoadState('networkidle')

      // 가격 카드 렌더 대기
      await expect(page.getByText('현재가')).toBeVisible({ timeout: 15_000 })

      // ── Visual evidence ──
      // 전체 페이지 baseline (첫 실행 — baseline 신규 생성)
      await expect(page).toHaveScreenshot(`s6-${target.code}-full.png`, {
        fullPage: true,
        animations: 'disabled',
      })

      // 가격 카드 부분 스크린샷 (visual diff 정밀도 ↑)
      const priceCard = page.locator('div').filter({ hasText: /^현재가/ }).first()
      await expect(priceCard).toHaveScreenshot(`s6-${target.code}-price-card.png`, {
        animations: 'disabled',
      })

      // ── DOM evidence ──
      // 현재가 표시 element + text content + class 추출
      const currentPriceElement = page.locator('.text-2xl.font-bold.text-white.tabular-nums').first()
      const currentPriceText = (await currentPriceElement.textContent())?.trim() ?? ''
      const currentPriceParent = await currentPriceElement.evaluate((el) => el.parentElement?.outerHTML?.slice(0, 800) ?? '')

      // 변동률·전일대비 text
      const changeRateText = await page
        .locator('.text-sm.tabular-nums')
        .first()
        .textContent()
        .catch(() => '')

      // ── Number parse ──
      const parsed = parseInt(currentPriceText.replace(/[^0-9]/g, ''), 10)

      // ── 결함 보고서용 evidence 출력 (console.log → playwright report 첨부) ──
      console.log(`\n══════ ${target.code} ${target.name} evidence ══════`)
      console.log(`[DOM] 현재가 text: "${currentPriceText}"`)
      console.log(`[DOM] parsed number: ${parsed}`)
      console.log(`[DOM] expected range: ${target.expectRange.min} ~ ${target.expectRange.max}`)
      console.log(`[DOM] 변동률 text: "${changeRateText}"`)
      console.log(`[DOM] price card parent (snippet):\n${currentPriceParent}`)
      console.log(`[NET] /api/symbols/${target.code} responses:`)
      for (const n of networkLog) {
        console.log(`  - status=${n.status} body.head=${n.body.slice(0, 600)}`)
      }
      console.log(`[CON] console messages (${consoleLog.length}):`)
      for (const c of consoleLog.slice(0, 30)) {
        console.log(`  - [${c.type}] ${c.text.slice(0, 200)}`)
      }
      console.log(`══════ ${target.code} end ══════\n`)

      // ── 의도적 soft 검증 — 결함 확정 → fail로 evidence 명시 ──
      // expectRange 위배 시 test fail (qa 결함 발견 정확히 표면화).
      // P0 hotfix가 들어가면 본 expect가 GREEN으로 reverse되는 회귀 가드 역할도 겸함.
      expect(
        parsed,
        `${target.name}(${target.code}) 현재가 ${parsed}원이 HTS 실거래 범위 ${target.expectRange.min}~${target.expectRange.max}원 밖. ` +
          `DB candle DB 1d close_raw 단위 mismatch 의심 (root cause 가설 H1·H2 — 보고서 참조).`,
      ).toBeGreaterThanOrEqual(target.expectRange.min)
      expect(parsed).toBeLessThanOrEqual(target.expectRange.max)
    })
  }
})
