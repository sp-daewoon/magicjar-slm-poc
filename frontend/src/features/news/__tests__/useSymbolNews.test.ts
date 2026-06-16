/**
 * useSymbolNews hook 단위 테스트
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   - refetchInterval 60s (60_000ms) 검증
 *   - getNextPageParam 로직 검증
 *
 * STOMP 0 LOCK 검증: useSymbolNews import 내 STOMP client 없음.
 * dogfooding hotfix: useInfiniteQuery 반환 시그니처로 테스트 갱신.
 */
import { beforeEach, describe, it, expect, vi } from 'vitest'

// useInfiniteQuery mock
vi.mock('@tanstack/react-query', () => ({
  useInfiniteQuery: vi.fn((options) => {
    return {
      _capturedOptions: options,
      data: undefined,
      isLoading: false,
      isError: false,
      fetchNextPage: vi.fn(),
      hasNextPage: false,
      isFetchingNextPage: false,
    }
  }),
}))

// newsApi mock
vi.mock('../api/newsApi', () => ({
  newsApi: {
    getNews: vi.fn().mockResolvedValue({
      stockCode: '005930',
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }),
  },
}))

import { useInfiniteQuery } from '@tanstack/react-query'
import { useSymbolNews } from '../hooks/useSymbolNews'

const mockUseInfiniteQuery = vi.mocked(useInfiniteQuery)

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('useSymbolNews', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('refetchInterval이 60_000ms (60s)로 설정된다 (ADR 0075 §6-2 LOCK)', () => {
    useSymbolNews('005930')

    expect(mockUseInfiniteQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseInfiniteQuery.mock.calls[0][0] as { refetchInterval?: number }
    expect(callArgs.refetchInterval).toBe(60_000)
  })

  it('code가 빈 문자열이면 enabled=false — 쿼리 비활성화', () => {
    useSymbolNews('')

    expect(mockUseInfiniteQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseInfiniteQuery.mock.calls[0][0] as { enabled?: boolean }
    expect(callArgs.enabled).toBe(false)
  })

  it('queryKey에 code가 포함된다', () => {
    useSymbolNews('000660')

    expect(mockUseInfiniteQuery).toHaveBeenCalledOnce()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const callArgs = mockUseInfiniteQuery.mock.calls[0][0] as any
    expect(Array.from(callArgs.queryKey as readonly unknown[])).toContain('symbolNews')
    expect(Array.from(callArgs.queryKey as readonly unknown[])).toContain('000660')
  })

  it('initialPageParam이 0이다', () => {
    useSymbolNews('005930')

    expect(mockUseInfiniteQuery).toHaveBeenCalledOnce()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const callArgs = mockUseInfiniteQuery.mock.calls[0][0] as any
    expect(callArgs.initialPageParam).toBe(0)
  })

  it('getNextPageParam — 마지막 페이지가 아니면 다음 page 번호 반환', () => {
    useSymbolNews('005930')

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const callArgs = mockUseInfiniteQuery.mock.calls[0][0] as any
    const getNext = callArgs.getNextPageParam as (
      lastPage: { page: number; totalPages: number },
      allPages: unknown[],
      lastPageParam: unknown,
      allPageParams: unknown[],
    ) => number | undefined

    // page=0, totalPages=3 → 1 반환
    expect(getNext({ page: 0, totalPages: 3 }, [], undefined, [])).toBe(1)
    // page=2, totalPages=3 → undefined (마지막)
    expect(getNext({ page: 2, totalPages: 3 }, [], undefined, [])).toBeUndefined()
  })
})
