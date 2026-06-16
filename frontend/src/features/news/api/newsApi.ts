/**
 * newsApi — 종목별 뉴스 REST 클라이언트 + DTO
 *
 * ADR 0075 §5 REST endpoint LOCK:
 *   GET /api/symbols/{code}/news?from=&to=&page=&size=
 *
 * ADR 0075 §5-3 DTO LOCK:
 *   SymbolNewsListResponse · NewsItem
 *
 * STOMP 0 LOCK (ADR 0075 §6 사용자 결정 6-1): polling 60s only.
 * AI provider import 0 (ADR 0075 §11-2): sentiment 필드 NULL render only.
 */
import apiClient from '../../../lib/axios'

// ──────────────────────────────────────────────
// DTO (ADR 0075 §5-3 LOCK — backend Kotlin DTO 1:1 정합)
// ──────────────────────────────────────────────

export interface NewsItem {
  id: number
  source: string // "NAVER" | "RSS_HANKYUNG" (Phase 4) | "RSS_GOOGLE" (Phase 4)
  title: string
  bodySummary: string | null
  url: string
  publishedAt: string // ISO 8601 KST
  sentiment: string | null // T-AI 흡수 backlog §62 (NULL 1차)
}

export interface SymbolNewsListResponse {
  stockCode: string
  items: NewsItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// ──────────────────────────────────────────────
// 요청 파라미터 (ADR 0075 §5-2 LOCK)
// ──────────────────────────────────────────────

export interface SymbolNewsParams {
  from?: string // ISO 8601 OffsetDateTime
  to?: string
  page?: number
  size?: number
}

// ──────────────────────────────────────────────
// REST 클라이언트
// ──────────────────────────────────────────────

export const newsApi = {
  /**
   * GET /api/symbols/{code}/news
   *
   * @param code  KRX 종목코드 6자리
   * @param params 기간 필터 + 페이지네이션
   */
  getNews(code: string, params: SymbolNewsParams = {}): Promise<SymbolNewsListResponse> {
    return apiClient
      .get<SymbolNewsListResponse>(`/symbols/${encodeURIComponent(code)}/news`, {
        params: {
          from: params.from,
          to: params.to,
          page: params.page ?? 0,
          size: params.size ?? 20,
        },
      })
      .then((res) => res.data)
  },
}
