/**
 * 잔익절 방어 — minHoldBars(B안) 요청 본문 + warnings(A안) 응답 정규화 계약 테스트 (MSW)
 *
 * 1. sigStrategyApi.create — minHoldBars>0이면 본문 포함, 0/미설정이면 키 생략(하위호환)
 * 2. sigStrategyApi.get/create — 응답의 warnings·minHoldBars 정규화
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { sigStrategyApi } from '../strategy'

let lastCreateBody: Record<string, unknown> | null = null

const server = setupServer(
  http.post('/api/strategies', async ({ request }) => {
    lastCreateBody = (await request.json()) as Record<string, unknown>
    return HttpResponse.json(
      {
        id: 7,
        userId: 1,
        name: 's',
        codes: ['005930'],
        intervals: ['5m'],
        status: 'PAUSED',
        minHoldBars: lastCreateBody.minHoldBars ?? null,
        // A안 — 익절 ≤ 0.3% 저장 시 서버 경고 1건
        warnings: ['익절 목표 0.2%는 왕복 거래비용에 근접·미만입니다(잔익절).'],
        createdAt: '2026-06-08T00:00:00Z',
      },
      { status: 201 },
    )
  }),
  http.get('/api/strategies/7', () =>
    HttpResponse.json({
      id: 7,
      userId: 1,
      name: 's',
      codes: ['005930'],
      intervals: ['5m'],
      status: 'PAUSED',
      minHoldBars: 3,
      warnings: [],
      createdAt: '2026-06-08T00:00:00Z',
    }),
  ),
)

beforeAll(() => server.listen())
afterEach(() => {
  lastCreateBody = null
  server.resetHandlers()
})
afterAll(() => server.close())

describe('sigStrategyApi.create — minHoldBars 요청 본문 (B안)', () => {
  it('minHoldBars>0이면 본문에 포함한다', async () => {
    await sigStrategyApi.create({
      userId: 1,
      name: 's',
      entry: 'ma(5) crosses_above ma(20)',
      codes: ['005930'],
      intervals: ['5m'],
      minHoldBars: 3,
    })
    expect(lastCreateBody?.minHoldBars).toBe(3)
  })

  it('minHoldBars 0/미설정이면 키를 생략한다 (하위호환 비활성)', async () => {
    await sigStrategyApi.create({
      userId: 1,
      name: 's',
      entry: 'ma(5) crosses_above ma(20)',
      codes: ['005930'],
      intervals: ['5m'],
      minHoldBars: 0,
    })
    expect(lastCreateBody).not.toHaveProperty('minHoldBars')
  })
})

describe('sigStrategyApi — warnings(A안) 정규화', () => {
  it('create 응답의 warnings를 배열로 노출한다', async () => {
    const s = await sigStrategyApi.create({
      userId: 1,
      name: 's',
      entry: 'ma(5) crosses_above ma(20)',
      codes: ['005930'],
      intervals: ['5m'],
      takeProfitPct: 0.2,
    })
    expect(s.warnings).toHaveLength(1)
    expect(s.warnings?.[0]).toContain('잔익절')
  })

  it('get 응답의 minHoldBars·빈 warnings를 정규화한다', async () => {
    const s = await sigStrategyApi.get('7')
    expect(s.minHoldBars).toBe(3)
    expect(s.warnings).toEqual([])
  })
})
