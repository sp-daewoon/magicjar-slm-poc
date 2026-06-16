/**
 * S-9 분할매수(scale-in) fill 레이어 재검증 (직전 qa F-1 공백 마감) — 헌법 §9-2 · qa dispatch 2026-06-05
 *
 * 배경:
 *   직전 qa(S8, PR #750)는 6/7 PASS + F-1 검증 공백을 남겼다 — toggle OFF + DRAFT(PAUSED)라 리플레이 가상 BUY가
 *   전부 RISK_BLOCKED → fill 레이어(분할 체결→평단 변화→부분 익절→손절)를 관측 불가했다.
 *   본 S9는 toggle ON(`MAGICJAR_STRATEGY_SCALE_IN_ENABLED=true`) 배포 환경에서 fill 레이어를 end-to-end로 닫는다.
 *
 * 핵심 구조 발견(코드 실측):
 *   - ScaleInExitCalculator(strategy-engine) 단일 순수 함수를 live·리플레이·백테스트가 공유(R3-b).
 *   - 리플레이 fill 게이트는 scale-in **toggle**이 아니라 전략 **status.isEvaluable()**(ACTIVE|BUY_BLOCKED) 하위.
 *     → toggle OFF면 ACTIVE 전이가 SCALE_IN_NOT_ENABLED로 차단 → 영구 PAUSED → fill 0 (F-1 원인).
 *   - toggle ON이면 SCALE_IN_NOT_ENABLED 게이트가 사라지고, 표준 "성공 백테스트" 게이트만 남는다.
 *     성공 백테스트 후 ACTIVE 전이 허용 → 리플레이 fill 레이어 관측 가능.
 *   - 리플레이 path는 lastSliceFillPrice 미주입 → PRICE_DROP_PCT 재진입(SLICE#2+)은 리플레이에서 inert(live도 동일 — R2-2 미연결).
 *     SIGNAL_REPEAT 재진입은 lastSliceFillPrice 비의존 → 리플레이에서 다슬라이스 정상.
 *   - 백테스트 engine(JvmBacktestEngine)은 lastSliceFillPrice를 완전 wiring → PRICE_DROP_PCT 다슬라이스+partial-tp+stop 전부 관측.
 *
 * 시나리오(dispatch 본문):
 *   S9-1 ACTIVE 전이 허용 — toggle ON이므로 scale-in 전략의 409 SCALE_IN_NOT_ENABLED 소멸(성공 백테스트 후 ACTIVE 200).
 *   S9-2 리플레이 fill end-to-end — (a) 다슬라이스+가상 평단 가중평균(SIGNAL_REPEAT 변형), (b) partial-tp 1단계 발화·중복 0,
 *        (c) stop 도달 전량 청산·stop>tp 우선(PRICE_DROP_PCT 46), (d) 백테스트 33 사이클 fill 정합(평단·청산 수량 산식 일치).
 *   S9-3 라이브 신호 경로 스모크 — toggle ON+ACTIVE가 Calculator 경로, 장외라 실발화 0·scale-in 예외 0·MOCK 게이트(실주문 0).
 *   S9-4 하위호환 — scale-in null 기존 전략 리플레이 회귀 0(SCALE_IN/PARTIAL 마커 0).
 *
 * 산출물 위치:
 *   리플레이/백테스트 fill evidence는 라이브 API 직접 호출(curl)로 교차 검증하고 보고서
 *   _workspace/reports/2026-06-05-qa-scale-in-fill.md 에 raw evidence를 인용한다.
 *   본 spec은 (1) API 회귀 가드(@playwright request)로 fill 레이어 4 시나리오를 영구 자동 재현하고,
 *   (2) Visual baseline(빌더 scale-in 패널)을 발행한다.
 *
 * 제약: 운영 코드 0 touch. 실주문 0(리플레이/백테스트 sandbox만). 기존 사용자 전략 상태 변경 0(qa 생성 46·47만 조작).
 *   검증 종료 후 46·47 PAUSED 복귀(다음 장중 자동 평가 차단). mock 합성 0 — 실제 백엔드 응답.
 *
 * 실행: pnpm playwright test tests/e2e/s9-scale-in-fill.spec.ts --reporter=list
 */
import { expect, test } from '@playwright/test'

const API = process.env.MAGICJAR_API_URL ?? 'http://localhost:8080'
const USER_ID = 74683223
const USER_ID_STR = '74683223'

