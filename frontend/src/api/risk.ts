/**
 * Risk REST API 클라이언트 (T-RISK Phase 3 — ADR 0067)
 *
 * GET    /api/risk-config              → RiskConfigDto
 * PUT    /api/risk-config              → RiskConfigDto
 * POST   /api/risk-config/reset        → RiskConfigDto
 * GET    /api/risk-events?from=&to=&page=&size=  → RiskEventPage
 * GET    /api/risk-events/{id}         → RiskEventDto
 *
 * 룰 5: LLM provider import 0건
 *
 * [hotfix] backend flat 구조 정합 — 이전 nested(positionLimit·dll·maxHolding·tradeFrequency)
 * 가정 제거, backend 실제 응답 키와 1:1 매핑.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type RiskVerdict = 'ALLOWED' | 'BLOCKED' | 'AUTO_PAUSED'

// ──────────────────────────────────────────────
// RiskConfig DTO — backend flat 구조 정합
// ──────────────────────────────────────────────

/**
 * GET/PUT /api/risk-config 응답 구조 (flat).
 * backend 실제 응답 키와 1:1 일치.
 *
 * 단위 주의:
 *   maxPositionPercent: 퍼센트 단위 (10 = 10%)  — backend가 percent 단위 저장
 *   dllPct:             퍼센트 단위 (3 = 3%)
 */
export interface RiskConfigDto {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number
  /** 포지션 절대 한도 (원) */
  maxPositionAmount: number
  /** 포지션 비율 한도 (퍼센트, 예: 10 = 10%) */
  maxPositionPercent: number
  /** 일일 손실 한도 절대값 (원). null = 미설정 */
  dllAbs: number | null
  /** 일일 손실 한도 비율 (퍼센트, 예: 3 = 3%). null = 미설정 */
  dllPct: number | null
  /** DLL 활성 여부 */
  isDailyLossLimitActive: boolean
  /** 동시 보유 가능 최대 종목 수 */
  maxConcurrentPositions: number
  /** 분당 최대 거래 횟수 */
  maxTradesPerMinute: number
  /** 일별 최대 거래 횟수 */
  maxTradesPerDay: number
  /** 연속 BLOCKED → 신규 BUY 차단 임계치 (default 10) */
  autoPauseThreshold: number
  updatedAt: string
}

/** PUT /api/risk-config 요청 바디 */
export type RiskConfigUpdateRequest = Omit<RiskConfigDto, 'userId' | 'updatedAt'>

// ──────────────────────────────────────────────
// RiskEvent DTO
// ──────────────────────────────────────────────

export interface RiskEventDto {
  id: string
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  verdict: RiskVerdict
  rule: string
  reason: string
  rawReason?: string
  alertTitle?: string
  alertMessage?: string
  alertFormula?: string
  alertSetting?: string
  alertAction?: string
  signalId?: string
  strategyId?: string
  strategyName?: string
  ts: string
}

export interface RiskEventPage {
  content: RiskEventDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface RiskEventListParams {
  userId?: number
  from?: string
  to?: string
  page?: number
  size?: number
  verdict?: RiskVerdict
}

type RiskEventBackendDto = Omit<Partial<RiskEventDto>, 'id' | 'userId' | 'signalId'> & {
  id: string | number
  userId: string | number
  signalId?: string | number | null
  createdAt?: string
}

type RiskEventBackendPage = Omit<Partial<RiskEventPage>, 'content'> & {
  content?: RiskEventBackendDto[]
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const riskApi = {
  /**
   * GET /api/risk-config?userId=
   * 현재 리스크 설정 조회 (default seed 포함)
   */
  getConfig: async (userId: number): Promise<RiskConfigDto> => {
    const res = await apiClient.get<RiskConfigDto>(`/risk-config?userId=${userId}`)
    return res.data
  },

  /**
   * PUT /api/risk-config?userId=
   * 리스크 설정 갱신
   */
  updateConfig: async (userId: number, req: RiskConfigUpdateRequest): Promise<RiskConfigDto> => {
    const res = await apiClient.put<RiskConfigDto>(`/risk-config?userId=${userId}`, req)
    return res.data
  },

  /**
   * POST /api/risk-config/reset?userId=
   * default seed로 초기화
   */
  resetConfig: async (userId: number): Promise<RiskConfigDto> => {
    const res = await apiClient.post<RiskConfigDto>(`/risk-config/reset?userId=${userId}`)
    return res.data
  },

  /**
   * GET /api/risk-events?userId=
   * 리스크 이벤트 시계열 (페이징)
   */
  listEvents: async (params: RiskEventListParams = {}): Promise<RiskEventPage> => {
    const searchParams = new URLSearchParams()
    if (params.userId !== undefined) searchParams.set('userId', String(params.userId))
    if (params.from) searchParams.set('from', params.from)
    if (params.to) searchParams.set('to', params.to)
    if (params.page !== undefined) searchParams.set('page', String(params.page))
    if (params.size !== undefined) searchParams.set('size', String(params.size))
    if (params.verdict) searchParams.set('verdict', params.verdict)

    const qs = searchParams.toString()
    const res = await apiClient.get<RiskEventBackendPage>(`/risk-events${qs ? `?${qs}` : ''}`)
    return normalizeRiskEventPage(res.data, params.page ?? 0, params.size ?? 50)
  },

  /**
   * GET /api/risk-events/{id}?userId=
   * 리스크 이벤트 단건
   */
  getEvent: async (id: string, userId: number): Promise<RiskEventDto> => {
    const res = await apiClient.get<RiskEventBackendDto>(`/risk-events/${id}?userId=${userId}`)
    return normalizeRiskEvent(res.data)
  },
}

function normalizeRiskEvent(raw: RiskEventBackendDto): RiskEventDto {
  return {
    id: String(raw.id),
    userId: Number(raw.userId),
    verdict: raw.verdict ?? 'BLOCKED',
    rule: raw.rule ?? 'UNKNOWN',
    reason: raw.alertMessage ?? raw.reason ?? '',
    rawReason: raw.rawReason,
    alertTitle: raw.alertTitle,
    alertMessage: raw.alertMessage,
    alertFormula: raw.alertFormula,
    alertSetting: raw.alertSetting,
    alertAction: raw.alertAction,
    signalId: raw.signalId === null || raw.signalId === undefined ? undefined : String(raw.signalId),
    strategyId: raw.strategyId,
    strategyName: raw.strategyName,
    ts: raw.ts ?? raw.createdAt ?? '',
  }
}

function normalizeRiskEventPage(raw: RiskEventBackendPage, page: number, size: number): RiskEventPage {
  const currentPage = raw.page ?? page
  const totalPages = raw.totalPages ?? 0
  return {
    content: (raw.content ?? []).map(normalizeRiskEvent),
    page: currentPage,
    size: raw.size ?? size,
    totalElements: raw.totalElements ?? raw.content?.length ?? 0,
    totalPages,
    last: raw.last ?? (totalPages === 0 || currentPage + 1 >= totalPages),
  }
}
