/**
 * Market REST + STOMP API client (T-MARKET R2 frontend — W4 R2 dispatch §2-1)
 *
 * Backend 1:1 정합 endpoint (R1 머지 — PR #676 backend-core):
 * - GET  /api/market/index/snapshot                   → IndexSnapshotResponse
 * - GET  /api/market/index/{code}/trend?periods=...   → IndexTrendResponse
 * - GET  /api/market/index/{code}/candles             → IndexCandlesResponse
 * - GET  /api/market/sectors/snapshot                 → SectorsSnapshotResponse
 * - GET  /api/market/sectors/ranking?periods=&rsPeriod= → SectorRankingResponse
 * - GET  /api/users/me/preferences/trend              → TrendPreferenceResponse
 * - PUT  /api/users/me/preferences/trend              → TrendPreferenceResponse
 *
 * STOMP topic (StompMarketNotifier):
 * - /topic/market.index.{code}        → IndexTrendResponse push (1분 cron)
 * - /topic/market.sector.rank         → SectorRankingResponse push (5분 캐시)
 * - /topic/market.user.{userId}.pref  → TrendPreferenceResponse push (다중 탭 sync)
 *
 * AI 호출 0 — 본 모듈은 REST + STOMP 단순 통신만 (룰 5 정합).
 * mock 합성 0 — backend 데이터 없음 시 dataInsufficient=true echo / 빈 list 그대로 전파.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Domain enum 1:1 (backend kr.spdaewoon.magicjar.domain.trend)
// ──────────────────────────────────────────────

export type CrossSignal = 'GOLDEN' | 'DEAD' | 'NEUTRAL'
export type TrendLabel = 'UP' | 'SIDEWAYS' | 'DOWN'

// ──────────────────────────────────────────────
// Response DTO (backend MarketTrendDto · TrendPreferenceDto 1:1)
// ──────────────────────────────────────────────

/** GET /api/market/index/{code}/trend · /snapshot list 의 한 row */
export interface IndexTrendResponse {
  code: string
  /** period → MA 값 map. backend Map<Int, BigDecimal> → JSON `{"5":"2670.12"}` 형식 (BigDecimal 직렬화 string|number). */
  ma: Record<string, number | string>
  crossSignal: CrossSignal
  trendLabel: TrendLabel
  periodsUsed: number[]
  isUserCustom: boolean
  dataInsufficient: boolean
  lastClose: number | string | null
  changeRate: number | string | null
}

export interface IndexSnapshotResponse {
  indexes: IndexTrendResponse[]
}

export interface IndexCandleItem {
  ts: string
  open: number | string
  high: number | string
  low: number | string
  close: number | string
  volume: number
}

export interface IndexCandlesResponse {
  code: string
  interval: '1m' | '1d'
  candles: IndexCandleItem[]
}

export interface SectorSnapshotItem {
  sectorCode: string
  sectorName: string
  ts: string
  price: number | string
  changeRate: number | string
  tradeValue: number | string | null
}

export interface SectorsSnapshotResponse {
  sectors: SectorSnapshotItem[]
}

export interface SectorRankEntryDto {
  sectorCode: string
  sectorName: string
  changeRate: number | string
  tradeValue: number | string | null
  score: number | string
  metric: string
}

export interface SectorRankingResponse {
  todayTop: SectorRankEntryDto[]
  longTop: SectorRankEntryDto[]
  periodsUsed: number[]
  rsPeriod: number
  isUserCustom: boolean
}

// ──────────────────────────────────────────────
// TrendPreference DTO
// ──────────────────────────────────────────────

export interface CrossPairDto {
  short: number
  long: number
}

export interface TrendPreferenceResponse {
  maPeriods: number[]
  rsPeriod: number
  crossPair: CrossPairDto | null
  isUserCustom: boolean
  updatedAt: string
}

export interface TrendPreferenceUpdateRequest {
  maPeriods: number[]
  rsPeriod: number
  crossPair: CrossPairDto | null
}

// ──────────────────────────────────────────────
// 4 default index code (KIS index code — ADR 0100 §2-3)
// ──────────────────────────────────────────────