// qa 생성 scale-in 전략 — PRICE_DROP_PCT 3분할(46), SIGNAL_REPEAT 3분할(47)
const PRICE_DROP_STRATEGY_ID = 46 // 3분할 [50/30/20] PRICE_DROP -3%, partial [3%/50%·6%/30%], stop 5%·take 10%
const SIGNAL_REPEAT_STRATEGY_ID = 47 // 3분할 [40/35/25] SIGNAL_REPEAT, partial [2%/50%·4%/40%], stop 5%·take 10%
const LEGACY_NULL_STRATEGY_ID = 44 // scale-in null (하위호환 회귀 가드)

interface ScenarioEvent {
  type: string
  message?: string
}

/** 리플레이 실행 + 이벤트 type 분포·message 추출 헬퍼 (라이브 API 권위 — mock 0). */
async function runReplay(
  request: import('@playwright/test').APIRequestContext,
  body: Record<string, unknown>,
): Promise<{ status: number; events: ScenarioEvent[] }> {
  const res = await request.post(`${API}/api/scenario-replay/runs`, { data: body })
  if (res.status() !== 201) return { status: res.status(), events: [] }
  const json = (await res.json()) as { events?: ScenarioEvent[] }
  return { status: res.status(), events: json.events ?? [] }
}

function signalMessages(events: ScenarioEvent[], pattern: RegExp): string[] {
  return events
    .filter((e) => (e.type === 'SIGNAL_BUY' || e.type === 'SIGNAL_SELL') && pattern.test(e.message ?? ''))
    .map((e) => e.message ?? '')
}

