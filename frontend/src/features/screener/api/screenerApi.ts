/**
 * Screener REST API 클라이언트
 *
 * ADR 0071 §6 REST 3 endpoint 정합:
 *   POST /api/screener/evaluate  — 동기 inline 평가
 *   GET  /api/screener/runs      — screening 이력 (옵션 영속 ON 시)
 *   GET  /api/screener/runs/{id} — screening 단건 상세 (옵션 영속 ON 시)
 *
 * 룰 5: anthropic·openai import 0건
 */
import apiClient from '../../../lib/axios'
import type { CandleInterval } from '../../../api/symbol'

// ──────────────────────────────────────────────
// Request 타입
// ──────────────────────────────────────────────

/** universe 필터 (ADR 0071 §2-2 필터 매트릭스) */
export interface ScreenerFilterDto {
  markets?: string[]
  types?: string[]
  marketCapMin?: number
  avgVolumeMin?: number
}

/** POST /api/screener/evaluate 요청 */
export interface ScreenerEvaluateRequest {
  /** 사용자 ID (multi-tenant 격리) */
  userId: number
  /** Strategy ID (저장된 DSL 참조) */
  strategyId: number
  /** universe 필터 (기본: KOSPI+KOSDAQ active STOCK+ETF) */
  filters?: ScreenerFilterDto
  /** 평가 기준봉 (기본: 1d) */
  interval?: CandleInterval
  /** 랭킹 최대 결과 수 (1~500, default 50) */
  limit?: number
}

// ──────────────────────────────────────────────
// Response 타입 (ADR 0071 §6 DTO 정합)
// ──────────────────────────────────────────────

/** 점수 구성 4 기준 (signal·volume·atr·marketCap) */
export interface ScreenerScoreComponentsDto {
  signal: number
  volume: number
  atr: number
  marketCap: number
}

/** screening 매칭 종목 1건 */
export interface ScreenerMatchDto {
  /** 랭킹 (1-base) */
  rank: number
  /** 종목 코드 */
  instrumentCode: string
  /** 종목명 */
  instrumentName: string
  /** 랭킹 점수 (0~1 normalize) */
  score: number
  /** 점수 구성 (signal/volume/atr/marketCap weighted) */
  scoreComponents: ScreenerScoreComponentsDto
  /** BUY 시그널 reason */
  reason: string
}

/** skip 사유 enum → 한국어 label 매핑 */
export type SkipReasonKey =
  | 'CandleMissing'
  | 'CandleInsufficient'
  | 'EvalTimeout'
  | 'EvalError'

/** screening 결과 + 랭킹 */
export interface ScreenerRunResponse {
  /** cacheKey (debug용) */
  cacheKey: string
  /** universe 사이즈 */
  universeSize: number
  /** 평가 완료 수 */
  evaluatedCount: number
  /** 매칭 결과 수 */
  matchedCount: number
  /** skip 사유별 카운트 (key = SkipReasonKey 또는 한국어 label) */
  skippedReasons: Record<string, number>
  /** 랭킹된 매칭 결과 */
  matches: ScreenerMatchDto[]
  /** 평가 elapsed (ms) */
  elapsedMs: number
  /** 캐시 HIT 여부 */
  cacheHit: boolean
}

// 옵션 영속 ON 시 이력 조회 타입 (Phase 3 scope — 이번 사이클은 evaluate만 필수)

export interface ScreenerRunListResponse {
  content: ScreenerRunSummaryDto[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface ScreenerRunSummaryDto {
  id: number
  strategyId: number
  universeSize: number
  matchedCount: number
  startedAt: string
  finishedAt?: string
  status: 'RUNNING' | 'COMPLETED' | 'ERROR'
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const screenerApi = {
  /**
   * POST /api/screener/evaluate
   * Strategy DSL을 universe에 적용해 매수 후보 + 랭킹 반환 (동기 inline, 최대 ~30s)
   */
  evaluate: async (req: ScreenerEvaluateRequest): Promise<ScreenerRunResponse> => {
    const res = await apiClient.post<ScreenerRunResponse>('/screener/evaluate', req, {
      timeout: 60_000, // inline 평가 최대 60s (ADR 0071 §2-1 기준 ~25s 여유분)
    })
    return res.data
  },

  /**
   * GET /api/screener/runs
   * 옵션 영속(MAGICJAR_SCREENER_PERSIST=true) 환경에서만 활성.
   * 영속 OFF 시 404 반환 (ADR 0071 §6-2).
   */
  listRuns: async (
    userId: number,
    page = 0,
    size = 20,
  ): Promise<ScreenerRunListResponse> => {
    const res = await apiClient.get<ScreenerRunListResponse>('/screener/runs', {
      params: { userId, page, size },
    })
    return res.data
  },

  /**
   * GET /api/screener/runs/{id}
   * 옵션 영속 환경에서만 활성.
   */
  findRunDetail: async (id: number, userId: number): Promise<ScreenerRunResponse> => {
    const res = await apiClient.get<ScreenerRunResponse>(`/screener/runs/${id}`, {
      params: { userId },
    })
    return res.data
  },
}
