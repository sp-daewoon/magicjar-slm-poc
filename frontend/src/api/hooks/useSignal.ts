/**
 * Signal TanStack Query hooks (T-SIG Phase 3 — ADR 0066)
 *
 * hooks:
 *   useSignals(params)   — useInfiniteQuery (무한 스크롤)
 *   useSignal(id)        — useQuery (단건)
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import {
  signalApi,
  type SignalDto,
  type SignalListParams,
  type SignalPage,
  type SignalTraceDto,
} from '../signal'

const SIGNAL_LIST_KEY = (params: SignalListParams) =>
  ['signals', 'list', params] as const

const SIGNAL_KEY = (id: string) => ['signals', id] as const

const SIGNAL_TRACE_KEY = (id: string) => ['signals', id, 'trace'] as const

/**
 * 시그널 목록 (무한 스크롤)
 * page=0 → page=1 → ... 자동 누적
 */
export function useSignals(params: Omit<SignalListParams, 'page'> = {}, size = 20) {
  return useInfiniteQuery<SignalPage, Error>({
    queryKey: SIGNAL_LIST_KEY({ ...params, size }),
    queryFn: ({ pageParam }) =>
      signalApi.list({ ...params, size, page: pageParam as number }),
    initialPageParam: 0 as number,
    getNextPageParam: (lastPage) =>
      lastPage.last ? undefined : lastPage.page + 1,
    staleTime: 15 * 1000,
  })
}

/**
 * 시그널 단건 조회
 */
export function useSignal(id: string | undefined, userId: number | null | undefined) {
  return useQuery<SignalDto>({
    queryKey: SIGNAL_KEY(id ?? ''),
    queryFn: () => signalApi.get(id!, userId!),
    enabled: !!id && userId !== null && userId !== undefined,
    staleTime: 60 * 1000,
  })
}

/**
 * 시그널 키 사슬 추적 (ADR 0115 §2-B 불변식 #4)
 *
 * 사인 → 주문 → 체결 → 보유 + 정합 드릴다운. `enabled` 로 행 확장 시에만 fetch (lazy).
 */
export function useSignalTrace(
  id: string | undefined,
  userId: number | null | undefined,
  enabled = true,
) {
  return useQuery<SignalTraceDto>({
    queryKey: SIGNAL_TRACE_KEY(id ?? ''),
    queryFn: () => signalApi.trace(id!, userId!),
    enabled: enabled && !!id && userId !== null && userId !== undefined,
    staleTime: 15 * 1000,
  })
}
