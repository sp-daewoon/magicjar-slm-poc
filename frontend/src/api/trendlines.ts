import apiClient from '../lib/axios'
import type { CandleInterval } from './symbol'

export type TrendlineType = 'SUPPORT' | 'RESISTANCE'
export type TrendlineSource = 'MANUAL' | 'AUTO'

export interface TrendlineDto {
  id: number
  code: string
  sourceInterval: string
  type: TrendlineType
  source: TrendlineSource
  anchor1Ts: string
  anchor1Price: string
  anchor2Ts: string
  anchor2Price: string
  extendRight: boolean
  extendLeft: boolean
  label?: string | null
  color?: string | null
  createdAt: string
  updatedAt: string
}

export interface TrendlineCreateRequest {
  sourceInterval: string
  type: TrendlineType
  anchor1Ts: string
  anchor1Price: string
  anchor2Ts: string
  anchor2Price: string
  extendRight?: boolean
  extendLeft?: boolean
  label?: string | null
  color?: string | null
}

export interface TrendlineUpdateRequest {
  type: TrendlineType
  anchor1Ts: string
  anchor1Price: string
  anchor2Ts: string
  anchor2Price: string
  extendRight?: boolean
  extendLeft?: boolean
  label?: string | null
  color?: string | null
}

export interface TrendlineAutoCreateResponse {
  trendlines: TrendlineDto[]
}

export const trendlineApi = {
  list: async (code: string): Promise<TrendlineDto[]> => {
    const res = await apiClient.get<TrendlineDto[]>(
      `/symbols/${encodeURIComponent(code)}/trendlines`,
    )
    return res.data
  },

  create: async (
    code: string,
    request: TrendlineCreateRequest,
  ): Promise<TrendlineDto> => {
    const res = await apiClient.post<TrendlineDto>(
      `/symbols/${encodeURIComponent(code)}/trendlines`,
      request,
    )
    return res.data
  },

  update: async (
    code: string,
    id: number,
    request: TrendlineUpdateRequest,
  ): Promise<TrendlineDto> => {
    const res = await apiClient.put<TrendlineDto>(
      `/symbols/${encodeURIComponent(code)}/trendlines/${id}`,
      request,
    )
    return res.data
  },

  createAuto: async (
    code: string,
    interval: CandleInterval,
    lookback = 240,
  ): Promise<TrendlineAutoCreateResponse> => {
    const params = new URLSearchParams({ interval, lookback: String(lookback) })
    const res = await apiClient.post<TrendlineAutoCreateResponse>(
      `/symbols/${encodeURIComponent(code)}/trendlines/auto?${params.toString()}`,
    )
    return res.data
  },

  delete: async (code: string, id: number): Promise<void> => {
    await apiClient.delete(`/symbols/${encodeURIComponent(code)}/trendlines/${id}`)
  },
}
