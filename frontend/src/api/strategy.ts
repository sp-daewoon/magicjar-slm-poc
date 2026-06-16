/**
 * Strategy REST API 클라이언트
 *
 * T-DSL PR-36 backend-core 7 endpoint LOCK:
 *   POST   /api/strategy/validate
 *   POST   /api/strategy/{id}/evaluate
 *   POST   /api/strategy
 *   GET    /api/strategy?userId=
 *   GET    /api/strategy/{id}
 *   DELETE /api/strategy/{id}
 *   PUT    /api/strategy/{id}/enable
 *
 * T-SIG Phase 3 — ADR 0066 신규 8 endpoint:
 *   POST   /api/strategies               신규
 *   GET    /api/strategies?page=&size=   목록
 *   GET    /api/strategies/{id}          단건
 *   PUT    /api/strategies/{id}          수정
 *   DELETE /api/strategies/{id}          soft 삭제 (status=DELETED)
 *   PUT    /api/strategies/{id}/status   ACTIVE/PAUSED/DISABLED 토글
 *
 * 빌더 저장은 /api/strategies로 정렬해 전략 목록·자동매매 운영 흐름과 같은 저장소를 사용한다.
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

const STRATEGY_EVALUATE_TIMEOUT_MS = 60_000

// ──────────────────────────────────────────────
// DTO 타입 (backend T-DSL StrategyController LOCK)
// ──────────────────────────────────────────────

/** POST /api/strategy/validate 요청 */
export interface ValidateRequest {
  expr: string
}

/** POST /api/strategy/validate 응답 — StrategyComplexityDto */
export interface StrategyComplexityDto {
  astDepth: number
  nodeCount: number
  indicatorCallCount: number
  estimatedEvalTimeMs: number
  complexityClass: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
}

/** POST /api/strategy/{id}/evaluate 요청 */
export interface EvaluateRequest {
  code: string
  interval: string
}

/** POST /api/strategy/{id}/evaluate 응답 */
export type EvaluateReason =
  | string
  | number
  | boolean
  | null
  | Record<string, unknown>
  | unknown[]

export interface EvaluateResponse {
  strategyId: string | number
  signal: 'BUY' | 'SELL' | 'HOLD' | (string & {})
  strength: number | string
  /** 백엔드 reason은 Map<String, Any>; 오래된 mock은 문자열을 보낼 수 있다. */
  reason: EvaluateReason
  evalTimeMs: number | string
}

/** POST /api/strategy 요청 — CreateStrategyRequest (ADR 0089 — userId number) */
export interface CreateStrategyRequest {
  userId?: number
  name: string
  nameKo?: string
  /** DSL entry 표현식 (buy_condition) */
  entry: string
  /** DSL exit 표현식 (sell_condition) — optional */
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
  viActionPolicy?: ViActionPolicy | string | null
}

/** POST /api/strategy 응답 */
export interface CreateStrategyResponse {
  strategyId: string
  complexity: StrategyComplexityDto
}

