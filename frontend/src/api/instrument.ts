/**
 * 종목 마스터 REST API 클라이언트 (T-IM1)
 *
 * dispatch-spec §5-3 REST 시그니처 LOCK 정합:
 * - GET /api/instruments/search?q=&limit=&market=
 * - GET /api/instruments/{code}
 * - GET /api/instruments?q=&market=&active=&limit=&offset=
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Response 타입 정의 (dispatch-spec §5-7 예시 페이로드 정합)
// ──────────────────────────────────────────────

/** 종목 검색 결과 단건 DTO */
export interface InstrumentSearchResultDto {
  code: string
  name: string
  market: string
  sector: string | null
  score: number
}

/** GET /api/instruments/search 응답 */
export interface InstrumentSearchResponseDto {
  query: string
  results: InstrumentSearchResultDto[]
  totalMatched: number
  limit: number
}

/** GET /api/instruments 응답 단건 */
export interface InstrumentUniverseItemDto {
  code: string
  name: string
  market: string
  type: string
  sector: string | null
  sectorKey: string
  sectorName: string
  industryMedium: string | null
  marketCapPrev: number | null
  tradingHalt: boolean
  managementStock: boolean
  active: boolean
}

/** GET /api/instruments 응답 */
export interface InstrumentUniverseResponseDto {
  items: InstrumentUniverseItemDto[]
  total: number
  limit: number
  offset: number
}

export interface InstrumentUniverseParams {
  q?: string
  market?: string
  active?: boolean
  limit?: number
  offset?: number
}

/** GET /api/instruments/{code} 응답 (PR-21b 83 필드 정합) */
export interface InstrumentDetailDto {
  code: string
  isin: string | null
  name: string
  nameEn: string | null
  market: string
  type: string
  sector: string | null
  /** 업종 중분류 (KIS industry_medium_class_code 정합) */
  industryMedium: string | null
  theme: string | null
  listedAt: string | null
  delistedAt: string | null
  active: boolean
  aliases: { alias: string; source: string }[]
  // ── 매매가드 (PR-21b backend-core — WatchlistEntryDto 정합) ──
  /** 거래정지 여부 (KIS trst_pbnt_yn = Y) */
  tradingHalt: boolean | null
  /** 관리종목 여부 (KIS admn_item_yn = Y) */
  managementStock: boolean | null
  // ── 시장 메타 ──
  /** 시가총액 전일 (억원 단위, KIS stck_bstprc_pcls 정합) */
  marketCapPrev: string | null
  /** KOSPI200 편입 여부 */
  isKospi200: boolean | null
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const instrumentApi = {
  /**
   * GET /api/instruments/search — 자동완성·검색
   * @param q 검색어 (1자 이상)
   * @param limit 결과 수 (default 10, max 20)
   * @param market 시장 필터 (optional: KOSPI·KOSDAQ·KONEX)
   */
  search: async (
    q: string,
    limit = 10,
    market?: string,
  ): Promise<InstrumentSearchResultDto[]> => {
    const params: Record<string, string | number> = { q, limit }
    if (market) params['market'] = market
    const res = await apiClient.get<InstrumentSearchResponseDto>('/instruments/search', { params })
    return res.data.results
  },

  /**
   * GET /api/instruments/{code} — 단일 종목 상세
   * @param code 종목코드 6자리
   */
  getByCode: async (code: string): Promise<InstrumentDetailDto> => {
    const res = await apiClient.get<InstrumentDetailDto>(`/instruments/${encodeURIComponent(code)}`)
    return res.data
  },

  /**
   * GET /api/instruments — 전체 종목 universe.
   */
  list: async (params: InstrumentUniverseParams = {}): Promise<InstrumentUniverseResponseDto> => {
    const res = await apiClient.get<InstrumentUniverseResponseDto>('/instruments', { params })
    return res.data
  },
}
