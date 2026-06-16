/**
 * 전략별 포지션 REST API 클라이언트 — ADR 0110 전략별 포지션 귀속 (분할매수 R3-a)
 *
 * GET /api/strategies/{id}/position → StrategyPositionDto[]
 *   - 이 전략이 산 물량(수량·평단·진행 회차·실현손익)
 *   - 보유 없으면 빈 배열 (mock 합성 0건 — feedback_no_mock_data 정합)
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

export interface StrategyPositionDto {
  strategyId: number
  userId: string
  code: string
  codeName?: string | null
  /** 이 전략 보유 수량 (분할 누적 - 청산). 0=청산 */
  quantity: number
  /** 이 전략 평단 (분할 가중평균) */
  avgPrice: number
  /** scale-in 진행 회차 (filledLots) */
  filledLots: number
  /** 이 전략 누적 실현 손익 */
  realizedPnl: number
  /** 전량 청산 여부 (quantity=0) */
  isClosed: boolean
  updatedAt: string
}

type StrategyPositionBackendDto = Omit<Partial<StrategyPositionDto>, 'strategyId'> & {
  strategyId?: number | string
  avgPrice?: number | string
  realizedPnl?: number | string
}

function toNumber(value: number | string | null | undefined): number {
  if (value === null || value === undefined) return 0
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : 0
}

function normalize(raw: StrategyPositionBackendDto): StrategyPositionDto {
  return {
    strategyId: toNumber(raw.strategyId),
    userId: String(raw.userId ?? ''),
    code: String(raw.code ?? ''),
    codeName: raw.codeName ?? null,
    quantity: toNumber(raw.quantity),
    avgPrice: toNumber(raw.avgPrice),
    filledLots: toNumber(raw.filledLots),
    realizedPnl: toNumber(raw.realizedPnl),
    isClosed: raw.isClosed ?? raw.quantity === 0,
    updatedAt: String(raw.updatedAt ?? ''),
  }
}

export const strategyPositionApi = {
  /**
   * GET /api/strategies/{id}/position
   * 전략별 포지션(보유 현황) — 보유 없으면 빈 배열
   */
  get: async (strategyId: string): Promise<StrategyPositionDto[]> => {
    const res = await apiClient.get<StrategyPositionBackendDto[]>(`/strategies/${strategyId}/position`)
    return (res.data ?? []).map(normalize)
  },
}
