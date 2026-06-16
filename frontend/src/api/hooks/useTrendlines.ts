import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  trendlineApi,
  type TrendlineAutoCreateResponse,
  type TrendlineCreateRequest,
  type TrendlineDto,
  type TrendlineUpdateRequest,
} from '../trendlines'
import type { CandleInterval } from '../symbol'

const trendlineQueryKey = (code: string) => ['trendlines', code] as const

export function useTrendlines(code: string) {
  return useQuery<TrendlineDto[]>({
    queryKey: trendlineQueryKey(code),
    queryFn: () => trendlineApi.list(code),
    enabled: Boolean(code),
    staleTime: 30_000,
  })
}

export function useCreateTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineDto, unknown, TrendlineCreateRequest>({
    mutationFn: (request) => trendlineApi.create(code, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

export function useUpdateTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineDto, unknown, { id: number; request: TrendlineUpdateRequest }>({
    mutationFn: ({ id, request }) => trendlineApi.update(code, id, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

export function useCreateAutoTrendlines(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineAutoCreateResponse, unknown, CandleInterval>({
    mutationFn: (interval) => trendlineApi.createAuto(code, interval),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

export function useDeleteTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, number>({
    mutationFn: (id) => trendlineApi.delete(code, id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}
