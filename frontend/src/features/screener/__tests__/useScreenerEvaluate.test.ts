/**
 * useScreenerEvaluate 단위 테스트 — mutation 흐름
 *
 * ADR 0071 §9-4 정합:
 *   1. 정상 mutation → data 반환
 *   2. 에러 mutation → error 반환
 */
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { useScreenerEvaluate } from '../hooks/useScreenerEvaluate'
import type { ScreenerRunResponse } from '../api/screenerApi'
import React from 'react'

const mockResult: ScreenerRunResponse = {
  cacheKey: 'screener:cache:abc:xyz',
  universeSize: 2000,
  evaluatedCount: 1990,
  matchedCount: 5,
  skippedReasons: {},
  matches: [],
  elapsedMs: 3200,
  cacheHit: false,
}

let lastEvaluateBody: Record<string, unknown> | null = null

const server = setupServer(
  http.post('/api/screener/evaluate', async ({ request }) => {
    lastEvaluateBody = await request.json() as Record<string, unknown>
    return HttpResponse.json(mockResult)
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  lastEvaluateBody = null
  server.resetHandlers()
})
afterAll(() => server.close())

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: qc }, children)
}

describe('useScreenerEvaluate', () => {
  it('1. 정상 mutation → data 반환', async () => {
    const { result } = renderHook(() => useScreenerEvaluate(), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ userId: 1, strategyId: 42, interval: '3m', limit: 50 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.matchedCount).toBe(5)
    expect(result.current.data?.cacheHit).toBe(false)
    expect(lastEvaluateBody?.interval).toBe('3m')
  })

  it('2. 서버 에러 → error 반환', async () => {
    server.use(
      http.post('/api/screener/evaluate', () =>
        HttpResponse.json({ message: 'Strategy not found' }, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => useScreenerEvaluate(), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ userId: 1, strategyId: 999, limit: 50 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeTruthy()
  })
})
