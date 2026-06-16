/**
 * S-10 orderTrigger 키 사슬 추적 Visual 회귀 + 기능 검증 (헌법 §9-2 qa Visual 자동 의무)
 *
 * 대상: PR #906 (ADR 0115 §2-B 불변식 #4 — orderTrigger 키 사슬 추적 화면) 머지·배포 라이브 검증.
 *   spec _workspace/frontend/screenshot-desc/2026-06-10-order-trigger-trace.md (frontend 작성).
 *   라이브 docker (api·consumer·batch·frontend healthy) — 실데이터 005930 주문 185건·체결·trigger 분류 완료.
 *
 * 검증된 실데이터 키 사슬 (API 스모크 GET /api/signals/699/trace?userId=74683223 200):
 *   signal 699 (strategy 8, 005930 1m, userId 74683223)
 *     → 주문 #219 BUY 3주 STRATEGY_SIGNAL FILLED (부분체결 2건: 1주+2주 = 체결 3주)
 *     → 주문 #220 SELL 1주 STRATEGY_SIGNAL CANCELLED (체결 0)
 *     → summary: 주문 4주 → 체결 3주 → 전략 보유 5주 vs KIS 권위 5주 → reconciled=true(정합/초록)
 *
 * 시나리오:
 *   S10-1 /orders 목록 — 발사 출처 badge 6값 분류 (Visual baseline + badge 존재 기능 검증)
 *   S10-2 /orders/:id 상세 — "발사 출처" 필드 (#219 STRATEGY_SIGNAL) Visual + 기능
 *   S10-3 /strategy/list signal 699 드릴다운 — 키 사슬 추적 토글 확장 (사인→주문→체결 타임라인→정합 badge)
 *          기능: 주문 #219·체결 타임라인·orderTrigger·triggerRefId·정합(초록) 화면-API 대조
 *   S10-4 에러 path — 존재하지 않는 signal trace (mock 합성 0 — "불러오지 못했습니다" 명시 처리)
 *
 * 제약: 읽기성 액션만 (드릴다운 토글·페이지 진입). 실주문·상태 변경 0. mock 합성 0 — 실제 백엔드 응답.
 *   baseline add-only — 기존 baseline PNG 미변경. 신규 시나리오 신규 PNG 발행.
 *   스크린샷 evidence는 _workspace/qa/screenshots/2026-06-10-order-trigger/ 에 추가 보존.
 *
 * 실행: pnpm playwright test tests/e2e/s10-order-trigger-trace.spec.ts --reporter=list
 */
import { expect, test, type ConsoleMessage, type Page } from '@playwright/test'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url))

const USER_ID = 74683223
// API 스모크로 trace 200 확인된 실데이터 키
const TRACE_SIGNAL_ID = 699
const TRACE_ORDER_ID = 219
const MISSING_SIGNAL_ID = 999999

// qa evidence 스크린샷 누적 디렉토리 (baseline 스냅샷과 별개 — 사용자 가시화용)
const EVIDENCE_DIR = path.resolve(
  SPEC_DIR,
  '../../../_workspace/qa/screenshots/2026-06-10-order-trigger',
)

interface CapturedConsole {
  type: string
  text: string
}

async function seedOnboarding(page: Page) {
  await page.addInitScript((userId) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: userId }, version: 2 }),
    )
  }, USER_ID)
}

function attachConsole(page: Page): CapturedConsole[] {
  const log: CapturedConsole[] = []
  page.on('console', (msg: ConsoleMessage) => log.push({ type: msg.type(), text: msg.text() }))
  page.on('pageerror', (err) => log.push({ type: 'pageerror', text: String(err) }))
  return log
}

/** /vite.svg favicon 404 — 사전 결함(s7·s8 보고 P2). 회귀 판정에서 제외. */
function isKnownNoise(text: string): boolean {
  return /Failed to load resource: the server responded with a status of 404/.test(text)
}

function reportConsole(scenario: string, log: CapturedConsole[]): CapturedConsole[] {
  const all = log.filter((c) => c.type === 'error' || c.type === 'pageerror')
  const noise = all.filter((c) => isKnownNoise(c.text))
  const errors = all.filter((c) => !isKnownNoise(c.text))
  console.log(
    `\n══════ ${scenario} — console (${log.length} msgs, ${all.length} errors, ${noise.length} known-noise) ══════`,
  )
  for (const c of errors.slice(0, 30)) console.log(`  [REGRESSION? ${c.type}] ${c.text.slice(0, 240)}`)
  if (errors.length === 0) console.log('  (S10 관련 error/pageerror 0건)')
  console.log(`══════ ${scenario} end ══════\n`)
  return errors
}