/** 전략 단건 DTO (ADR 0089 — userId number) */
export interface StrategyDto {
  id: string
  userId: number
  name: string
  nameKo?: string
  entry: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
  viActionPolicy?: ViActionPolicy | string | null
  enabled: boolean
  createdAt: string
  complexity?: StrategyComplexityDto
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const strategyApi = {
  /**
   * POST /api/strategy/validate
   * DSL 표현식 복잡도 검증 → StrategyComplexityDto
   */
  validate: async (req: ValidateRequest): Promise<StrategyComplexityDto> => {
    const res = await apiClient.post<StrategyComplexityDto>('/strategy/validate', req)
    return res.data
  },

  /**
   * POST /api/strategy/{id}/evaluate
   * 단건 종목·인터벌로 DSL 평가 → 신호 결과
   */
  evaluate: async (strategyId: string, req: EvaluateRequest): Promise<EvaluateResponse> => {
    const res = await apiClient.post<EvaluateResponse>(`/strategy/${strategyId}/evaluate`, req, {
      timeout: STRATEGY_EVALUATE_TIMEOUT_MS,
    })
    return res.data
  },

  /**
   * POST /api/strategy
   * 전략 저장 → strategyId 반환
   */
  create: async (req: CreateStrategyRequest): Promise<CreateStrategyResponse> => {
    const res = await apiClient.post<CreateStrategyResponse>('/strategy', req)
    return res.data
  },

  /**
   * GET /api/strategy?userId=
   * 사용자별 전략 목록
   */
  list: async (userId?: number): Promise<StrategyDto[]> => {
    const params = userId !== undefined ? `?userId=${userId}` : ''
    const res = await apiClient.get<StrategyDto[]>(`/strategy${params}`)
    return res.data
  },

  /**
   * GET /api/strategy/{id}
   * 단일 전략 조회
   */
  get: async (id: string): Promise<StrategyDto> => {
    const res = await apiClient.get<StrategyDto>(`/strategy/${id}`)
    return res.data
  },

  /**
   * DELETE /api/strategy/{id}
   * 전략 삭제
   */
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/strategy/${id}`)
  },

  /**
   * PUT /api/strategy/{id}/enable
   * 전략 활성화 토글
   */
  enable: async (id: string): Promise<void> => {
    await apiClient.put(`/strategy/${id}/enable`)
  },
}

// ──────────────────────────────────────────────
// T-SIG Phase 3 — ADR 0066 신규 타입 & API
// ──────────────────────────────────────────────

/** 전략 상태 (ADR 0066 §3-A + BUY_BLOCKED risk 보호 상태) */
export type StrategyStatus = 'ACTIVE' | 'BUY_BLOCKED' | 'PAUSED' | 'DISABLED' | 'DELETED'
export type StrategyOrigin = 'USER_DSL' | 'PAPER_DISCOVERY'
export type ViActionPolicy = 'EXIT_MARKET' | 'ALERT_ONLY' | 'HOLD'

// ──────────────────────────────────────────────
// 분할 매수(scale-in) / 분할 익절(partial-exit) DTO — ADR 0111 (분할매수 R3-a)
// ──────────────────────────────────────────────

/** 추가 진입 트리거 (ADR 0111 §2-1) */
export type ReentryTrigger = 'SIGNAL_REPEAT' | 'PRICE_DROP_PCT'

/** 분할 매수 설정 — null이면 단일 진입(현행 하위호환) */
export interface ScaleInDto {
  /** 분할 회수 N (1~10) */
  totalSlices: number
  /** 회당 비중%(합 100±0.01). null이면 균등 분할 */
  sliceWeights?: Array<number | string> | null
  /** 추가 진입 트리거 (default SIGNAL_REPEAT) */
  reentryTrigger?: ReentryTrigger
  /** PRICE_DROP_PCT 시 직전 회차 체결가 대비 하락폭%(음수) */
  priceDropPct?: number | string | null
  /** 회차 간 최소 간격(초). default 60 */
  minSliceIntervalSec?: number | null
}

/** 분할 익절 단계 — 평단 대비 triggerProfitPct% 도달 시 보유분의 exitWeightPct% 청산 */
export interface PartialExitDto {
  /** 평단 대비 수익률%(양수) */
  triggerProfitPct: number | string
  /** 보유 수량의 청산 비율%((0, 100]) */
  exitWeightPct: number | string
}

/** ADR 0066 전략 상세 DTO (signal tracking 포함, ADR 0089 — userId number) */
export interface SigStrategyDto {
  id: string
  userId: number
  name: string
  nameKo?: string
  origin?: StrategyOrigin
  universe?: string
  tf?: string
  /** 감시 종목 코드 배열 (JSONB) */
  codes: string[]
  /** 종목 코드별 종목명 */
  codeNames?: Record<string, string>
  /** 감시 인터벌 배열 (JSONB) */
  intervals: string[]
  /** buy_condition DSL */
  buyCondition?: string
  /** sell_condition DSL */
  sellCondition?: string
  /** 자동매매 hard stop 손절 비율 (%) */
  stopLossPct?: number | string | null
  /** 보유 포지션 평가 수익률 기준 익절 비율 (%) */
  takeProfitPct?: number | string | null
  /** 잔익절 방어 B안 — 최소 보유 캔들 수. null=비활성(현행) */
  minHoldBars?: number | null
  /** VI 발동 행동 정책 */
  viActionPolicy?: ViActionPolicy
  /** 잔익절 방어 A안 — 저장 경고(비차단). 익절 목표가 왕복 거래비용 근접·미만 시 1건 */
  warnings?: string[]
  complexity?: StrategyComplexityDto
  /** 분할 매수 설정 (ADR 0111 · null이면 단일 진입) */
  scaleIn?: ScaleInDto | null
  /** 분할 익절 단계 (ADR 0111 · null이면 전량 익절) */
  partialExits?: PartialExitDto[] | null
  status: StrategyStatus
  /** 자동매매 시작 가능 여부 */
  activationReady?: boolean
  /** 자동매매 시작 차단 사유 */
  activationBlockedReason?: string | null
  /** 최근 7일 시그널 카운트 */
  recentSignalCount?: number
  createdAt: string
  updatedAt?: string
  enabled?: boolean
  disabledReason?: string
  lastDisabledAt?: string | null
}

type SigStrategyBackendDto = Omit<Partial<SigStrategyDto>, 'id' | 'userId'> & {
  id: string | number
  userId: string | number
  entry?: string
  exit?: string
}

/** ADR 0066 전략 목록 페이지 */
export interface SigStrategyPage {
  content: SigStrategyDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

type SigStrategyBackendPage = Omit<Partial<SigStrategyPage>, 'content'> & {
  content?: SigStrategyBackendDto[]
}

/** POST /api/strategies 요청 */
export interface CreateSigStrategyRequest {
  userId: number
  name: string
  nameKo?: string
  entry?: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
  /** 잔익절 방어 B안 — 최소 보유 캔들 수. null·0이면 비활성(현행) */
  minHoldBars?: number | null
  /** VI 발동 행동 정책 */
  viActionPolicy?: ViActionPolicy
  codes?: string[]
  intervals?: string[]
  buyCondition?: string
  sellCondition?: string
  /** 분할 매수 설정 (ADR 0111 · null이면 단일 진입) */
  scaleIn?: ScaleInDto | null
  /** 분할 익절 단계 (ADR 0111 · null이면 전량 익절) */
  partialExits?: PartialExitDto[] | null
}

/** PUT /api/strategies/{id} 요청 */
export interface UpdateSigStrategyRequest {
  userId: number
  name: string
  nameKo?: string
  entry?: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
  /** 잔익절 방어 B안 — 최소 보유 캔들 수. null·0이면 비활성(현행) */
  minHoldBars?: number | null
  /** VI 발동 행동 정책 */
  viActionPolicy?: ViActionPolicy
  codes?: string[]
  intervals?: string[]
  buyCondition?: string
  sellCondition?: string
  /** 분할 매수 설정 (ADR 0111 · null이면 단일 진입) */
  scaleIn?: ScaleInDto | null
  /** 분할 익절 단계 (ADR 0111 · null이면 전량 익절) */
  partialExits?: PartialExitDto[] | null
}

export const sigStrategyApi = {
  /**
   * GET /api/strategies?userId=&page=&size=
   * 전략 목록 (페이징) — userId: Long (StrategiesController 정합)
   */
  list: async (userId: number, page = 0, size = 20): Promise<SigStrategyPage> => {
    const res = await apiClient.get<SigStrategyBackendPage>(
      `/strategies?userId=${userId}&page=${page}&size=${size}`,
    )
    return normalizeSigStrategyPage(res.data, page, size)
  },

  /**
   * GET /api/strategies/{id}
   * 전략 단건 조회
   */
  get: async (id: string): Promise<SigStrategyDto> => {
    const res = await apiClient.get<SigStrategyBackendDto>(`/strategies/${id}`)
    return normalizeSigStrategy(res.data)
  },

  /**
   * POST /api/strategies
   * 신규 전략 생성
   */
  create: async (req: CreateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.post<SigStrategyBackendDto>('/strategies', toSigStrategyRequestBody(req))
    return normalizeSigStrategy(res.data)
  },

  /**
   * PUT /api/strategies/{id}
   * 전략 수정
   */
  update: async (id: string, req: UpdateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyBackendDto>(`/strategies/${id}`, toSigStrategyRequestBody(req))
    return normalizeSigStrategy(res.data)
  },

  /**
   * DELETE /api/strategies/{id}
   * soft 삭제 (status → DELETED)
   */
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/strategies/${id}`)
  },

  /**
   * PUT /api/strategies/{id}/status
   * 상태 토글 (ACTIVE·PAUSED·DELETED, BUY_BLOCKED·DISABLED는 시스템 보호 상태)
   */
  toggleStatus: async (id: string, status: StrategyStatus): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyBackendDto>(`/strategies/${id}/status`, { status })
    return normalizeSigStrategy(res.data)
  },
}

