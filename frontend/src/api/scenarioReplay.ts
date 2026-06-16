/**
 * Scenario Replay REST API 클라이언트 (T-SIM Phase 3)
 *
 * endpoint:
 *   POST   /api/scenario-replay/runs
 *   GET    /api/scenario-replay/runs?userId=&page=&size=
 *   GET    /api/scenario-replay/runs/{runId}?userId=
 *   GET    /api/scenario-replay/runs/{runId}/events?userId=&page=&size=
 *   DELETE /api/scenario-replay/runs/{runId}?userId=
 *
 * 리플레이는 QA namespace만 사용한다. live order API로 fallback하지 않는다.
 */
import axios from 'axios'
import apiClient from '../lib/axios'

export interface ScenarioPositionSeedDto {
  code: string
  quantity: number
  averagePrice: string
}

export type ScenarioFaultType =
  | 'BALANCE_LOOKUP_RATE_LIMITED'
  | 'BALANCE_LOOKUP_FAILED'
  | 'MARKET_CLOSED'
  | 'DATA_NOT_READY'
  | 'STRATEGY_TIMEOUT'

export interface ScenarioFaultDto {
  at: string
  type: ScenarioFaultType
  message?: string | null
}

export interface ScenarioReplayRunRequestDto {
  userId: string
  strategyId: number
  code: string
  interval: string
  from: string
  to: string
  initialCash: string
  initialPositions: ScenarioPositionSeedDto[]
  faults: ScenarioFaultDto[]
}

export interface ScenarioRunSummaryDto {
  id: number
  userId: string
  strategyId: number
  strategyName: string | null
  code: string
  interval: string
  rangeFrom: string
  rangeTo: string
  initialCash: string
  fillModel: string
  status: 'SUCCESS' | 'ABORTED' | 'FAILED' | (string & {})
  eventCount: number
  finalCash: string | null
  abortReason: string | null
  createdAt: string
}

export interface ScenarioEventDto {
  id: number | null
  sequenceNo: number
  virtualTime: string
  type: string
  severity: 'INFO' | 'WARN' | 'ERROR' | (string & {})
  title: string
  message: string
  strategyId: number | null
  strategyName: string | null
  code: string | null
  payload: Record<string, unknown>
}

export interface ScenarioPositionDto {
  id: number | null
  code: string
  quantity: number
  averagePrice: string
  virtualTime: string
}

export interface ScenarioRunDetailDto {
  run: ScenarioRunSummaryDto
  events: ScenarioEventDto[]
  positions: ScenarioPositionDto[]
}

export interface ScenarioPageDto<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const scenarioReplayApi = {
  create: async (req: ScenarioReplayRunRequestDto): Promise<ScenarioRunDetailDto> => {
    const res = await apiClient.post<ScenarioRunDetailDto>('/scenario-replay/runs', req)
    return res.data
  },

  list: async (
    userId: string,
    page = 0,
    size = 20,
  ): Promise<ScenarioPageDto<ScenarioRunSummaryDto>> => {
    const res = await apiClient.get<ScenarioPageDto<ScenarioRunSummaryDto>>(
      `/scenario-replay/runs?userId=${encodeURIComponent(userId)}&page=${page}&size=${size}`,
    )
    return res.data
  },

  get: async (runId: number, userId: string): Promise<ScenarioRunDetailDto> => {
    const res = await apiClient.get<ScenarioRunDetailDto>(
      `/scenario-replay/runs/${runId}?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },

  listEvents: async (
    runId: number,
    userId: string,
    page = 0,
    size = 100,
  ): Promise<ScenarioPageDto<ScenarioEventDto>> => {
    const res = await apiClient.get<ScenarioPageDto<ScenarioEventDto>>(
      `/scenario-replay/runs/${runId}/events?userId=${encodeURIComponent(userId)}&page=${page}&size=${size}`,
    )
    return res.data
  },

  delete: async (runId: number, userId: string): Promise<void> => {
    await apiClient.delete(`/scenario-replay/runs/${runId}?userId=${encodeURIComponent(userId)}`)
  },
}

export function scenarioReplayErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string; detail?: string; error?: string } | undefined
    return data?.message ?? data?.detail ?? data?.error ?? error.message
  }
  return error instanceof Error ? error.message : '시나리오 리플레이 처리 중 오류가 발생했습니다'
}
