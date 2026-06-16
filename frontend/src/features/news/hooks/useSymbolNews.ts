/**
 * useSymbolNews — 종목별 뉴스 TanStack Query hook
 *
 * ADR 0075 §6-2 polling interval LOCK:
 *   refetchInterval: 60_000 (60초)
 *
 * STOMP 0 LOCK (§6 사용자 결정 6-1): STOMP client import 0.
 * 종목 상세 페이지 unmount 시 polling 자동 stop (queryClient cleanup).
 *
 * 기본 조회 기간: 최근 7일 (from = T-7일 ISO 8601)
 *
 * dogfooding hotfix: useInfiniteQuery로 변경 — "더 보기" 시 기존 items 보존 누적 append.
 */
import { useInfiniteQuery } from '@tanstack/react-query'
import { newsApi, type SymbolNewsParams } from '../api/newsApi'

/** 7일 전 YYYY-MM-DD 문자열 계산 (backend LocalDate 정합 — PR #440) */
function sevenDaysAgo(): string {
  const d = new Date()
  d.setDate(d.getDate() - 7)
  return d.toISOString().slice(0, 10)
}

interface UseSymbolNewsOptions {
  enabled?: boolean
  refetchInterval?: number | false
}

export function useSymbolNews(
  code: string,
  params: SymbolNewsParams = {},
  options: UseSymbolNewsOptions = {},
) {
  const { enabled = true, refetchInterval = 60_000 } = options

  return useInfiniteQuery({
    queryKey: ['symbolNews', code, params] as const,
    queryFn: ({ pageParam }) =>
      newsApi.getNews(code, {
        from: params.from ?? sevenDaysAgo(),
        ...params,
        page: pageParam,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    // ADR 0075 §6-2 LOCK: 60s polling
    refetchInterval,
    refetchIntervalInBackground: false,
    staleTime: 30_000,
    enabled: Boolean(code) && enabled,
    retry: 1,
    retryDelay: 3000,
  })
}
