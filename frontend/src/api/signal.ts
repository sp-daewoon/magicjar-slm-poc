/**
 * Signal REST API 클라이언트 (T-SIG Phase 3 — ADR 0066)
 *
 * GET /api/signals?userId=&from=&to=&page=&size=   → PageResponse<SignalDto>
 * GET /api/signals/{id}                             → SignalDto
 *
 * DTO: SignalDto · SignalSide · SignalPage
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'
import type { OrderStatus } from './order'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type SignalSide = 'BUY' | 'SELL' | 'HOLD'

// ──────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────

export interface SignalDto {
  id: string
  strategyId: string
  strategyName?: string
  /** 발행 전략명(한글) — backend enrichment */
  strategyNameKo?: string
  /** 발행 전략 매수 조건 DSL — backend enrichment */
  buyCondition?: string
  /** 발행 전략 매도 조건 DSL — backend enrichment */
  sellCondition?: string
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number
  code: string
  /** 종목명 (optional — backend enrichment) */
  codeName?: string
  interval: string
  side: SignalSide
  /** 발생 가격 (문자열 원화) */
  price: string
  priceAtSignal?: string
  /** 시그널 발생 규칙 텍스트 (예: "RSI(14) < 30") */
  rule?: string
  reason?: string
  /** 시그널 발생 시각 (ISO 8601 KST) */
  ts: string
}

type SignalBackendDto = Omit<Partial<SignalDto>, 'id' | 'strategyId' | 'userId'> & {
  id: string | number
  strategyId: string | number
  userId: string | number
  side: SignalSide | string
  priceAtSignal?: string | number
  reason?: string
  createdAt?: string
}

