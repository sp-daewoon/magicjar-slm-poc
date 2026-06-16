/**
 * Symbol REST API 클라이언트 + DTO 정의
 *
 * spec §3 REST 시그니처 LOCK 1:1 정합:
 *   GET  /api/symbols/{code}                      → SymbolDto
 *   GET  /api/symbols/{code}/candles?interval=...  → CandlesResponseDto
 *   POST /api/symbols/{code}/realtime/subscribe    → RealtimeSubscribeResponseDto
 *   POST /api/symbols/{code}/realtime/unsubscribe  → RealtimeUnsubscribeResponseDto
 *   POST /api/symbols/{code}/orderbook/subscribe   → OrderbookSubscribeResponseDto
 *   POST /api/symbols/{code}/orderbook/unsubscribe → OrderbookUnsubscribeResponseDto
 *
 * ADR 0019 §2-2 인터벌 확장: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d...
 * ADR 0014 stale 배지: source 필드 + stale boolean
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// 인터벌 타입 (ADR 0062 채택 2026-05-11 — 확장)
//   KIS 직접 제공 5종: 1m·1d·1wk·1mo·1y
//   합산(precomputed): 3m·5m·15m·30m·90m·1h·4h·12h
// 주의: 1wk·1mo·1y는 backend-core PR(PR-31b)과 동시 활성화.
//       합산 interval은 AggregateBackfillRunner 작동 전 빈 배열 반환 → graceful 표시.
// ──────────────────────────────────────────────

export type CandleInterval = '1m' | '3m' | '5m' | '15m' | '30m' | '90m' | '1h' | '4h' | '12h' | '1d' | '1wk' | '1mo' | '1y'

// ──────────────────────────────────────────────
// DTO 정의 (spec §3-2 LOCK)
// ──────────────────────────────────────────────

/**
 * GET /api/symbols/{code} 응답
 * source: 'live' | 'polling' | 'closed' — ADR 0014 Fallback tier
 */
export interface SymbolDto {
  code: string
  name?: string
  market?: string
  industry?: string
  currentPrice: string
  previousClose: string
  change: string
  changeRate: string
  high: string
  low: string
  open: string
  volume: number
  marketCap?: string
  per?: string
  pbr?: string
  eps?: string
  bps?: string
  asOf: string // ISO 8601
  source: 'live' | 'live-nxt' | 'polling' | 'closed' | 'candle-1d'
  stale: boolean
}

/** GET /api/symbols/{code}/candles 단일 캔들 */
export interface CandleDto {
  ts: string // ISO 8601
  open: string
  high: string
  low: string
  close: string
  volume: number
  previousClose?: string | null
  change?: string | null
  changeRate?: string | null
}

/** GET /api/symbols/{code}/candles 응답 */
export interface CandlesResponseDto {
  code: string
  interval: string // backend 응답이 확장 interval을 반환할 수 있으므로 string 유지
  candles: CandleDto[]
  hasMore: boolean
  nextBefore?: string
}

/** POST /api/symbols/{code}/orderbook/subscribe 응답 */
export interface OrderbookSubscribeResponseDto {
  subscribed: boolean
  slot: 'live' | 'queued'
}

/** POST /api/symbols/{code}/orderbook/unsubscribe 응답 */
export interface OrderbookUnsubscribeResponseDto {
  unsubscribed: boolean
}

/** POST /api/symbols/{code}/realtime/subscribe 응답 */
export interface RealtimeSubscribeResponseDto {
  subscribed: boolean
  priceSlot: 'live' | 'queued'
  orderbookSlot: 'live' | 'queued'
}

/** POST /api/symbols/{code}/realtime/unsubscribe 응답 */
export interface RealtimeUnsubscribeResponseDto {
  unsubscribed: boolean
}

// ──────────────────────────────────────────────
// STOMP 실시간 payload 타입 (spec §4-4 STOMP LOCK)
// ──────────────────────────────────────────────

