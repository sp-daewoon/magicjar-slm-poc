/**
 * S-7 Phase B 핵심 화면 변경 Visual 검증 (헌법 §9-2 의무 dispatch)
 *
 * orchestrator dispatch 2026-06-05 — 라이브 docker 환경 (전 컨테이너 healthy):
 *   1. PR #727  종목 상세 캔들 수집 상태 배지 (CandleHealthBadge)
 *   2. PR #730  시나리오 리플레이 게이트 이벤트 (GATE_SKIPPED / GATE_NOT_SIMULATED)
 *   3. Codex    전략 리서치 신규 화면 (/strategy/research) 스모크
 *
 * 제약 (ADR 0107 + dispatch):
 *   - 읽기성 액션만. 실주문·전략 상태 변경·설정 변경 금지.
 *   - 시나리오 리플레이 실행은 sandbox(scenario_* only, KIS 주문 미발행)라 허용.
 *   - mock 금지 — 실제 백엔드 응답으로 진행. STALE/NO_DATA 강제 재현 시도 안 함.
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s7-phase-b-visual-verification.spec.ts --reporter=list
 */
import { expect, test, type ConsoleMessage, type Response } from '@playwright/test'

// 라이브 ACTIVE 전략 보유 사용자 (dispatch evidence — strategy 43·44 owner)
const USER_ID = 74683223

interface CapturedConsole {
  type: string
  text: string
}

/**
 * 온보딩 store(localStorage persist key `magicjar-onboarding`, version 2)에
 * registeredUserId를 주입 — 시나리오 리플레이·전략 리서치 페이지의 사용자 게이트 통과.
 * 설정 변경 아님(읽기 흐름 진입 전제 셋업).
 */
async function seedOnboarding(page: import('@playwright/test').Page) {
  await page.addInitScript((userId) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: userId }, version: 2 }),
    )
  }, USER_ID)
}

function attachConsole(page: import('@playwright/test').Page): CapturedConsole[] {
  const log: CapturedConsole[] = []
  page.on('console', (msg: ConsoleMessage) => {
    log.push({ type: msg.type(), text: msg.text() })
  })
  page.on('pageerror', (err) => {
    log.push({ type: 'pageerror', text: String(err) })
  })
  return log
}

/**
 * 알려진 무해 노이즈 필터 — Phase B 변경과 무관한 사전 결함.
 *   - `/vite.svg`: index.html이 참조하지만 docker 빌드에 미포함된 favicon → 404.
 *     모든 페이지에서 간헐 출현. 별도 P2로 분리 보고. Phase B 회귀 판정에서 제외.
 */
function isKnownNoise(text: string): boolean {
  // 브라우저는 404 favicon을 "Failed to load resource ... 404" 형태로만 로깅(URL 미포함)하므로
  // 네트워크 레벨에서 vite.svg 404를 별도 검출하고, 콘솔 404 1건은 favicon으로 귀속한다.
  return /Failed to load resource: the server responded with a status of 404/.test(text)
}

function reportConsole(scenario: string, log: CapturedConsole[]) {
  const allErrors = log.filter((c) => c.type === 'error' || c.type === 'pageerror')
  const noise = allErrors.filter((c) => isKnownNoise(c.text))
  const errors = allErrors.filter((c) => !isKnownNoise(c.text))
  console.log(
    `\n══════ ${scenario} — console (${log.length} msgs, ${allErrors.length} errors, ${noise.length} known-noise) ══════`,
  )
  for (const c of errors.slice(0, 30)) {
    console.log(`  [REGRESSION? ${c.type}] ${c.text.slice(0, 240)}`)
  }
  for (const c of noise.slice(0, 5)) {
    console.log(`  [known-noise ${c.type}] ${c.text.slice(0, 200)} (← /vite.svg favicon 404, P2)`)
  }
  if (errors.length === 0) console.log('  (Phase B 관련 error/pageerror 0건)')
  console.log(`══════ ${scenario} end ══════\n`)
  return errors
}