/** GET /api/signals 응답 페이지 래퍼 */
export interface SignalPage {
  content: SignalDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

type SignalBackendPage = Omit<Partial<SignalPage>, 'content'> & {
  content?: SignalBackendDto[]
}

/** GET /api/signals 필터 파라미터 */
export interface SignalListParams {
  userId?: number
  code?: string
  interval?: string
  side?: SignalSide
  /** 발행 전략 ID 필터 */
  strategyId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

// ──────────────────────────────────────────────
// 키 사슬 추적 DTO (ADR 0115 §2-B 불변식 #4 — GET /api/signals/{id}/trace)
// ──────────────────────────────────────────────

/** 발사 출처 enum (order.ts OrderTrigger 와 동일 — signal trace 응답용) */
export type TraceOrderTrigger =
  | 'STRATEGY_SIGNAL'
  | 'AUTO_EXIT'
  | 'STOP_LOSS'
  | 'VI_AUTO_EXIT'
  | 'PARTIAL_TAKE_PROFIT'
  | 'MANUAL'
  | 'EXTERNAL'

/** 체결 단건 (부분체결 타임라인) — backend TracedExecutionDto 정합 */
export interface TracedExecutionDto {
  id: number
  orderId: number
  executedQty: number
  executedPrice: string
  executedAt: string
  fee: string
  tax: string
}

/** 사인이 발사한 주문 + 체결 타임라인 — backend TracedOrderDto 정합 */
export interface TracedOrderDto {
  id: number
  code: string
  side: 'BUY' | 'SELL'
  qty: number
  priceType: 'MARKET' | 'LIMIT'
  limitPrice: string | null
  status: OrderStatus
  mode: 'MOCK' | 'REAL'
  kisOrderNo: string | null
  rejectReason: string | null
  requestedAt: string
  completedAt: string | null
  source: string
  orderTrigger: TraceOrderTrigger | null
  triggerRefId: number | null
  /** 이 주문의 체결 합계 수량 (부분체결 sum) */
  filledQty: number
  executions: TracedExecutionDto[]
}

/** 추적 요약 + 정합 판정 — backend SignalTraceSummaryDto 정합 */
export interface SignalTraceSummaryDto {
  orderCount: number
  /** 주문한 총 수량 (Σ order.qty) */
  orderedQty: number
  /** 실제 체결된 총 수량 (Σ execution.qty) */
  filledQty: number
  /** 현재 그 전략이 보유한 수량 (strategy_position) */
  strategyHeldQty: number
  /** KIS 권위 보유 수량 (position user+code 합산) */
  authorityHeldQty: number
  /** 정합 여부 — strategyHeldQty == authorityHeldQty면 true(정합), 다르면 false(보정 대기) */
  reconciled: boolean
  strategyAvgPrice: string | null
  strategyFilledLots: number | null
  strategyRealizedPnl: string | null
  strategyPositionUpdatedAt: string | null
}

/** 시그널 키 사슬 추적 응답 — backend SignalTraceResponse 정합 (사인→주문→체결→보유+정합) */
export interface SignalTraceDto {
  signal: SignalDto
  orders: TracedOrderDto[]
  summary: SignalTraceSummaryDto
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const signalApi = {
  /**
   * GET /api/signals
   * 사용자 시그널 시계열 (페이징)
   */
  list: async (params: SignalListParams = {}): Promise<SignalPage> => {
    const searchParams = new URLSearchParams()
    const range = withDefaultRange(params)
    if (params.userId !== undefined) searchParams.set('userId', String(params.userId))
    if (params.code) searchParams.set('code', params.code)
    if (params.interval) searchParams.set('interval', params.interval)
    if (params.side) searchParams.set('side', params.side)
    if (params.strategyId) searchParams.set('strategyId', params.strategyId)
    searchParams.set('from', range.from)
    searchParams.set('to', range.to)
    if (params.page !== undefined) searchParams.set('page', String(params.page))
    if (params.size !== undefined) searchParams.set('size', String(params.size))

    const qs = searchParams.toString()
    const res = await apiClient.get<SignalBackendPage>(`/signals${qs ? `?${qs}` : ''}`)
    return normalizeSignalPage(res.data, params.page ?? 0, params.size ?? 50)
  },

  /**
   * GET /api/signals/{id}?userId=
   * 시그널 단건 조회
   */
  get: async (id: string, userId: number): Promise<SignalDto> => {
    const res = await apiClient.get<SignalBackendDto>(`/signals/${id}?userId=${userId}`)
    return normalizeSignal(res.data)
  },

  /**
   * GET /api/signals/{id}/trace?userId=
   * 키 사슬 추적 (ADR 0115 §2-B 불변식 #4) — 사인→주문→체결→보유+정합 드릴다운.
   */
  trace: async (id: string, userId: number): Promise<SignalTraceDto> => {
    const res = await apiClient.get<
      Omit<SignalTraceDto, 'signal'> & { signal: SignalBackendDto }
    >(`/signals/${id}/trace?userId=${userId}`)
    return {
      ...res.data,
      signal: normalizeSignal(res.data.signal),
    }
  },
}

function withDefaultRange(params: SignalListParams): { from: string; to: string } {
  if (params.from && params.to) return { from: params.from, to: params.to }
  // 기본 범위 = 오늘 (로컬 자정 ~ 현재). 명시 from/to 미지정 시 fallback.
  const to = params.to ? new Date(params.to) : new Date()
  let from: Date
  if (params.from) {
    from = new Date(params.from)
  } else {
    from = new Date()
    from.setHours(0, 0, 0, 0)
  }
  return { from: from.toISOString(), to: to.toISOString() }
}

function normalizeSide(side: SignalBackendDto['side']): SignalSide {
  return side === 'BUY' || side === 'SELL' || side === 'HOLD' ? side : 'HOLD'
}

function normalizeSignal(raw: SignalBackendDto): SignalDto {
  const price = raw.price ?? raw.priceAtSignal ?? ''
  return {
    ...raw,
    id: String(raw.id),
    strategyId: String(raw.strategyId),
    strategyName: raw.strategyName ?? undefined,
    strategyNameKo: raw.strategyNameKo ?? undefined,
    buyCondition: raw.buyCondition ?? undefined,
    sellCondition: raw.sellCondition ?? undefined,
    userId: Number(raw.userId),
    code: raw.code ?? '',
    codeName: raw.codeName ?? undefined,
    interval: raw.interval ?? '',
    side: normalizeSide(raw.side),
    price: String(price),
    priceAtSignal: raw.priceAtSignal === undefined ? undefined : String(raw.priceAtSignal),
    rule: raw.rule ?? raw.reason,
    reason: raw.reason,
    ts: raw.ts ?? raw.createdAt ?? '',
  }
}

function normalizeSignalPage(raw: SignalBackendPage, page: number, size: number): SignalPage {
  const totalPages = raw.totalPages ?? 0
  const currentPage = raw.page ?? page
  return {
    content: (raw.content ?? []).map(normalizeSignal),
    page: currentPage,
    size: raw.size ?? size,
    totalElements: raw.totalElements ?? raw.content?.length ?? 0,
    totalPages,
    last: raw.last ?? (totalPages === 0 || currentPage + 1 >= totalPages),
  }
}