/** /topic/market.tick.{code} */
export interface TickPayload {
  code: string
  price: string
  /** 전일대비 (신규 — KIS PRDY_VRSS, null 가능: polling source) */
  priceChange: string | null
  /** 등락률 % (신규 — KIS PRDY_CTRT, null 가능: polling source) */
  changeRate: string | null
  /**
   * 부호 코드 (신규 — KIS PRDY_VRSS_SIGN, null 가능: polling source)
   * "1"=상한·"2"=상승·"3"=보합·"4"=하한·"5"=하락
   */
  changeSign: string | null
  volume: number
  ts: string
  source: 'live' | 'live-nxt' | 'polling' | 'closed' | 'candle-1d'
  stale: boolean
}

/** /topic/market.candle.minute.{code} */
export interface CandleMinutePayload {
  code: string
  interval: string
  ts: string
  open: string
  high: string
  low: string
  close: string
  volume: number
}

/**
 * /topic/market.candle.{interval}.{code} — boundary closed 실시간 캔들
 * ADR 0064 §2-C: 합산 interval boundary 마감 시점에 fanout
 * BoundaryAggregator → Kafka market.candle.{interval}.closed.v1 → StompCandleForwarder → fanout
 */
export interface CandleClosedPayload {
  code: string
  interval: string
  ts: string
  open: string
  high: string
  low: string
  close: string
  volume: number
}

/** boundary closed STOMP 구독 대상 인터벌 (ADR 0064 §2-C) */
export const BOUNDARY_INTERVALS = ['3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h'] as const
export type BoundaryInterval = (typeof BOUNDARY_INTERVALS)[number]

/** CandleInterval이 boundary closed STOMP 구독 대상인지 확인 */
export function isBoundaryInterval(interval: string): interval is BoundaryInterval {
  return (BOUNDARY_INTERVALS as readonly string[]).includes(interval)
}

/** /topic/market.orderbook.{code} */
export interface OrderbookLevel {
  price: string
  quantity: number
}

export interface OrderbookPayload {
  code: string
  ts: string
  bids: OrderbookLevel[]
  asks: OrderbookLevel[]
  sourceTrId?: string
  totalAskQuantity?: number | null
  totalBidQuantity?: number | null
  overtimeTotalAskQuantity?: number | null
  overtimeTotalBidQuantity?: number | null
  anticipatedPrice?: string | null
  anticipatedQuantity?: number | null
  anticipatedVolume?: number | null
  anticipatedChange?: string | null
  anticipatedChangeSign?: string | null
  anticipatedChangeRate?: string | null
  accumulatedVolume?: number | null
  stockDealClassCode?: string | null
}

// ──────────────────────────────────────────────
// 캔들 갱신 API 응답 DTO
// ──────────────────────────────────────────────

/**
 * POST /api/symbols/{code}/candles/refresh 응답
 */
export interface IntervalRefreshResultDto {
  interval: string
  fetchedCount: number
  insertedCount: number
  latestTs: string | null
  status: string
}

export interface CandleRefreshResponseDto {
  refreshedIntervals: IntervalRefreshResultDto[]
  elapsedMs: number
}

/**
 * POST /api/symbols/{code}/candles/recollect 응답
 */
export interface CandleRecollectResponseDto {
  deletedCount: number
  resetIntervals: string[]
  refreshedIntervals: IntervalRefreshResultDto[]
  rebuiltIntervals: IntervalRefreshResultDto[]
  elapsedMs: number
}

export interface CandleRecollectJobResponseDto {
  jobId: string
  code: string
  intervals: string[]
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string
  topic: string
  submittedAt: string
}

export interface CandleRecollectProgressDto {
  jobId: string
  code: string
  intervals: string[]
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string
  stage: string
  processedDays: number | null
  totalDays: number | null
  deletedCount: number
  insertedCount: number
  rebuiltCount: number
  message: string
  elapsedMs: number | null
  error: string | null
  updatedAt: string
}

export interface CandleRecollectOptions {
  from?: string
  to?: string
  paddingTradingDays?: number
  /**
   * ADR 0120 §6 재수집 모달 — 1m 기준봉(+합산 파생)만 최근 N 거래일로 제한.
   * 일봉 패밀리(1d·1wk·1mo·1y)는 전기간 강제 재수집 유지. from/to(리서치 range) 지정 시 백엔드가 무시.
   */
  minuteRecentDays?: number
}

