/**
 * Performance REST API 클라이언트
 *
 * ADR 0072 §6 REST 2 endpoint 정합:
 *   GET /api/performance/summary      — 성과 통계 요약
 *   GET /api/performance/equity-curve — Equity Curve 시계열
 *
 * 룰 5: anthropic·openai import 0건
 * mock 합성 0 — 영속 부재 시 backend 응답 그대로
 */
import apiClient from '../../../lib/axios'
import type { AccountType } from '../../../api/account'

// ──────────────────────────────────────────────
// 기간 단위 (ADR 0072 §2-3 C-1)
// ──────────────────────────────────────────────

export type PerformancePeriod = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR' | 'ALL'

export const PERIOD_LABELS: Record<PerformancePeriod, string> = {
  DAY: '오늘',
  WEEK: '이번 주',
  MONTH: '이번 달',
  YEAR: '올해',
  ALL: '전체',
}

// ──────────────────────────────────────────────
// Response 타입
// ──────────────────────────────────────────────

/** 성과 통계 요약 (ADR 0072 §6-4) */
export interface PerformanceSummaryDto {
  period: PerformancePeriod
  periodStart: string
  periodEnd: string
  totalPnl: number
  mddPct: number
  winRatePct: number
  profitLossRatio: number | null
  tradeCount: number
  computedAt: string
  cacheHit: boolean
}

/** Equity Curve 점 (ADR 0072 §6-5) */
export interface EquityPointDto {
  tradeDate: string
  dailyPnl: number
  cumulativePnl: number
  tradeCount: number
}

/** Equity Curve 시계열 응답 */
export interface EquityCurveDto {
  from: string
  to: string
  points: EquityPointDto[]
}

/** 실현손익 상세 row */
export interface PerformanceTradeDto {
  executionId: number
  orderId: number
  tradeDate: string
  executedAt: string
  code: string
  name: string | null
  mode: 'MOCK' | 'REAL'
  source: 'AUTO' | 'MANUAL'
  executedQty: number
  executedPrice: number
  avgBuyPrice: number
  fee: number
  tax: number
  realizedPnl: number
}

/** 실현손익 상세 응답 */
export interface PerformanceTradeBreakdownDto {
  period: PerformancePeriod
  periodStart: string
  periodEnd: string
  totalRealizedPnl: number
  tradeCount: number
  items: PerformanceTradeDto[]
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const performanceApi = {
  /**
   * GET /api/performance/summary
   * 기간별 성과 통계 요약 (P&L·MDD·승률·평균 손익비·거래 횟수)
   */
  getSummary: async (
    userId: number,
    period: PerformancePeriod,
    periodStart?: string,
    periodEnd?: string,
    mode?: AccountType,
  ): Promise<PerformanceSummaryDto> => {
    const res = await apiClient.get<PerformanceSummaryDto>('/performance/summary', {
      params: { userId, period, periodStart, periodEnd, mode },
    })
    return res.data
  },

  /**
   * GET /api/performance/equity-curve
   * 일별 누적 P&L 시계열 (equity curve chart source)
   */
  getEquityCurve: async (
    userId: number,
    from?: string,
    to?: string,
    mode?: AccountType,
  ): Promise<EquityCurveDto> => {
    const res = await apiClient.get<EquityCurveDto>('/performance/equity-curve', {
      params: { userId, from, to, mode },
    })
    return res.data
  },

  /**
   * GET /api/performance/trades
   * 총 손익의 매도 체결별 상세 근거
   */
  getTrades: async (
    userId: number,
    period: PerformancePeriod,
    periodStart?: string,
    periodEnd?: string,
    mode?: AccountType,
  ): Promise<PerformanceTradeBreakdownDto> => {
    const res = await apiClient.get<PerformanceTradeBreakdownDto>('/performance/trades', {
      params: { userId, period, periodStart, periodEnd, mode },
    })
    return res.data
  },
}
