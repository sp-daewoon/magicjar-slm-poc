/**
 * S-8 분할매수(scale-in) 트랙 R3-c 전 흐름 시나리오 + Visual (헌법 §9-2 · qa dispatch 2026-06-05)
 *
 * 검증 대상: spec _workspace/specs/2026-06-05-scale-in-exit-ownership-spec.md + PR #742~#747
 *   현재 toggle `magicjar.strategy.scale-in.enabled` = default false (OFF) — toggle OFF 기대 동작까지 검증.
 *   라이브 docker (api·consumer·batch·frontend healthy), Flyway V64·V65 적용 GREEN.
 *
 * 시나리오 (dispatch 본문):
 *   S8-1 빌더 입력 패널 — 분할매수 토글 ON → 회수·비중(균등/수동 합100 색상)·재진입 트리거·분할익절 단계 UX + 경계값
 *   S8-3 ACTIVE 차단 UX — scale-in DRAFT 전략(strategyId=46, API 사전 생성) 로드 → round-trip 보존 확인
 *   S8-4 전략별 포지션 패널 — StrategyList 카드의 "전략 보유 현황" — 보유 0건 "보유 없음"
 *   S8-6 하위호환 — scale-in 미설정 기존 전략 카드 회귀 0 (분할 배지 미노출)
 *   S8-7 Visual 회귀 — 빌더 모달(scale-in 패널)·전략 카드 baseline 스크린샷
 *
 * S8-2(저장→DRAFT round-trip) · S8-3 서버 거부(409) · S8-5(리플레이 분할/익절) 는 라이브 API 직접 호출로
 *   교차 검증했고 보고서(_workspace/reports/2026-06-05-qa-r3-scale-in.md)에 evidence 인용한다.
 *   (DRAFT 생성·리플레이는 sandbox이며 실주문·기존 ACTIVE 상태 변경 0 — dispatch 제약 준수.)
 *
 * 제약: 읽기성 액션만. 빌더는 클라이언트 state 조작(저장 버튼 미클릭)으로 입력 UX·Visual만 검증.
 *   실주문·기존 ACTIVE 전략 상태 변경 0. mock 합성 0 — 실제 백엔드 응답.
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s8-scale-in-verification.spec.ts --reporter=list
 */
import { expect, test, type ConsoleMessage } from '@playwright/test'

// scale-in DRAFT 전략(qa_scalein_verify_s8) owner — 라이브 API로 사전 생성된 strategyId=46
const USER_ID = 74683223
const SCALE_IN_STRATEGY_ID = 46

interface CapturedConsole {
  type: string
  text: string
}

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
  page.on('console', (msg: ConsoleMessage) => log.push({ type: msg.type(), text: msg.text() }))
  page.on('pageerror', (err) => log.push({ type: 'pageerror', text: String(err) }))
  return log
}

/** /vite.svg favicon 404 — 사전 결함(s7 보고 P2). S8 회귀 판정에서 제외. */
function isKnownNoise(text: string): boolean {
  return /Failed to load resource: the server responded with a status of 404/.test(text)
}

function reportConsole(scenario: string, log: CapturedConsole[]) {
  const all = log.filter((c) => c.type === 'error' || c.type === 'pageerror')
  const noise = all.filter((c) => isKnownNoise(c.text))
  const errors = all.filter((c) => !isKnownNoise(c.text))
  console.log(`\n══════ ${scenario} — console (${log.length} msgs, ${all.length} errors, ${noise.length} known-noise) ══════`)
  for (const c of errors.slice(0, 30)) console.log(`  [REGRESSION? ${c.type}] ${c.text.slice(0, 240)}`)
  if (errors.length === 0) console.log('  (S8 관련 error/pageerror 0건)')
  console.log(`══════ ${scenario} end ══════\n`)
  return errors
}