// ──────────────────────────────────────────────
// 캔들 수집 건강도 (readiness P1 — candle-health)
//
// GET /api/symbols/{code}/candle-health
//   → "왜 캔들 수집이 안 됐는지"를 종목별 1줄로 노출.
//
// orchestrator LOCK 계약 (market-data 병렬 구현 중):
//   { code, interval, lastCandleAt, staleSeconds, status, reason, reasonDetail, checkedAt }
//
// 방어적 파싱 의무: reason/status가 enum 밖 값이어도 throw하지 않고 원문 보존.
//   알 수 없는 reason은 UNKNOWN으로 분류하되 reasonDetail 원문을 그대로 표시한다.
//   API 404/에러는 호출부(hook)에서 "수집 상태 확인 불가"로 graceful 처리 — 가짜 FRESH 금지.
// ──────────────────────────────────────────────

/** candle-health status — 계약 LOCK 3종 (그 외 값은 원문 보존) */
export type CandleHealthStatus = 'FRESH' | 'STALE' | 'NO_DATA' | (string & {})

/** candle-health reason — 계약 LOCK 8종 (그 외 값은 UNKNOWN 취급 + 원문 표시) */
export type CandleHealthReason =
  | 'OK_REALTIME'
  | 'MARKET_CLOSED'
  | 'WS_NOT_SUBSCRIBED'
  | 'NO_TICK'
  | 'INSERT_FAILED'
  | 'BACKFILL_FAILED'
  | 'BACKFILL_PERMANENT_GAP'
  | 'UNKNOWN'
  | (string & {})

/** GET /api/symbols/{code}/candle-health 응답 */
export interface CandleHealthDto {
  code: string
  interval: string
  lastCandleAt: string | null
  staleSeconds: number
  status: CandleHealthStatus
  reason: CandleHealthReason
  /** 한국어 1줄 설명 (백엔드 권위 — 모르는 reason도 이 값을 그대로 표시) */
  reasonDetail: string
  checkedAt: string
}