function toSigStrategyRequestBody(req: CreateSigStrategyRequest | UpdateSigStrategyRequest) {
  const entry = req.entry ?? req.buyCondition
  if (!entry) {
    throw new Error('매수 조건이 없습니다')
  }
  return {
    userId: req.userId,
    name: req.name,
    nameKo: req.nameKo,
    entry,
    exit: req.exit ?? req.sellCondition,
    universe: req.universe ?? 'KOSPI',
    tf: req.tf ?? req.intervals?.[0] ?? '1d',
    stopLossPct: req.stopLossPct ?? null,
    takeProfitPct: req.takeProfitPct ?? null,
    viActionPolicy: req.viActionPolicy,
    // 잔익절 방어 B안 — null·0이면 키 생략(현행 비활성 하위호환)
    ...(req.minHoldBars ? { minHoldBars: req.minHoldBars } : {}),
    codes: req.codes ?? [],
    intervals: req.intervals ?? (req.tf ? [req.tf] : []),
    // ADR 0111 — null(미사용)이면 키 생략(현행 단일 진입 + 전량 익절 하위호환)
    ...(req.scaleIn ? { scaleIn: req.scaleIn } : {}),
    ...(req.partialExits && req.partialExits.length > 0 ? { partialExits: req.partialExits } : {}),
  }
}