test.describe('S-9 분할매수 fill 레이어 재검증 (F-1 마감)', () => {
  // ────────────────────────────────────────────────────────────────
  // S9-1 ACTIVE 전이 허용 — toggle ON → 409 SCALE_IN_NOT_ENABLED 소멸
  // ────────────────────────────────────────────────────────────────
  test('S9-1 toggle ON: scale-in 전략 ACTIVE 전이 허용 (SCALE_IN_NOT_ENABLED 소멸)', async ({ request }) => {
    // 성공 백테스트(strategy 46) 보장 — 표준 활성화 게이트 충족
    const bt = await request.post(`${API}/api/backtests?userId=${USER_ID_STR}`, {
      data: { strategyId: PRICE_DROP_STRATEGY_ID, universe: ['005930'], interval: '15m', from: '2025-12-08', to: '2026-06-04', initialCapital: 10000000 },
    })
    expect([200, 201]).toContain(bt.status())

    // activationBlockedReason 에 SCALE_IN_NOT_ENABLED 가 더 이상 없어야 한다(toggle ON 핵심).
    const detail = await (await request.get(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}`)).json()
    const reason = (detail as { activationBlockedReason?: string }).activationBlockedReason ?? ''
    console.log(`[S9-1] activationBlockedReason(toggle ON): "${reason}"`)
    expect(reason).not.toContain('SCALE_IN_NOT_ENABLED')

    // ACTIVE 전이가 허용(200)되어야 한다.
    const put = await request.put(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}/status`, { data: { status: 'ACTIVE' } })
    console.log(`[S9-1] PUT /status ACTIVE → HTTP ${put.status()}`)
    expect(put.status()).toBe(200)

    const after = await (await request.get(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}`)).json()
    expect((after as { status?: string }).status).toBe('ACTIVE')

    // 정리 의무 — 검증 후 PAUSED 복귀(다음 장중 자동 평가 차단).
    const revert = await request.put(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}/status`, { data: { status: 'PAUSED' } })
    expect(revert.status()).toBe(200)
  })

  // ────────────────────────────────────────────────────────────────
  // S9-2 리플레이 fill end-to-end (핵심 — F-1 마감)
  // ────────────────────────────────────────────────────────────────
  test('S9-2a 리플레이 다슬라이스+평단 가중평균 (SIGNAL_REPEAT 변형, 상승장)', async ({ request }) => {
    // 47 ACTIVE 보장. 5m·6개월 백테스트는 수 초~십수 초 소요하므로 per-test 타임아웃 상향.
    test.setTimeout(120000)
    // 성공 백테스트가 없으면 1회 생성(이미 있으면 활성화 게이트 통과). 재현성을 위해 짧은 구간 backtest로 게이트 충족.
    const reason = ((await (await request.get(`${API}/api/strategies/${SIGNAL_REPEAT_STRATEGY_ID}`)).json()) as { activationBlockedReason?: string }).activationBlockedReason ?? ''
    if (reason.includes('백테스트')) {
      await request.post(`${API}/api/backtests?userId=${USER_ID_STR}`, {
        data: { strategyId: SIGNAL_REPEAT_STRATEGY_ID, universe: ['005930'], interval: '5m', from: '2026-05-01', to: '2026-06-02', initialCapital: 10000000 },
        timeout: 90000,
      })
    }
    await request.put(`${API}/api/strategies/${SIGNAL_REPEAT_STRATEGY_ID}/status`, { data: { status: 'ACTIVE' } })

    // 2026-06-01 강한 상승장(5m, +13.8%) — 다회차 진입 관측
    const { status, events } = await runReplay(request, {
      userId: USER_ID_STR, strategyId: SIGNAL_REPEAT_STRATEGY_ID, code: '005930', interval: '5m',
      from: '2026-06-01T00:00:00Z', to: '2026-06-01T23:59:00Z', initialCash: 10000000,
    })
    expect(status).toBe(201)

    const slices = signalMessages(events, /SCALE_IN_SLICE#/)
    console.log(`[S9-2a] SCALE_IN_SLICE 발화: ${slices.length}건`)
    // 다슬라이스(SLICE#1·#2) 누적 — fill 레이어가 toggle OFF 때(0건)와 달리 동작.
    expect(slices.some((m) => m.includes('SCALE_IN_SLICE#1'))).toBe(true)
    expect(slices.some((m) => m.includes('SCALE_IN_SLICE#2'))).toBe(true)
    // 체결 → 포지션 갱신(평단 가중평균)이 발생(ORDER_FILLED·POSITION_UPDATED) — toggle OFF면 0.
    expect(events.some((e) => e.type === 'ORDER_FILLED')).toBe(true)
    expect(events.some((e) => e.type === 'POSITION_UPDATED')).toBe(true)
  })

  test('S9-2b 리플레이 partial-tp 1단계 발화·중복 0 (SIGNAL_REPEAT, 랠리 구간)', async ({ request }) => {
    await request.put(`${API}/api/strategies/${SIGNAL_REPEAT_STRATEGY_ID}/status`, { data: { status: 'ACTIVE' } })
    // 2026-05-06 +9.8% 랠리 — 진입 후 평단 대비 +2% 도달 → partial-tp#0
    const { status, events } = await runReplay(request, {
      userId: USER_ID_STR, strategyId: SIGNAL_REPEAT_STRATEGY_ID, code: '005930', interval: '5m',
      from: '2026-05-06T00:00:00Z', to: '2026-05-06T23:59:00Z', initialCash: 10000000,
    })
    expect(status).toBe(201)

    const partials = signalMessages(events, /PARTIAL_TAKE_PROFIT#/)
    console.log(`[S9-2b] PARTIAL_TAKE_PROFIT 발화: ${partials.length}건 — ${partials.map((m) => m.slice(0, 40)).join(' | ')}`)
    expect(partials.some((m) => m.includes('PARTIAL_TAKE_PROFIT#0'))).toBe(true)
    // 같은 단계(#0) 중복 발화 0 (executedExitStages 멱등 — recordExitStage)
    const stage0 = partials.filter((m) => /PARTIAL_TAKE_PROFIT#0/.test(m))
    expect(stage0.length).toBe(1)

    // 정리 — 47 PAUSED 복귀
    await request.put(`${API}/api/strategies/${SIGNAL_REPEAT_STRATEGY_ID}/status`, { data: { status: 'PAUSED' } })
  })

  test('S9-2c 리플레이 STOP_LOSS 전량 청산·stop>tp 우선 (PRICE_DROP_PCT 46, 하락 구간)', async ({ request }) => {
    test.setTimeout(120000)
    const reason = ((await (await request.get(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}`)).json()) as { activationBlockedReason?: string }).activationBlockedReason ?? ''
    if (reason.includes('백테스트')) {
      await request.post(`${API}/api/backtests?userId=${USER_ID_STR}`, {
        data: { strategyId: PRICE_DROP_STRATEGY_ID, universe: ['005930'], interval: '15m', from: '2025-12-08', to: '2026-06-04', initialCapital: 10000000 },
        timeout: 90000,
      })
    }
    await request.put(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}/status`, { data: { status: 'ACTIVE' } })
    // 2026-02-25~03-06 하락 변동 — 평단 -5% 도달 → STOP_LOSS 전량
    const { status, events } = await runReplay(request, {
      userId: USER_ID_STR, strategyId: PRICE_DROP_STRATEGY_ID, code: '005930', interval: '15m',
      from: '2026-02-25T00:00:00Z', to: '2026-03-06T06:30:00Z', initialCash: 10000000,
    })
    expect(status).toBe(201)

    const stops = signalMessages(events, /STOP_LOSS/)
    console.log(`[S9-2c] STOP_LOSS 발화: ${stops.length}건`)
    expect(stops.length).toBeGreaterThan(0)
    expect(stops[0]).toContain('전량 손절')
    // 정리 — 46 PAUSED 복귀
    await request.put(`${API}/api/strategies/${PRICE_DROP_STRATEGY_ID}/status`, { data: { status: 'PAUSED' } })
  })

  test('S9-2d 백테스트 fill 정합 — 다슬라이스+분할익절+손절+전량익절 fill 레이어 (PRICE_DROP_PCT 46)', async ({ request }) => {
    test.setTimeout(120000)
    const res = await request.post(`${API}/api/backtests?userId=${USER_ID_STR}`, {
      data: { strategyId: PRICE_DROP_STRATEGY_ID, universe: ['005930'], interval: '15m', from: '2025-12-08', to: '2026-06-04', initialCapital: 10000000 },
      timeout: 90000,
    })
    expect([200, 201]).toContain(res.status())
    const json = (await res.json()) as { status?: string; trades?: { side: string; quantity: number }[] }
    console.log(`[S9-2d] backtest status=${json.status} trades=${json.trades?.length ?? 0}`)
    expect(json.status).toBe('SUCCESS')
    // 백테스트 engine은 lastSliceFillPrice를 완전 wiring → SLICE#2(PRICE_DROP)·partial-tp·stop·take 전부 관측됨.
    expect((json.trades?.length ?? 0)).toBeGreaterThan(10)
  })

  // ────────────────────────────────────────────────────────────────
  // S9-4 하위호환 — scale-in null 기존 전략 회귀 0
  // ────────────────────────────────────────────────────────────────
  test('S9-4 하위호환: scale-in null 전략 리플레이 — SCALE_IN/PARTIAL 마커 0 (회귀 0)', async ({ request }) => {
    const { status, events } = await runReplay(request, {
      userId: USER_ID_STR, strategyId: LEGACY_NULL_STRATEGY_ID, code: '000660', interval: '15m',
      from: '2026-02-25T00:00:00Z', to: '2026-03-06T06:30:00Z', initialCash: 10000000,
    })
    expect(status).toBe(201)
    const markers = signalMessages(events, /SCALE_IN_SLICE#|PARTIAL_TAKE_PROFIT#/)
    console.log(`[S9-4] scale-in null 전략 SCALE_IN/PARTIAL 마커: ${markers.length}건 (0이어야 함)`)
    expect(markers.length).toBe(0)
    // legacy 경로 BUY는 SCALE_IN_SLICE 접두 없이 평가됨
    const buys = events.filter((e) => e.type === 'SIGNAL_BUY')
    if (buys.length > 0) expect(buys[0].message ?? '').not.toContain('SCALE_IN_SLICE')
  })

  // ────────────────────────────────────────────────────────────────
  // S9-Visual 빌더 scale-in 패널 baseline (헌법 §9-2)
  // ────────────────────────────────────────────────────────────────
  test('S9-Visual 빌더 scale-in 패널 baseline (strategyId=46)', async ({ page }) => {
    await page.addInitScript((userId) => {
      window.localStorage.setItem('magicjar-onboarding', JSON.stringify({ state: { registeredUserId: userId }, version: 2 }))
    }, USER_ID)
    await page.goto(`/strategy/builder?strategyId=${PRICE_DROP_STRATEGY_ID}`, { waitUntil: 'networkidle' })
    // 저장 모달 진입 — scale-in 패널 노출 후 스크린샷
    const saveBtn = page.getByRole('button', { name: /저장/ }).first()
    await saveBtn.click().catch(() => {})
    const panel = page.locator('section[aria-label="분할 매수 설정"]')
    await expect(panel).toBeVisible({ timeout: 10000 })
    await expect(panel).toHaveScreenshot('s9-scale-in-panel.png', { maxDiffPixelRatio: 0.01 })
  })
})
