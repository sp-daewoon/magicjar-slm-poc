/**
 * disclosureApi — 종목별 공시 REST 클라이언트 + DTO
 *
 * ADR 0075 §5 REST endpoint LOCK:
 *   GET /api/symbols/{code}/disclosures?from=&to=&onlyCorrection=&page=&size=
 *
 * ADR 0075 §5-3 DTO LOCK:
 *   SymbolDisclosureListResponse · DisclosureItem
 *
 * STOMP 0 LOCK (ADR 0075 §6 사용자 결정 6-1): polling 60s only.
 * AI provider import 0 (ADR 0075 §11-2).
 */
import apiClient from '../../../lib/axios'

// ──────────────────────────────────────────────
// DTO (ADR 0075 §5-3 LOCK — backend Kotlin DTO 1:1 정합)
// ──────────────────────────────────────────────

export interface DisclosureItem {
  id: number
  rceptNo: string
  corpCode: string
  corpName: string
  reportNm: string
  flrNm: string | null
  rm: string | null
  isCorrection: boolean // UI 강조 — 정정공시 빨간 배지
  url: string // DART 공시 본문 URL
  rceptDt: string // YYYYMMDD raw
  ingestedAt: string // ISO 8601
}

export interface SymbolDisclosureListResponse {
  stockCode: string
  items: DisclosureItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// ──────────────────────────────────────────────
// 요청 파라미터 (ADR 0075 §5-2 LOCK)
// ──────────────────────────────────────────────

export interface SymbolDisclosureParams {
  from?: string // LocalDate YYYY-MM-DD
  to?: string
  page?: number
  size?: number
  onlyCorrection?: boolean // 정정공시 필터 — default false
}

// ──────────────────────────────────────────────
// REST 클라이언트
// ──────────────────────────────────────────────

export const disclosureApi = {
  /**
   * GET /api/symbols/{code}/disclosures
   *
   * @param code  KRX 종목코드 6자리
   * @param params 기간 필터 + 페이지네이션 + 정정공시 필터
   */
  getDisclosures(
    code: string,
    params: SymbolDisclosureParams = {},
  ): Promise<SymbolDisclosureListResponse> {
    return apiClient
      .get<SymbolDisclosureListResponse>(`/symbols/${encodeURIComponent(code)}/disclosures`, {
        params: {
          from: params.from,
          to: params.to,
          page: params.page ?? 0,
          size: params.size ?? 20,
          onlyCorrection: params.onlyCorrection ?? false,
        },
      })
      .then((res) => res.data)
  },
}
