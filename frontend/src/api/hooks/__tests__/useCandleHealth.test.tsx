/**
 * useCandleHealth — MSW 계약 테스트 (readiness P1)
 *
 * 백엔드(market-data) 미머지 상태이므로 orchestrator LOCK 계약 기반 MSW로 검증:
 *   GET /api/symbols/{code}/candle-health → CandleHealthDto
 *
 * 검증:
 * - 200 계약 응답 파싱 (status·reason·reasonDetail)
 * - 404 → isError (graceful, 가짜 FRESH 합성 금지)
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { useCandleHealth } from '../useCandleHealth'

const server = setupServer(
  http.get('/api/symbols/005930/candle-health', () =>
    HttpResponse.json({
      code: '005930',
      interval: '1m',
      lastCandleAt: '2026-06-05T05:00:00+09:00',
      staleSeconds: 320,
      status: 'STALE',
      reason: 'NO_TICK',
      reasonDetail: '최근 체결이 수신되지 않아 캔들이 갱신되지 않았습니다',
      checkedAt: '2026-06-05T05:05:20+09:00',
    }),
  ),
  // 백엔드 미머지/미지원 종목 → 404
  http.get('/api/symbols/999999/candle-health', () =>
    HttpResponse.json({ error: 'NOT_FOUND' }, { status: 404 }),
  ),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchInterval: false } },
  })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('useCandleHealth (MSW 계약)', () => {
  it('200 계약 응답을 파싱한다 (status·reason·reasonDetail)', async () => {
    const { result } = renderHook(() => useCandleHealth('005930'), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.status).toBe('STALE')
    expect(result.current.data?.reason).toBe('NO_TICK')
    expect(result.current.data?.reasonDetail).toContain('체결이 수신되지')
  })

  it('404는 isError로 노출된다 (graceful — 가짜 FRESH 금지)', async () => {
    const { result } = renderHook(() => useCandleHealth('999999'), { wrapper })
    // 훅 retry:1 + 백오프 때문에 기본 1s waitFor를 넘길 수 있어 여유를 둔다.
    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 5000 })
    expect(result.current.data).toBeUndefined()
  })
})
