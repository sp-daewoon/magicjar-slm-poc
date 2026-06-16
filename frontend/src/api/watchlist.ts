/**
 * Watchlist REST API 클라이언트
 *
 * spec §4-1 REST 시그니처 LOCK 1:1 정합
 * 3 endpoint: GET/POST /api/watchlists · DELETE /api/watchlists/{code}
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Response / Request 타입 정의 (spec §4-1 LOCK)
// ──────────────────────────────────────────────

/** GET /api/watchlists 응답 — 배열 요소 (PR-21b 6 필드 add-only 정합, ADR 0089 userId number) */
export interface WatchlistEntryDto {
  id: number
  userId: number
  code: string
  /** 종목명 (instrument 테이블 join — 미동기화 시 null, PR-20) */
  name?: string | null
  /** 시장 구분 KOSPI·KOSDAQ·UNKNOWN (PR-20) */
  market?: string | null
  priority: number
  createdAt: string // ISO 8601
  /**
   * 상장폐지 여부 (instrument.active=false).
   * true 시 "상장폐지" 작은 라벨 표시 + 사용자가 직접 watchlist에서 제거 결정.
   */
  delisted?: boolean
  // ── PR-21b 추가 필드 (backend WatchlistEntryDto 6 필드 add-only 정합) ──
  /** 종목 유형 (KIS scrt_grp_cls_code 기반: STOCK·ETF·REIT 등) */
  type?: string | null
  /** 시가총액 전일 (억원 단위) */
  marketCapPrev?: string | null
  /** 거래정지 여부 */
  tradingHalt?: boolean | null
  /** 관리종목 여부 */
  managementStock?: boolean | null
  /** 업종 중분류 */
  industryMedium?: string | null
  /** KOSPI200 편입 여부 */
  isKospi200?: boolean | null
}

/** POST /api/watchlists 요청 */
export interface WatchlistAddRequest {
  code: string
  priority?: number
}

/** 공통 에러 응답 포맷 (spec §4-2 LOCK) */
export interface WatchlistErrorResponse {
  code:
    | 'INVALID_TICKER_FORMAT'
    | 'WATCHLIST_DUPLICATE'
    | 'WATCHLIST_NOT_FOUND'
    | 'ONBOARDING_REQUIRED'
    | 'VALIDATION_ERROR'
  message: string
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const watchlistApi = {
  /** GET /api/watchlists — 등록된 관심종목 목록 (priority + createdAt 정렬) */
  list: async (): Promise<WatchlistEntryDto[]> => {
    const res = await apiClient.get<WatchlistEntryDto[]>('/watchlists')
    return res.data
  },

  /** POST /api/watchlists — 관심종목 등록. 201 반환 */
  add: async (req: WatchlistAddRequest): Promise<WatchlistEntryDto> => {
    const res = await apiClient.post<WatchlistEntryDto>('/watchlists', req)
    return res.data
  },

  /** DELETE /api/watchlists/{code} — 관심종목 삭제. 204 반환 */
  remove: async (code: string): Promise<void> => {
    await apiClient.delete(`/watchlists/${encodeURIComponent(code)}`)
  },
}