test.describe('S-10 orderTrigger 키 사슬 추적 Visual + 기능', () => {
  // ─────────────────────────────────────────────────────────────
  // S10-1 /orders 목록 — 발사 출처 badge Visual baseline + 기능
  // ─────────────────────────────────────────────────────────────
  test('S10-1 주문 목록 발사 출처 badge baseline + 존재 검증', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto('/orders')
    await page.waitForLoadState('networkidle')

    // 발사 출처 badge 라벨 — 라이브 데이터에 존재하는 분류가 보이는지 (mock 합성 0)
    const bodyText = await page.locator('body').innerText()
    const knownLabels = ['전략 사인', '자동 익절', '손절', '분할 익절', '수동', '외부', '미분류']
    const present = knownLabels.filter((l) => bodyText.includes(l))
    console.log(`  [S10-1] 화면 노출 발사 출처 라벨: ${present.join(', ') || '(없음)'}`)
    expect(present.length, '주문 목록에 발사 출처 badge 라벨이 1개 이상 보여야 함').toBeGreaterThan(0)

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, 's10-1-orders-list.png'),
      fullPage: true,
    })

    await expect(page).toHaveScreenshot('s10-1-orders-list.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const errors = reportConsole('S10-1 /orders', consoleLog)
    expect(errors, 'S10-1 콘솔 회귀 0건').toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // S10-2 /orders/:id 상세 — 발사 출처 필드 Visual + 기능 (#219 STRATEGY_SIGNAL)
  // ─────────────────────────────────────────────────────────────
  test('S10-2 주문 상세 발사 출처 필드 baseline + #219 STRATEGY_SIGNAL', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto(`/orders/${TRACE_ORDER_ID}`)
    await page.waitForLoadState('networkidle')

    const bodyText = await page.locator('body').innerText()
    // #219 = STRATEGY_SIGNAL → "전략 사인" badge + triggerRefId 699 hover 키
    const hasTriggerLabel = bodyText.includes('전략 사인')
    console.log(`  [S10-2] 상세 화면 "전략 사인" 노출: ${hasTriggerLabel}`)
    expect(hasTriggerLabel, '주문 #219 상세에 "전략 사인" 발사 출처 badge 노출').toBe(true)

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, 's10-2-order-detail.png'),
      fullPage: true,
    })

    await expect(page).toHaveScreenshot('s10-2-order-detail.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const errors = reportConsole('S10-2 /orders/219', consoleLog)
    expect(errors, 'S10-2 콘솔 회귀 0건').toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // S10-3 ★ signal 699 드릴다운 키 사슬 추적 (불변식 #4 핵심)
  //   사인 → 주문 #219 → 체결 타임라인 → 전략 보유 5 vs KIS 5 → 정합(초록)
  // ─────────────────────────────────────────────────────────────
  test('S10-3 signal 699 키 사슬 추적 드릴다운 — 사인→주문→체결→정합', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto('/strategy/list')
    await page.waitForLoadState('networkidle')

    // 시그널 내역(today 프리셋, size 20)의 SignalCard "키 사슬 추적" 토글(aria-label).
    const traceButtons = page.getByRole('button', { name: '키 사슬 추적 (사인→주문→체결→보유)' })
    const btnCount = await traceButtons.count()
    console.log(`  [S10-3] "키 사슬 추적" 버튼 수: ${btnCount}`)
    expect(btnCount, '시그널 카드에 키 사슬 추적 버튼이 1개 이상').toBeGreaterThan(0)

    // 699 trace 응답을 노출하는 카드를 찾을 때까지 순회 (699 = today 첫 페이지 index 16).
    let opened = false
    for (let i = 0; i < Math.min(btnCount, 20); i++) {
      const btn = traceButtons.nth(i)
      await btn.scrollIntoViewIfNeeded()
      const [resp] = await Promise.all([
        page
          .waitForResponse(
            (r) => /\/api\/signals\/\d+\/trace/.test(r.url()) && r.status() === 200,
            { timeout: 8000 },
          )
          .catch(() => null),
        btn.click(),
      ])
      if (resp && resp.url().includes(`/signals/${TRACE_SIGNAL_ID}/trace`)) {
        opened = true
        break
      }
      // 699 아니면 닫고 다음 카드
      await btn.click()
    }
    expect(opened, `signal ${TRACE_SIGNAL_ID} 드릴다운을 찾아 열어야 함`).toBe(true)

    // 패널 렌더 대기 — 요약 행 + 정합 badge + 주문 #219 + 체결 타임라인
    const panelHeader = page.locator('text=이 사인이 발사한 주문').first()
    await expect(panelHeader).toBeVisible({ timeout: 8000 })

    const bodyText = await page.locator('body').innerText()
    console.log(`  [S10-3] 키 사슬 토큰 점검:`)
    const checks: Array<[string, boolean]> = [
      ['주문 #219 링크', bodyText.includes('#219')],
      ['주문 #220 연결', bodyText.includes('#220')],
      ['정합 badge', bodyText.includes('정합')],
      ['KIS 권위 라벨', bodyText.includes('KIS 권위')],
      ['전략 보유 라벨', bodyText.includes('전략 보유')],
    ]
    for (const [label, ok] of checks) console.log(`     - ${label}: ${ok ? 'PASS' : 'FAIL'}`)

    // 핵심 단언: 주문 #219 + 정합 badge 노출 (전략 보유 5 == KIS 권위 5 → reconciled=true)
    expect(bodyText.includes('#219'), '드릴다운에 주문 #219 노출').toBe(true)
    expect(bodyText.includes('정합'), '전략 보유 5 == KIS 권위 5 → 정합 badge').toBe(true)
    expect(bodyText.includes('KIS 권위'), 'KIS 권위 보유 요약 노출').toBe(true)

    // evidence 는 full page (사용자 가시화 — 드릴다운 위치 맥락 포함)
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, 's10-3-signal-699-trace.png'),
      fullPage: true,
    })

    // Visual baseline 은 드릴다운 패널 요소만 캡처 — full page 는 실시간 시그널 목록(STOMP prepend)·
    // today 시각 strip 이 매 실행 변동되어 false positive 회귀를 유발. 패널은 fetch 데이터 고정(699 키 사슬).
    const tracePanel = page
      .locator('div', { has: page.locator('text=이 사인이 발사한 주문') })
      .filter({ hasText: 'KIS 권위' })
      .last()
    await expect(tracePanel).toHaveScreenshot('s10-3-signal-699-trace-panel.png', {
      animations: 'disabled',
    })

    const errors = reportConsole('S10-3 /strategy/list trace', consoleLog)
    expect(errors, 'S10-3 콘솔 회귀 0건').toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // S10-4 에러 path — 존재하지 않는 signal trace (mock 합성 0 명시 처리)
  //   trace 응답을 404 로 라우팅하여 컴포넌트 isError 분기가 명시되는지 검증.
  // ─────────────────────────────────────────────────────────────
  test('S10-4 존재하지 않는 signal trace — 명시 처리(mock 합성 0)', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto('/strategy/list')
    await page.waitForLoadState('networkidle')

    // trace 요청을 강제 404 로 라우팅 (실제 백엔드 999999 trace 가 반환하는 형태와 동일).
    await page.route(/\/api\/signals\/\d+\/trace/, async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status: 404,
          error: 'Not Found',
          path: `/api/signals/${MISSING_SIGNAL_ID}/trace`,
        }),
      })
    })

    const traceButtons = page.getByRole('button', { name: '키 사슬 추적 (사인→주문→체결→보유)' })
    if ((await traceButtons.count()) > 0) {
      await traceButtons.first().scrollIntoViewIfNeeded()
      await traceButtons.first().click()
      // 에러 명시 텍스트 — mock 박스가 아니라 "불러오지 못했습니다"
      const errMsg = page.locator('text=키 사슬 추적 데이터를 불러오지 못했습니다')
      await expect(errMsg, '404 시 에러 명시 처리(mock 합성 0)').toBeVisible({ timeout: 8000 })
      console.log('  [S10-4] 404 에러 path 명시 처리 PASS')

      await page.screenshot({
        path: path.join(EVIDENCE_DIR, 's10-4-trace-error.png'),
        fullPage: true,
      })
    } else {
      console.log('  [S10-4] SKIP — 시그널 카드 0건 (today 데이터 없음)')
    }

    // 의도적 404 라우팅이므로 네트워크 404 콘솔 noise 는 회귀로 보지 않음.
    reportConsole('S10-4 missing trace', consoleLog)
  })
})
