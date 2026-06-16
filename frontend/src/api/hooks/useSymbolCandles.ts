/**
 * useSymbolCandles — 차트 series 무한 스크롤 TanStack Query hook + boundary STOMP subscribe
 *
 * spec §6-3 LOCK:
 * - queryKey: ['symbolCandles', code, interval, pageSize]
 * - useInfiniteQuery + before cursor 페이지네이션
 * - 1m cold-start 0건이면 SymbolDetail gap-fill 완료를 따라잡기 위해 짧은 REST polling fallback
 * - getNextPageParam: lastPage.hasMore ? lastPage.nextBefore : undefined
 *
 * ADR 0019 §2-2: interval 확장 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d...)
 * ADR 0029 §2-1: 1m = DB + Redis candle:current 합류, 1d = DB + Redis candle:today 합류
 *
 * ADR 0064 §2-C: 합산 interval(3m·5m·15m·30m·90m·1h·4h·12h) — boundary 마감 시점 STOMP 실시간 갱신
 *   interval이 BOUNDARY_INTERVALS 중 하나일 때 /topic/market.candle.{interval}.{code} 구독
 *   interval 변경 시 기존 구독 해제 → 새 interval 구독
 *   boundary candle 수신 시 onBoundaryCandle 콜백 호출
 *
 * ADR 0093 §결정 LOCK Phase 4-D (2026-05-20):
 *   - pageSize prop 추가 — 사용자 입력 봉수 (CandleCountSelector value)
 *   - queryKey에 pageSize 포함 → cache 분리 (pageSize 변경 시 re-fetch)
 *   - backend `/candles?limit=<pageSize>` 전달 (Phase 3 backend cap 5000 정합)
 *   - default 200 (현행 동작 회귀 안전 — props 미지정 시)
 */
import { useInfiniteQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useStompTopic } from '../../lib/stomp'
import { marketCandle } from '../stompDestinations'
import {
  symbolApi,
  type CandleClosedPayload,
  type CandleInterval,
  isBoundaryInterval,
} from '../symbol'

// ──────────────────────────────────────────────
// useSymbolCandles — REST + STOMP boundary 통합 hook
// ──────────────────────────────────────────────

/** 기본 페이지 크기 — props 미지정 시 (현행 동작 회귀 안전) */
const DEFAULT_PAGE_SIZE = 200
export const ONE_MINUTE_EMPTY_REFETCH_INTERVAL_MS = 3_000

function candleCountFromPages(data: unknown): number | null {
  if (!data || typeof data !== 'object' || !('pages' in data)) return null
  const pages = (data as { pages?: unknown }).pages
  if (!Array.isArray(pages)) return null
  return pages.reduce((sum, page) => {
    if (!page || typeof page !== 'object' || !('candles' in page)) return sum
    const candles = (page as { candles?: unknown }).candles
    return sum + (Array.isArray(candles) ? candles.length : 0)
  }, 0)
}

export function resolveOneMinuteEmptyRefetchInterval(
  interval: CandleInterval,
  data: unknown,
): number | false {
  if (interval !== '1m') return false
  const candleCount = candleCountFromPages(data)
  return candleCount === 0 ? ONE_MINUTE_EMPTY_REFETCH_INTERVAL_MS : false
}

export interface UseSymbolCandlesResult {
  /** TanStack useInfiniteQuery 결과 (REST fetch) */
  data: ReturnType<typeof useInfiniteQueryForCandles>['data']
  isLoading: ReturnType<typeof useInfiniteQueryForCandles>['isLoading']
  isFetchingNextPage: ReturnType<typeof useInfiniteQueryForCandles>['isFetchingNextPage']
  fetchNextPage: ReturnType<typeof useInfiniteQueryForCandles>['fetchNextPage']
  hasNextPage: ReturnType<typeof useInfiniteQueryForCandles>['hasNextPage']
  isError: ReturnType<typeof useInfiniteQueryForCandles>['isError']
  /** ADR 0064 §2-C: 가장 최근에 수신한 boundary closed candle (합산 interval 전용) */
  latestBoundaryCandle: CandleClosedPayload | null
}

/** 타입 추론 전용 내부 헬퍼 — export 0 */
function useInfiniteQueryForCandles(code: string, interval: CandleInterval, pageSize: number) {
  return useInfiniteQuery({
    queryKey: ['symbolCandles', code, interval, pageSize] as const,
    queryFn: ({ pageParam }) =>
      symbolApi.getCandles(code, interval, pageParam as string | undefined, pageSize),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.nextBefore : undefined),
    enabled: Boolean(code),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    refetchOnWindowFocus: true,
    refetchInterval: (query) => resolveOneMinuteEmptyRefetchInterval(interval, query.state.data),
    refetchIntervalInBackground: false,
    retry: 1,
  })
}

export function useSymbolCandles(
  code: string,
  interval: CandleInterval,
  pageSize: number = DEFAULT_PAGE_SIZE,
): UseSymbolCandlesResult {
  // REST 무한 스크롤 query
  const infiniteQuery = useInfiniteQueryForCandles(code, interval, pageSize)

  // boundary closed candle 상태 (ADR 0064 §2-C)
  const [latestBoundaryCandle, setLatestBoundaryCandle] = useState<CandleClosedPayload | null>(null)

  // interval 변경 시 이전 boundary candle 초기화 (stale 방지)
  const prevIntervalRef = useRef(interval)
  useEffect(() => {
    if (prevIntervalRef.current !== interval) {
      setLatestBoundaryCandle(null)
      prevIntervalRef.current = interval
    }
  }, [interval])

  // boundary closed candle 수신 콜백 (useCallback으로 안정화)
  const handleBoundaryCandle = useCallback((payload: CandleClosedPayload) => {
    // eslint-disable-next-line no-console
    console.debug(
      `[useSymbolCandles] boundary closed 수신: code=${payload.code} interval=${payload.interval} ts=${payload.ts} close=${payload.close}`,
    )
    setLatestBoundaryCandle(payload)
  }, [])

  // ADR 0064 §2-C: 합산 interval boundary STOMP 구독
  // isBoundaryInterval 검사 — 1m·1d·1wk·1mo·1y 는 구독 0
  const boundaryTopic = isBoundaryInterval(interval)
    ? marketCandle(interval, code)
    : ''
  const boundaryEnabled = Boolean(code) && isBoundaryInterval(interval)

  useStompTopic<CandleClosedPayload>(boundaryTopic, handleBoundaryCandle, boundaryEnabled)

  return {
    data: infiniteQuery.data,
    isLoading: infiniteQuery.isLoading,
    isFetchingNextPage: infiniteQuery.isFetchingNextPage,
    fetchNextPage: infiniteQuery.fetchNextPage,
    hasNextPage: infiniteQuery.hasNextPage,
    isError: infiniteQuery.isError,
    latestBoundaryCandle,
  }
}