/** 4 default index strip code → 한국어 라벨. */
export const DEFAULT_INDEX_CODES = ['0001', '1001', '.IXIC', '.SPX'] as const

/** code → 한국어 표시 이름 (frontend 표시용 only — backend payload에는 sectorName 만 존재). */
export const INDEX_LABELS: Record<string, string> = {
  '0001': 'KOSPI',
  '1001': 'KOSDAQ',
  '.IXIC': 'NASDAQ',
  '.SPX': 'S&P 500',
}

// ──────────────────────────────────────────────
// REST helpers (TanStack Query 외부 직접 호출용)
// ──────────────────────────────────────────────

/** GET /api/market/index/snapshot — 4 지수 카드 snapshot. */
export async function fetchIndexSnapshot(): Promise<IndexSnapshotResponse> {
  const { data } = await apiClient.get<IndexSnapshotResponse>('/market/index/snapshot')
  return data
}

/**
 * GET /api/market/index/{code}/trend
 * @param periods 명시 시 user pref override (1~5개·sorted ASC·각 1~252).
 *                생략 시 user pref echo.
 */
export async function fetchIndexTrend(
  code: string,
  periods?: number[],
): Promise<IndexTrendResponse> {
  const params: Record<string, string> = {}
  if (periods && periods.length > 0) {
    params.periods = periods.join(',')
  }
  const { data } = await apiClient.get<IndexTrendResponse>(
    `/market/index/${encodeURIComponent(code)}/trend`,
    { params },
  )
  return data
}

/** GET /api/market/index/{code}/candles — 지수 snapshot 기반 실제 캔들 series. 기본은 KST 일봉. */
export async function fetchIndexCandles(
  code: string,
  opts?: { interval?: '1m' | '1d'; limit?: number },
): Promise<IndexCandlesResponse> {
  const { data } = await apiClient.get<IndexCandlesResponse>(
    `/market/index/${encodeURIComponent(code)}/candles`,
    {
      params: {
        interval: opts?.interval ?? '1d',
        limit: opts?.limit ?? 260,
      },
    },
  )
  return data
}

/** GET /api/market/sectors/snapshot — KRX 섹터 21건. */
export async function fetchSectorsSnapshot(): Promise<SectorsSnapshotResponse> {
  const { data } = await apiClient.get<SectorsSnapshotResponse>('/market/sectors/snapshot')
  return data
}

/** GET /api/market/sectors/ranking — todayTop 5 / longTop 5. */
export async function fetchSectorRanking(opts?: {
  periods?: number[]
  rsPeriod?: number
}): Promise<SectorRankingResponse> {
  const params: Record<string, string | number> = {}
  if (opts?.periods && opts.periods.length > 0) {
    params.periods = opts.periods.join(',')
  }
  if (opts?.rsPeriod !== undefined) {
    params.rsPeriod = opts.rsPeriod
  }
  const { data } = await apiClient.get<SectorRankingResponse>('/market/sectors/ranking', {
    params,
  })
  return data
}

/** GET /api/users/me/preferences/trend — 사용자 추세 설정 조회 (한 번도 PUT 안 했으면 default echo). */
export async function fetchTrendPreference(): Promise<TrendPreferenceResponse> {
  const { data } = await apiClient.get<TrendPreferenceResponse>('/users/me/preferences/trend')
  return data
}

/** PUT /api/users/me/preferences/trend — 사용자 추세 설정 변경. */
export async function updateTrendPreference(
  request: TrendPreferenceUpdateRequest,
): Promise<TrendPreferenceResponse> {
  const { data } = await apiClient.put<TrendPreferenceResponse>(
    '/users/me/preferences/trend',
    request,
  )
  return data
}

// ──────────────────────────────────────────────
// Numeric coercion helpers — backend BigDecimal/Number 양형 대응
// ──────────────────────────────────────────────

/** BigDecimal string or number → number (NaN 시 null). */
export function toNumberOrNull(v: number | string | null | undefined): number | null {
  if (v === null || v === undefined) return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}
