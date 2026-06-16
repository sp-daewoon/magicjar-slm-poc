/**
 * useJournalEntries hook 단위 테스트 — 2 케이스
 *
 * ADR 0072 §9-4 + spec §3-3 정합:
 *   1. userId null → enabled=false (query 비활성)
 *   2. userId 지정 → journalApi.listEntries 호출 (msw mock)
 */
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useJournalEntries } from '../hooks/useJournalEntries'

const mockPage = {
  content: [
    {
      id: 1001,
      eventType: 'ORDER_FILLED',
      instrumentCode: '005930',
      instrumentName: '삼성전자',
      occurredAt: '2026-05-14T14:32:18+09:00',
      summary: '체결: 삼성전자 매수 100주 @ 75,000원',
      realizedPnl: '150000.00',
      payload: {},
      notes: [],
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
}

const server = setupServer(
  http.get('/api/journal/entries', () => HttpResponse.json(mockPage)),
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

describe('useJournalEntries', () => {
  it('1. userId null → query 비활성 (fetchStatus idle)', () => {
    const { result } = renderHook(
      () => useJournalEntries({ userId: null }),
      { wrapper: makeWrapper() },
    )
    expect(result.current.fetchStatus).toBe('idle')
    expect(result.current.data).toBeUndefined()
  })

  it('2. userId 지정 → 데이터 로드 성공', async () => {
    const { result } = renderHook(
      () => useJournalEntries({ userId: 12345678 }),
      { wrapper: makeWrapper() },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.content).toHaveLength(1)
    expect(result.current.data?.content[0].eventType).toBe('ORDER_FILLED')
  })
})