/** GET /api/symbols/candle-health?codes=A,B 배치 응답 */
export interface CandleHealthBatchDto {
  items: CandleHealthDto[]
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const symbolApi = {
  /**
   * GET /api/symbols/{code}
   * 종목 메타 + 현재가 (fresh quote:last → KIS J/KRX snapshot → 1d candle fallback)
   */
  getSymbol: async (code: string): Promise<SymbolDto> => {
    const res = await apiClient.get<SymbolDto>(`/symbols/${encodeURIComponent(code)}`)
    return res.data
  },

  /**
   * GET /api/symbols/{code}/candle-health
   * 캔들 수집 건강도 — stale 시 "왜 안 됐는지" 1줄 사유.
   * 404/에러는 throw → hook에서 "수집 상태 확인 불가"로 graceful 표시.
   */
  getCandleHealth: async (code: string): Promise<CandleHealthDto> => {
    const res = await apiClient.get<CandleHealthDto>(
      `/symbols/${encodeURIComponent(code)}/candle-health`,
    )
    return res.data
  },

  /**
   * GET /api/symbols/candle-health?codes=A,B
   * 배치 조회 (watchlist 등 다종목 인디케이터용).
   */
  getCandleHealthBatch: async (codes: string[]): Promise<CandleHealthBatchDto> => {
    const params = new URLSearchParams({ codes: codes.join(',') })
    const res = await apiClient.get<CandleHealthBatchDto>(
      `/symbols/candle-health?${params.toString()}`,
    )
    return res.data
  },

  /**
   * GET /api/symbols/{code}/candles
   * 차트 series — DB candle + Redis current·today 합류
   *
   * @param code - 종목 코드
   * @param interval - 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)
   * @param before - 페이지네이션 cursor (ISO 8601, 미지정 시 최신)
   * @param limit - 최대 캔들 수 (backend default 600, max 5000)
   */
  getCandles: async (
    code: string,
    interval: CandleInterval,
    before?: string,
    limit = 200,
  ): Promise<CandlesResponseDto> => {
    const params = new URLSearchParams({ interval, limit: String(limit) })
    if (before) params.set('before', before)
    const res = await apiClient.get<CandlesResponseDto>(
      `/symbols/${encodeURIComponent(code)}/candles?${params.toString()}`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/realtime/subscribe
   * 종목 상세 실시간 수요 통보: KIS WS 현재가 + 호가.
   * 페이지 mount 시 호출
   */
  subscribeRealtime: async (code: string): Promise<RealtimeSubscribeResponseDto> => {
    const res = await apiClient.post<RealtimeSubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/realtime/subscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/realtime/unsubscribe
   * 종목 상세 실시간 수요 회수.
   * 페이지 unmount 시 호출 + sendBeacon fallback
   */
  unsubscribeRealtime: async (code: string): Promise<RealtimeUnsubscribeResponseDto> => {
    const res = await apiClient.post<RealtimeUnsubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/realtime/unsubscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/orderbook/subscribe
   * 기존 호환 alias.
   */
  subscribeOrderbook: async (code: string): Promise<OrderbookSubscribeResponseDto> => {
    const res = await apiClient.post<OrderbookSubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/orderbook/subscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/orderbook/unsubscribe
   * 기존 호환 alias.
   */
  unsubscribeOrderbook: async (code: string): Promise<OrderbookUnsubscribeResponseDto> => {
    const res = await apiClient.post<OrderbookUnsubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/orderbook/unsubscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/candles/refresh?intervals=1d,1wk,1mo,1y
   * KIS에서 최신 캔들을 재수집 후 DB에 적재.
   * intervals 미지정 시 backend default (1d·1wk·1mo·1y).
   */
  refreshCandles: async (
    code: string,
    intervals?: string[],
  ): Promise<CandleRefreshResponseDto> => {
    const params = intervals?.length
      ? new URLSearchParams({ intervals: intervals.join(',') })
      : undefined
    const url = params
      ? `/symbols/${encodeURIComponent(code)}/candles/refresh?${params.toString()}`
      : `/symbols/${encodeURIComponent(code)}/candles/refresh`
    const res = await apiClient.post<CandleRefreshResponseDto>(url)
    return res.data
  },

  /**
   * POST /api/symbols/{code}/candles/recollect?intervals=3m
   * 기존 캔들을 삭제한 뒤 KIS에서 재수집한다. 합산봉은 1m 재수집 후 서버에서 재생성한다.
   */
  recollectCandles: async (
    code: string,
    intervals: string[],
    options?: CandleRecollectOptions,
  ): Promise<CandleRecollectResponseDto> => {
    const params = new URLSearchParams({ intervals: intervals.join(',') })
    if (options?.from) params.set('from', options.from)
    if (options?.to) params.set('to', options.to)
    if (options?.paddingTradingDays != null) params.set('paddingTradingDays', String(options.paddingTradingDays))
    if (options?.minuteRecentDays != null) params.set('minuteRecentDays', String(options.minuteRecentDays))
    const res = await apiClient.post<CandleRecollectResponseDto>(
      `/symbols/${encodeURIComponent(code)}/candles/recollect?${params.toString()}`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/candles/recollect/jobs?intervals=3m
   * 긴 재수집을 비동기 job으로 시작하고 STOMP topic을 반환한다.
   */
  startCandleRecollectJob: async (
    code: string,
    intervals: string[],
    options?: CandleRecollectOptions,
  ): Promise<CandleRecollectJobResponseDto> => {
    const params = new URLSearchParams({ intervals: intervals.join(',') })
    if (options?.from) params.set('from', options.from)
    if (options?.to) params.set('to', options.to)
    if (options?.paddingTradingDays != null) params.set('paddingTradingDays', String(options.paddingTradingDays))
    if (options?.minuteRecentDays != null) params.set('minuteRecentDays', String(options.minuteRecentDays))
    const res = await apiClient.post<CandleRecollectJobResponseDto>(
      `/symbols/${encodeURIComponent(code)}/candles/recollect/jobs?${params.toString()}`,
    )
    return res.data
  },

  /**
   * GET /api/symbols/{code}/candles/recollect/jobs/{jobId}
   * STOMP progress 누락 보완용 poll fallback.
   */
  getCandleRecollectJob: async (
    code: string,
    jobId: string,
  ): Promise<CandleRecollectProgressDto> => {
    const res = await apiClient.get<CandleRecollectProgressDto>(
      `/symbols/${encodeURIComponent(code)}/candles/recollect/jobs/${encodeURIComponent(jobId)}`,
    )
    return res.data
  },
}
