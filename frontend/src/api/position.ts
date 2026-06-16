/**
 * Position REST API 클라이언트 (T-RISK Phase 3 — ADR 0067)
 *
 * GET /api/positions?source=   → PositionDto[]
 * GET /api/positions/{id}      → PositionDto
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type PositionSource = 'MOCK' | 'REAL'

// ──────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────

export interface PositionDto {
  id: string
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number
  code: string
  /** 종목명 */
  codeName?: string
  /** 보유 수량 */
  qty: number
  /** 평균 매수가 (원) */
  avgPrice: number
  /** 현재가 (원, optional — 실시간 enrichment) */
  currentPrice?: number
  /** 포지션 소스 */
  source: PositionSource
  /** 매수 진입 시각 (ISO 8601 KST) */
  entryTs: string
  /** 마지막 업데이트 시각 */
  updatedAt: string
}

export interface PositionListParams {
  userId?: number
  source?: PositionSource
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const positionApi = {
  /**
   * GET /api/positions
   * 현재 보유 포지션 목록
   */
  list: async (params: PositionListParams = {}): Promise<PositionDto[]> => {
    const searchParams = new URLSearchParams()
    if (params.userId !== undefined) searchParams.set('userId', String(params.userId))
    if (params.source) searchParams.set('source', params.source)
    const qs = searchParams.toString()
    const res = await apiClient.get<PositionDto[]>(`/positions${qs ? `?${qs}` : ''}`)
    return res.data
  },

  /**
   * GET /api/positions/{id}
   * 포지션 단건 조회
   */
  get: async (id: string, userId: number): Promise<PositionDto> => {
    const res = await apiClient.get<PositionDto>(`/positions/${id}?userId=${userId}`)
    return res.data
  },
}
