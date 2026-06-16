/**
 * Backtest REST API 클라이언트 (T-BT R3 — ADR 0061 §4 C-1 정합)
 *
 * 5 endpoint:
 *   POST   /api/backtests?userId=         — 실행 + 영속 (HTTP 201)
 *   GET    /api/backtests?userId=&page=&size=  — 목록 (페이징)
 *   GET    /api/backtests/{id}?userId=    — 단건 메타 + KPI
 *   GET    /api/backtests/{id}/result?userId=&tradePage=&tradeSize=&posPage=&posSize=
 *   DELETE /api/backtests/{id}?userId=   — 삭제 (HTTP 204)
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────────────────────
// DTO 타입 (BacktestPersistenceDto.kt + BacktestDto.kt 정합)
// ──────────────────────────────────────────────────────────────

/** POST /api/backtests 요청 (BacktestRunRequest 정합) */
export interface BacktestRunRequest {
  strategyId: number
  universe: string[]
  interval?: string
  from: string
  to: string
  initialCapital: string // BigDecimal — string으로 전송
  taxModel?: string
  commissionModel?: string
  slippageModel?: string
  priceAdjustmentMode?: string
}

/** 성과 지표 DTO (BacktestPerformanceDto 정합) */
export interface BacktestPerformanceDto {
  totalReturn: string
  cagr: string
  maxDrawdown: string
  sharpeRatio: string | null
  winRate: string
  totalTrades: number
  winningTrades: number
  losingTrades: number
  avgWin: string
  avgLoss: string
  profitFactor: string | null
  tradingFrequency: string
}

/** 매매 기록 DTO (BacktestTradeDto 정합 — POST 응답 내 포함) */
export interface BacktestTradeDto {
  code: string
  side: 'BUY' | 'SELL'
  quantity: number
  executedPrice: string
  rawPrice: string
  executedAt: string
  commission: string
  tax: string
  slippage: string
}

/** 포지션 DTO (BacktestPositionDto 정합) */
export interface BacktestPositionDto {
  code: string
  quantity: number
  avgEntryPrice: string
  openedAt: string
  closedAt: string | null
  realizedPnl: string
}

/** POST /api/backtests 응답 (BacktestRunPersistResponse 정합) */
export interface BacktestRunPersistResponse {
  runId: number
  status: 'SUCCESS' | 'ABORTED'
  trades: BacktestTradeDto[]
  positions: BacktestPositionDto[]
  finalCapital: string
  performance: BacktestPerformanceDto | null
  abortReason: string | null
  durationMs: number
}

/** 백테스트 목록 항목 DTO (BacktestRunSummaryDto 정합) */
export interface BacktestRunSummaryDto {
  id: number
  code: string
  interval: string
  rangeFrom: string
  rangeTo: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  completedAt: string | null
  totalReturn: string | null
  winRate: string | null
  mdd: string | null
  sharpe: string | null
  tradeCount: number | null
  createdAt: string
}

/** 백테스트 단건 상세 DTO (BacktestRunDetailDto 정합) */
export interface BacktestRunDetailDto {
  id: number
  code: string
  interval: string
  ruleDsl: string
  rangeFrom: string
  rangeTo: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  startedAt: string | null
  completedAt: string | null
  totalReturn: string | null
  winRate: string | null
  mdd: string | null
  sharpe: string | null
  tradeCount: number | null
  positionCount: number | null
  createdAt: string
}

/** 거래 기록 영속 DTO (BacktestTradeResultDto 정합 — GET result 응답) */
export interface BacktestTradeResultDto {
  id: number
  ts: string
  side: 'BUY' | 'SELL'
  price: string
  quantity: number
  fee: string | null
  ruleSignal: string | null
}

/** 포지션 영속 DTO (BacktestPositionResultDto 정합) */
export interface BacktestPositionResultDto {
  id: number
  openTs: string
  closeTs: string | null
  entryPrice: string
  exitPrice: string | null
  quantity: number
  pnl: string | null
  returnPct: string | null
}

/** 공용 페이지 래퍼 (BacktestPageDto<T> 정합) */
export interface BacktestPageDto<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** GET /api/backtests 응답 */
export type BacktestListResponse = BacktestPageDto<BacktestRunSummaryDto>

/** GET /api/backtests/{id}/result 응답 (BacktestResultPageDto 정합) */
export interface BacktestResultPageDto {
  trades: BacktestPageDto<BacktestTradeResultDto>
  positions: BacktestPageDto<BacktestPositionResultDto>
}

// ──────────────────────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────────────────────

export const backtestApi = {
  /**
   * POST /api/backtests?userId=
   * 백테스트 실행 + 영속 → runId + KPI
   */
  create: async (userId: number, req: BacktestRunRequest): Promise<BacktestRunPersistResponse> => {
    const res = await apiClient.post<BacktestRunPersistResponse>(
      `/backtests?userId=${userId}`,
      req,
    )
    return res.data
  },

  /**
   * GET /api/backtests?userId=&page=&size=
   * 사용자 백테스트 목록 (페이징)
   */
  list: async (
    userId: number,
    page = 0,
    size = 20,
  ): Promise<BacktestListResponse> => {
    const res = await apiClient.get<BacktestListResponse>(
      `/backtests?userId=${userId}&page=${page}&size=${size}`,
    )
    return res.data
  },

  /**
   * GET /api/backtests/{id}?userId=
   * 단건 메타 + KPI 조회
   */
  getDetail: async (id: number, userId: number): Promise<BacktestRunDetailDto> => {
    const res = await apiClient.get<BacktestRunDetailDto>(
      `/backtests/${id}?userId=${userId}`,
    )
    return res.data
  },

  /**
   * GET /api/backtests/{id}/result?userId=&tradePage=&tradeSize=&posPage=&posSize=
   * 거래·포지션 시계열 (페이징)
   */
  getResult: async (
    id: number,
    userId: number,
    tradePage = 0,
    tradeSize = 100,
    posPage = 0,
    posSize = 50,
  ): Promise<BacktestResultPageDto> => {
    const res = await apiClient.get<BacktestResultPageDto>(
      `/backtests/${id}/result?userId=${userId}&tradePage=${tradePage}&tradeSize=${tradeSize}&posPage=${posPage}&posSize=${posSize}`,
    )
    return res.data
  },

  /**
   * DELETE /api/backtests/{id}?userId=
   * 백테스트 삭제 (CASCADE) → HTTP 204
   */
  delete: async (id: number, userId: number): Promise<void> => {
    await apiClient.delete(`/backtests/${id}?userId=${userId}`)
  },
}