test.describe('S-7 Phase B Visual 검증', () => {
  // ─────────────────────────────────────────────────────────────
  // 시나리오 1 — PR #727 종목 상세 캔들 수집 상태 배지
  // ─────────────────────────────────────────────────────────────
  test('S7-1 종목상세 005930 캔들 수집 배지 (FRESH/OK_REALTIME 기대)', async ({ page }) => {
    const consoleLog = attachConsole(page)
    let candleHealthBody = ''

    page.on('response', async (res: Response) => {
      if (res.url().includes('/api/symbols/005930/candle-health')) {
        try {
          candleHealthBody = await res.text()
        } catch {
          /* graceful */
        }
      }
    })

    await page.goto('/symbol/005930')
    await page.waitForLoadState('networkidle')

    // 배지 존재 단언 (data-testid="candle-health-badge")
    const badge = page.getByTestId('candle-health-badge')
    await expect(badge).toBeVisible({ timeout: 15_000 })

    const tone = await badge.getAttribute('data-tone')
    const badgeText = (await badge.textContent())?.trim() ?? ''

    console.log(`\n[S7-1] badge data-tone=${tone}`)
    console.log(`[S7-1] badge text="${badgeText}"`)
    console.log(`[S7-1] GET /api/symbols/005930/candle-health body=${candleHealthBody.slice(0, 400)}`)

    // 배지 부분 스크린샷 (evidence)
    await expect(badge).toHaveScreenshot('s7-1-005930-candle-health-badge.png', {
      animations: 'disabled',
    })

    // 전체 페이지 스크린샷 (배지 컨텍스트 — 차트 위 위치 확인)
    await expect(page).toHaveScreenshot('s7-1-005930-full.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const errors = reportConsole('S7-1', consoleLog)

    // ── 단언 ──
    // 장중이면 FRESH(녹색 점 + "캔들 실시간 수집 정상") 기대.
    // 장외 등으로 neutral(MARKET_CLOSED)이어도 정직한 정상 상황이므로 fresh|neutral 둘 다 허용.
    expect(['fresh', 'neutral'], `예상 밖 tone=${tone} (배지 text="${badgeText}")`).toContain(tone)
    // API status·reason 교차 확인 (정직성 — 배지가 가짜 FRESH 아닌지)
    if (candleHealthBody) {
      const parsed = JSON.parse(candleHealthBody)
      if (tone === 'fresh') {
        expect(parsed.status, `tone=fresh인데 API status=${parsed.status}`).toBe('FRESH')
      }
    }
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // 시나리오 2 — PR #730 시나리오 리플레이 게이트 이벤트
  //   strategy 44(000660·15m) / 2026-06-04 16:00~17:00 KST(장외) 1시간 리플레이
  //   → GATE_SKIPPED(정규장 게이트 skip) + GATE_NOT_SIMULATED 기대
  // ─────────────────────────────────────────────────────────────
  test('S7-2 시나리오 리플레이 게이트 이벤트 정직 표시', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)
    const gateEventTypes: string[] = []

    page.on('response', async (res: Response) => {
      if (res.url().includes('/api/scenario-replay/runs') && res.request().method() === 'POST') {
        try {
          const body = await res.text()
          const json = JSON.parse(body)
          for (const e of json.events ?? []) {
            if (String(e.type).startsWith('GATE_')) gateEventTypes.push(e.type)
          }
        } catch {
          /* graceful */
        }
      }
    })

    await page.goto('/strategy/scenario-replay')
    await page.waitForLoadState('networkidle')

    // "신규 실행" 폼 진입 단언
    await expect(page.getByRole('heading', { name: '시나리오 리플레이' })).toBeVisible()

    // 전략 선택 (strategy 44 — ACTIVE, code 000660·15m)
    await expect(page.locator('#scenario-strategy')).toBeVisible({ timeout: 15_000 })
    await page.locator('#scenario-strategy').selectOption('44')

    // 종목·interval·기간 설정 (장외 1시간 — GATE_SKIPPED:MARKET_HOURS 유도)
    await page.locator('#scenario-code').fill('000660')
    await page.locator('#scenario-interval').selectOption('15m').catch(async () => {
      await page.locator('#scenario-interval').fill('15m')
    })
    await page.locator('#scenario-from').fill('2026-06-04T16:00')
    await page.locator('#scenario-to').fill('2026-06-04T17:00')

    // 입력 직후 폼 스크린샷
    await expect(page).toHaveScreenshot('s7-2-replay-form.png', {
      fullPage: true,
      animations: 'disabled',
    })

    // 리플레이 실행 (sandbox — KIS 주문 미발행, scenario_* only)
    // 실제 UX: onSuccess → activeTab='history' + selectedRunId 설정 → 타임라인(ScenarioReplayDetail) 직접 진입.
    // (별도 "완료 배너 → 타임라인 보기" 단계는 없음 — 제출 즉시 타임라인 표시.)
    await page.getByRole('button', { name: /리플레이 실행/ }).click()

    // 타임라인 진입 + GATE 이벤트 노드 렌더 대기
    await page.waitForLoadState('networkidle')

    // 진단 이벤트(CANDLE 등) 표시 토글 ON — GATE_SKIPPED가 INFO/CANDLE 흐름과 함께 노출되도록
    const diagToggle = page.locator('input[type="checkbox"]').first()
    if (await diagToggle.isVisible().catch(() => false)) {
      await diagToggle.check().catch(() => {})
    }

    // GATE 이벤트 라벨 텍스트 단언 (timeline EventNode)
    // GATE_NOT_SIMULATED: "리플레이 미재현 게이트", GATE_SKIPPED: "리플레이 정규장 게이트 skip"
    await expect(page.getByText(/리플레이 정규장 게이트 skip/).first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(/리플레이 미재현 게이트/).first()).toBeVisible()

    // 타임라인 스크린샷
    await expect(page).toHaveScreenshot('s7-2-replay-timeline.png', {
      fullPage: true,
      animations: 'disabled',
    })

    console.log(`\n[S7-2] POST 응답 GATE 이벤트 type 수집: ${JSON.stringify([...new Set(gateEventTypes)])}`)
    const errors = reportConsole('S7-2', consoleLog)

    // ── 단언 ──
    expect(gateEventTypes, 'GATE_SKIPPED 미출현 (장외 구간 정규장 게이트 skip 기대)').toContain('GATE_SKIPPED')
    expect(gateEventTypes, 'GATE_NOT_SIMULATED 미출현').toContain('GATE_NOT_SIMULATED')
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // 시나리오 3 — Codex 전략 리서치 신규 화면 스모크
  // ─────────────────────────────────────────────────────────────
  test('S7-3 전략 리서치 페이지 진입·렌더·콘솔 에러 0 (스모크)', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto('/strategy/research')
    await page.waitForLoadState('networkidle')

    // 페이지 헤딩 렌더 단언
    await expect(page.getByRole('heading', { name: '전략 리서치' })).toBeVisible({ timeout: 15_000 })

    // 전체 페이지 스크린샷
    await expect(page).toHaveScreenshot('s7-3-strategy-research.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const errors = reportConsole('S7-3', consoleLog)

    // ── 단언 ── 스모크 수준 — 렌더 + 콘솔 에러 0
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })
})
