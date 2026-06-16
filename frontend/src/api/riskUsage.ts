/**
 * Risk Usage REST API 클라이언트 — 리스크 한도 사용량 게이지
 *
 * GET /api/risk/usage?userId={userId}&strategyId={strategyId}
 *   → RiskUsageDto (각 한도의 현재 사용량/남은량)
 *
 * 설정 화면(RiskConfigForm)이 "한도를 얼마나 정할지"라면, 이 API는
 * "그 한도를 지금 얼마나 쓰고 있는지"(사용량 게이지)를 공급한다.
 *
 * 단위 주의:
 *   - 금액(원)은 backend가 정밀도 보존을 위해 string(BigDecimal)로 내려준다.
 *     클라이언트에서 number 로 파싱해 천단위 콤마·게이지 계산에 사용.
 *   - 응답 또는 섹션(position/dailyLoss/concurrent/tradeFrequency)이 null 이면
 *     "준비 중/데이터 없음" 으로 표기 (feedback_no_mock_data — mock 합성 금지).
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// backend wire DTO (금액 string)
// ──────────────────────────────────────────────

interface RiskUsagePositionWire {
  /** 절대 한도 (원) */
  absoluteLimit: string | null
  /** 비율 한도 (퍼센트, 예: 10.0) */
  maxPositionPercent: string | null
  /** 비율 산정 기준 (순자산 등, 원) */
  percentBase: string | null
  /** 비율 한도를 금액으로 환산 (원) */
  percentLimit: string | null
  /** 실제 적용 총한도 = min(절대, 비율환산) (원) */
  totalLimit: string | null
  /** 계좌 전체 현재 노출 (원) */
  totalExposure: string | null
  /** 남은 한도 = totalLimit - totalExposure (원) */
  remaining: string | null
  /** 예수금 (원) */
  orderableCash: string | null
  /** 실제 매수가능 = min(남은한도, 예수금) (원) */
  buyable: string | null
}

interface RiskUsageDailyLossWire {
  enabled: boolean
  /** 한도 절대값 (원). null = 비율 기준 */
  limitAmount: string | null
  /** 한도 비율 (퍼센트). null = 금액 기준 */
  limitPercent: string | null
  /** 오늘 실현손익 (원, 손실이면 음수) */
  todayRealizedPnl: string | null
  /** 남은 손실 여유 (원) */
  remaining: string | null
}

interface RiskUsageConcurrentWire {
  limit: number | null
  current: number | null
  remaining: number | null
}

interface RiskUsageTradeFrequencyWire {
  strategyId: number | null
  perMinuteLimit: number | null
  perMinuteUsed: number | null
  perDayLimit: number | null
  perDayUsed: number | null
  perDayRemaining: number | null
}

interface RiskUsageWire {
  position?: RiskUsagePositionWire | null
  dailyLoss?: RiskUsageDailyLossWire | null
  concurrent?: RiskUsageConcurrentWire | null
  tradeFrequency?: RiskUsageTradeFrequencyWire | null
}

// ──────────────────────────────────────────────
// 클라이언트 DTO (금액 number 파싱)
// ──────────────────────────────────────────────

export interface RiskUsagePosition {
  absoluteLimit: number | null
  maxPositionPercent: number | null
  percentBase: number | null
  percentLimit: number | null
  totalLimit: number | null
  totalExposure: number | null
  remaining: number | null
  orderableCash: number | null
  buyable: number | null
}

export interface RiskUsageDailyLoss {
  enabled: boolean
  limitAmount: number | null
  limitPercent: number | null
  todayRealizedPnl: number | null
  remaining: number | null
}

export interface RiskUsageConcurrent {
  limit: number | null
  current: number | null
  remaining: number | null
}

export interface RiskUsageTradeFrequency {
  strategyId: number | null
  perMinuteLimit: number | null
  perMinuteUsed: number | null
  perDayLimit: number | null
  perDayUsed: number | null
  perDayRemaining: number | null
}

export interface RiskUsageDto {
  position: RiskUsagePosition | null
  dailyLoss: RiskUsageDailyLoss | null
  concurrent: RiskUsageConcurrent | null
  tradeFrequency: RiskUsageTradeFrequency | null
}

// ──────────────────────────────────────────────
// 파싱 유틸 — string|null → number|null (mock 합성 X)
// ──────────────────────────────────────────────

function num(v: string | number | null | undefined): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

function normalizePosition(w: RiskUsagePositionWire | null | undefined): RiskUsagePosition | null {
  if (!w) return null
  return {
    absoluteLimit: num(w.absoluteLimit),
    maxPositionPercent: num(w.maxPositionPercent),
    percentBase: num(w.percentBase),
    percentLimit: num(w.percentLimit),
    totalLimit: num(w.totalLimit),
    totalExposure: num(w.totalExposure),
    remaining: num(w.remaining),
    orderableCash: num(w.orderableCash),
    buyable: num(w.buyable),
  }
}

function normalizeDailyLoss(w: RiskUsageDailyLossWire | null | undefined): RiskUsageDailyLoss | null {
  if (!w) return null
  return {
    enabled: Boolean(w.enabled),
    limitAmount: num(w.limitAmount),
    limitPercent: num(w.limitPercent),
    todayRealizedPnl: num(w.todayRealizedPnl),
    remaining: num(w.remaining),
  }
}

function normalizeConcurrent(w: RiskUsageConcurrentWire | null | undefined): RiskUsageConcurrent | null {
  if (!w) return null
  return {
    limit: num(w.limit),
    current: num(w.current),
    remaining: num(w.remaining),
  }
}

function normalizeTradeFrequency(
  w: RiskUsageTradeFrequencyWire | null | undefined,
): RiskUsageTradeFrequency | null {
  if (!w) return null
  return {
    strategyId: num(w.strategyId),
    perMinuteLimit: num(w.perMinuteLimit),
    perMinuteUsed: num(w.perMinuteUsed),
    perDayLimit: num(w.perDayLimit),
    perDayUsed: num(w.perDayUsed),
    perDayRemaining: num(w.perDayRemaining),
  }
}

function normalize(raw: RiskUsageWire): RiskUsageDto {
  return {
    position: normalizePosition(raw.position),
    dailyLoss: normalizeDailyLoss(raw.dailyLoss),
    concurrent: normalizeConcurrent(raw.concurrent),
    tradeFrequency: normalizeTradeFrequency(raw.tradeFrequency),
  }
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const riskUsageApi = {
  /**
   * GET /api/risk/usage?userId=&strategyId=
   * 각 한도의 현재 사용량 조회.
   * strategyId 는 거래 빈도(D) 섹션 산정용 — 미지정 시 빈도 섹션은 backend가 null.
   */
  getUsage: async (userId: number, strategyId?: number | null): Promise<RiskUsageDto> => {
    const params = new URLSearchParams()
    params.set('userId', String(userId))
    if (strategyId !== null && strategyId !== undefined) {
      params.set('strategyId', String(strategyId))
    }
    const res = await apiClient.get<RiskUsageWire>(`/risk/usage?${params.toString()}`)
    return normalize(res.data ?? {})
  },
}
