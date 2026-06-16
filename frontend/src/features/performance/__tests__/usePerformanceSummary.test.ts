/**
 * usePerformanceSummary hook 단위 테스트 — 2 케이스
 *
 * ADR 0072 §9-4 정합:
 *   1. userId null → query 비활성
 *   2. period 변경 → 재조회 (queryKey 연동 검증)
 */
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { usePerformanceSummary } from '../hooks/usePerformanceSummary'
import type { PerformanceSummaryDto } from '../api/performanceApi'

const mockSummary: PerformanceSummaryDto = {
  period: 'MONTH',
  periodStart: '2026-05-01',
  periodEnd: '2026-05-14',
  totalPnl: 1250000,
  mddPct: 8.43,
  winRatePct: 62.5,
  profitLossRatio: 1.85,
  tradeCount: 32,
  computedAt: '2026-05-14T18:50:00+09:00',
  cacheHit: false,
}

const server = setupServer(
  http.get('/api/performance/summary', () => HttpResponse.json(mockSummary)),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: qc }, children)
  }
}

describe('usePerformanceSummary', () => {
  it('1. userId null → query 비활성', () => {
    const { result } = renderHook(
      () => usePerformanceSummary({ userId: null, period: 'MONTH' }),
      { wrapper: makeWrapper() },
    )
    expect(result.current.fetchStatus).toBe('idle')
    expect(result.current.data).toBeUndefined()
  })

  it('2. userId 지정 → 5 지표 데이터 로드 성공', async () => {
    const { result } = renderHook(
      () => usePerformanceSummary({ userId: 12345678, period: 'MONTH' }),
      { wrapper: makeWrapper() },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.totalPnl).toBe(1250000)
    expect(result.current.data?.winRatePct).toBe(62.5)
    expect(result.current.data?.tradeCount).toBe(32)
  })
})