function asStringArray(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean)
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return []
    try {
      const parsed = JSON.parse(trimmed) as unknown
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item)).filter(Boolean)
      }
    } catch {
      return [trimmed]
    }
  }
  return []
}

function asCodeNames(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .map(([code, name]) => [code, String(name)])
      .filter(([code, name]) => code.trim() && name.trim()),
  )
}

function normalizeStatus(raw: SigStrategyBackendDto): StrategyStatus {
  if (
    raw.status === 'ACTIVE' ||
    raw.status === 'BUY_BLOCKED' ||
    raw.status === 'PAUSED' ||
    raw.status === 'DISABLED' ||
    raw.status === 'DELETED'
  ) {
    return raw.status
  }
  return raw.enabled === false ? 'DISABLED' : 'ACTIVE'
}

function normalizeOrigin(raw: SigStrategyBackendDto): StrategyOrigin {
  if (raw.origin === 'PAPER_DISCOVERY') return 'PAPER_DISCOVERY'
  const label = `${raw.nameKo ?? ''} ${raw.name ?? ''}`.trim()
  if (label.includes('[모의]') || label.includes('[페이퍼]')) return 'PAPER_DISCOVERY'
  return 'USER_DSL'
}

function normalizeViActionPolicy(value: unknown): ViActionPolicy {
  return value === 'EXIT_MARKET' || value === 'ALERT_ONLY' || value === 'HOLD'
    ? value
    : 'ALERT_ONLY'
}

function normalizeSigStrategy(raw: SigStrategyBackendDto): SigStrategyDto {
  const codes = asStringArray(raw.codes)
  const intervals = asStringArray(raw.intervals)
  const tf = raw.tf ? String(raw.tf) : undefined
  return {
    ...raw,
    id: String(raw.id),
    userId: Number(raw.userId),
    name: raw.name ?? `strategy-${raw.id}`,
    nameKo: raw.nameKo,
    origin: normalizeOrigin(raw),
    universe: raw.universe,
    tf,
    stopLossPct: raw.stopLossPct ?? null,
    takeProfitPct: raw.takeProfitPct ?? null,
    minHoldBars: raw.minHoldBars ?? null,
    viActionPolicy: normalizeViActionPolicy(raw.viActionPolicy),
    warnings: Array.isArray(raw.warnings) ? raw.warnings.map((w) => String(w)).filter(Boolean) : [],
    codes,
    codeNames: asCodeNames(raw.codeNames),
    intervals: intervals.length > 0 ? intervals : tf ? [tf] : [],
    buyCondition: raw.buyCondition ?? raw.entry,
    sellCondition: raw.sellCondition ?? raw.exit,
    complexity: raw.complexity,
    scaleIn: raw.scaleIn ?? null,
    partialExits: raw.partialExits && raw.partialExits.length > 0 ? raw.partialExits : null,
    status: normalizeStatus(raw),
    activationReady: raw.activationReady,
    activationBlockedReason: raw.activationBlockedReason ?? null,
    createdAt: raw.createdAt ?? '',
    updatedAt: raw.updatedAt,
    enabled: raw.enabled,
    disabledReason: raw.disabledReason,
    lastDisabledAt: raw.lastDisabledAt ?? null,
  }
}

function normalizeSigStrategyPage(raw: SigStrategyBackendPage, page: number, size: number): SigStrategyPage {
  const totalPages = raw.totalPages ?? 0
  const currentPage = raw.page ?? page
  return {
    content: (raw.content ?? []).map(normalizeSigStrategy),
    page: currentPage,
    size: raw.size ?? size,
    totalElements: raw.totalElements ?? raw.content?.length ?? 0,
    totalPages,
    last: raw.last ?? (totalPages === 0 || currentPage + 1 >= totalPages),
  }
}
