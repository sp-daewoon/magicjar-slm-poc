/**
 * 종목 검색 커스텀 훅 (T-IM1)
 *
 * TanStack Query 기반 자동완성 검색.
 * staleTime: 60초 (자동완성은 짧은 cache가 적합).
 * query 1자 미만이면 enabled=false로 API 미호출.
 */
import { useQuery } from '@tanstack/react-query'
import { instrumentApi, type InstrumentSearchResultDto } from '../instrument'

interface UseInstrumentSearchOptions {
  /** 검색어 (1자 이상 시 API 호출) */
  query: string
  /** 결과 수 (default 10) */
  limit?: number
  /** 시장 필터 (optional) */
  market?: string
  /** IME 조합 중 여부 — true이면 API 호출 차단 (design §11-6) */
  isComposing?: boolean
}

interface UseInstrumentSearchResult {
  results: InstrumentSearchResultDto[]
  isLoading: boolean
  isError: boolean
  /** 5xx 오류 발생 여부 (graceful fallback 판단용) */
  isServerError: boolean
}

export function useInstrumentSearch({
  query,
  limit = 10,
  market,
  isComposing = false,
}: UseInstrumentSearchOptions): UseInstrumentSearchResult {
  const trimmedQuery = query.trim()
  // 1자 미만이거나 IME 조합 중이면 호출 차단
  const enabled = trimmedQuery.length >= 1 && !isComposing

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['instrument-search', trimmedQuery, limit, market],
    queryFn: () => instrumentApi.search(trimmedQuery, limit, market),
    enabled,
    staleTime: 60_000, // 60초
    retry: 1,
  })

  // 5xx 판단 (graceful raw input fallback 표시용)
  const isServerError = isError && isAxiosServerError(error)

  return {
    results: data ?? [],
    isLoading: enabled ? isLoading : false,
    isError,
    isServerError,
  }
}

/** axios 5xx 오류 판단 */
function isAxiosServerError(error: unknown): boolean {
  if (!error) return false
  const status = (error as { response?: { status?: number } })?.response?.status
  return typeof status === 'number' && status >= 500
}