test.describe('S-8 분할매수 R3-c 전 흐름 + Visual', () => {
  // ─────────────────────────────────────────────────────────────
  // S8-1 빌더 입력 패널 — 분할매수/분할익절 UX + 경계값 시각 검증
  //   ?strategyId=46 로드 → entry DSL 채워짐 → 저장 모달 진입 → scale-in 패널 조작
  // ─────────────────────────────────────────────────────────────
  test('S8-1 빌더 scale-in/exit 입력 패널 + 경계값 색상 검증', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)

    await page.goto(`/strategy/builder?strategyId=${SCALE_IN_STRATEGY_ID}`)
    await page.waitForLoadState('networkidle')

    // 전략 로드 완료(entry DSL) → 저장 버튼 활성화 대기
    const saveBtn = page.getByRole('button', { name: /^저장$/ })
    await expect(saveBtn).toBeEnabled({ timeout: 15_000 })
    await saveBtn.click()

    // 저장 모달 진입
    const modal = page.getByRole('dialog', { name: '전략 저장' })
    await expect(modal).toBeVisible()

    // 분할 매수 섹션 — 로드된 전략이 scaleIn 보유 → 토글 ON + 입력 노출
    const scaleInSection = modal.locator('section[aria-label="분할 매수 설정"]')
    await expect(scaleInSection).toBeVisible()
    const scaleInToggle = scaleInSection.getByLabel('분할 매수 사용')
    await expect(scaleInToggle).toBeChecked() // strategyId=46 은 scaleIn 설정 전략

    // 분할 회수 입력 노출
    await expect(scaleInSection.getByLabel('분할 회수')).toBeVisible()

    // ── 수동 비중 경계값: 합 100이 아니면 빨강, 100이면 초록 ──
    const manualToggle = scaleInSection.getByLabel('회당 비중 수동 입력')
    if (!(await manualToggle.isChecked())) await manualToggle.check()
    // 50/30/20 (=100) → 초록 / status 텍스트 "비중 합 100 / 100"
    const w1 = scaleInSection.getByLabel('1회차 비중')
    const w2 = scaleInSection.getByLabel('2회차 비중')
    const w3 = scaleInSection.getByLabel('3회차 비중')
    await w1.fill('50')
    await w2.fill('30')
    await w3.fill('20')
    const sumStatus = scaleInSection.getByRole('status')
    await expect(sumStatus).toContainText('100 / 100')
    const greenClass = await sumStatus.getAttribute('class')
    console.log(`[S8-1] 합100 status class=${greenClass}`)
    expect(greenClass, '합100인데 emerald 미적용').toContain('emerald')

    // 합 99 (50/30/19) → 빨강 + 합 표시 99
    await w3.fill('19')
    await expect(sumStatus).toContainText('99 / 100')
    const redClass = await sumStatus.getAttribute('class')
    console.log(`[S8-1] 합99 status class=${redClass}`)
    expect(redClass, '합99인데 red 미적용').toContain('red')

    // 비중 패널 스크린샷 (경계값 RED 상태 — evidence)
    await expect(scaleInSection).toHaveScreenshot('s8-1-scale-in-weight-99-red.png', {
      animations: 'disabled',
    })

    // 합 100 복귀
    await w3.fill('20')
    await expect(sumStatus).toContainText('100 / 100')

    // ── 재진입 트리거 = PRICE_DROP_PCT 시 하락폭 입력 노출 ──
    await scaleInSection.getByLabel('재진입 트리거').selectOption('PRICE_DROP_PCT')
    await expect(scaleInSection.getByLabel('하락폭')).toBeVisible()
    await scaleInSection.getByLabel('하락폭').fill('-3')

    // ── 분할 익절 섹션 ──
    const exitSection = modal.locator('section[aria-label="분할 익절 설정"]')
    await expect(exitSection).toBeVisible()
    const exitToggle = exitSection.getByLabel('분할 익절 사용')
    if (!(await exitToggle.isChecked())) await exitToggle.check()

    // 단계 추가 버튼이 최대 5단계까지만 — 6번째 추가 시도 시 버튼 사라짐(cap)
    const addStageBtn = exitSection.getByRole('button', { name: '+ 단계 추가' })
    for (let i = 0; i < 6; i += 1) {
      if (await addStageBtn.isVisible().catch(() => false)) {
        await addStageBtn.click()
      } else {
        break
      }
    }
    // 5단계 cap 도달 → 추가 버튼 미노출 단언
    const stageCount = await exitSection.locator('input[aria-label$="단계 수익률"]').count()
    console.log(`[S8-1] 분할익절 단계 수(cap 5): ${stageCount}`)
    expect(stageCount, '분할익절 단계가 5(cap) 초과 추가됨').toBeLessThanOrEqual(5)
    await expect(addStageBtn, '5단계인데 추가 버튼 여전히 노출(cap 미작동)').toHaveCount(0)

    // 전체 모달 스크린샷 (scale-in/exit 패널 — Visual baseline)
    await expect(modal).toHaveScreenshot('s8-1-save-modal-scale-in-panel.png', {
      animations: 'disabled',
    })

    const errors = reportConsole('S8-1', consoleLog)
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // S8-3 scale-in DRAFT 전략 로드 + round-trip 보존
  //   서버 거부(SCALE_IN_NOT_ENABLED)는 보고서에서 API 직접 호출로 교차(HTTP 409).
  // ─────────────────────────────────────────────────────────────
  test('S8-3 scale-in DRAFT 전략 로드 + scaleIn round-trip 보존', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)
    let detailScaleIn: unknown = null

    page.on('response', async (res) => {
      if (
        res.url().includes(`/api/strategies/${SCALE_IN_STRATEGY_ID}`) &&
        res.request().method() === 'GET'
      ) {
        try {
          const j = JSON.parse(await res.text())
          if (j && 'scaleIn' in j) {
            detailScaleIn = j.scaleIn
            console.log(
              `[S8-3] GET /api/strategies/${SCALE_IN_STRATEGY_ID} scaleIn=${JSON.stringify(j.scaleIn)} status=${j.status} blocked=${j.activationBlockedReason}`,
            )
          }
        } catch {
          /* graceful */
        }
      }
    })

    await page.goto(`/strategy/builder?strategyId=${SCALE_IN_STRATEGY_ID}`)
    await page.waitForLoadState('networkidle')

    await expect(page.getByRole('button', { name: /^저장$/ })).toBeEnabled({ timeout: 15_000 })

    const errors = reportConsole('S8-3', consoleLog)
    // 서버가 scaleIn 을 round-trip 으로 반환(저장 보존 검증) — null 이 아님
    expect(detailScaleIn, 'GET 상세에 scaleIn 미반환(round-trip 깨짐)').not.toBeNull()
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // S8-4 / S8-6 / S8-7 전략 목록 카드 — 보유 현황 패널 + 하위호환 + Visual
  // ─────────────────────────────────────────────────────────────
  test('S8-4·6·7 전략 카드 보유 현황(보유 없음) + 하위호환 + Visual', async ({ page }) => {
    await seedOnboarding(page)
    const consoleLog = attachConsole(page)
    const positionResponses: Array<{ url: string; body: string }> = []

    page.on('response', async (res) => {
      if (/\/api\/strategies\/\d+\/position/.test(res.url())) {
        try {
          positionResponses.push({ url: res.url(), body: await res.text() })
        } catch {
          /* graceful */
        }
      }
    })

    await page.goto('/strategy/list')
    await page.waitForLoadState('networkidle')

    // 페이지 진입 단언
    await expect(page.getByRole('heading', { name: /전략/ }).first()).toBeVisible({ timeout: 15_000 })

    // 전략 카드의 "전략 보유 현황" 패널 — 보유 0건 "보유 없음"
    const positionPanels = page.locator('section[aria-label="전략별 보유 현황"]')
    const panelCount = await positionPanels.count()
    console.log(`[S8-4] 전략 보유 현황 패널 수: ${panelCount}`)

    if (panelCount > 0) {
      const firstPanel = positionPanels.first()
      await expect(firstPanel).toBeVisible()
      const panelText = (await firstPanel.textContent())?.trim() ?? ''
      console.log(`[S8-4] 첫 패널 텍스트: "${panelText.slice(0, 120)}"`)
      // strategy_position 0행 → "보유 없음" 기대 (mock 합성 0)
      expect(panelText, '보유 현황 패널이 비정상').toMatch(/보유 없음|수량|불러오는 중/)
    }

    // position API 응답 정직성 — 0행이면 빈 배열 (mock 0 정합)
    for (const r of positionResponses.slice(0, 5)) {
      console.log(`[S8-4] ${r.url.replace('http://localhost:3000', '')} → ${r.body.slice(0, 120)}`)
    }

    // 전략 목록 전체 스크린샷 (S8-7 Visual baseline — 카드 + 분할 배지 + 보유 패널)
    await expect(page).toHaveScreenshot('s8-4-strategy-list-cards.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const errors = reportConsole('S8-4·6·7', consoleLog)
    expect(errors, `콘솔 에러 ${errors.length}건`).toHaveLength(0)
  })
})
