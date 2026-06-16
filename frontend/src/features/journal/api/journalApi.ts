/**
 * Trading Journal REST API 클라이언트
 *
 * ADR 0072 §6 REST 3 endpoint 정합:
 *   GET  /api/journal/entries         — 실제 체결 list 페이징
 *   GET  /api/journal/entries/{id}    — entry 단건 상세
 *   POST /api/journal/notes           — 사용자 메모 작성
 *
 * 룰 5: anthropic·openai import 0건
 * mock 합성 0 — 영속 부재 시 backend 응답 그대로
 */
import apiClient from '../../../lib/axios'

// ──────────────────────────────────────────────
// 이벤트 타입 enum
// ──────────────────────────────────────────────

export type JournalEventType = 'ORDER_FILLED'

// ──────────────────────────────────────────────
// Response 타입
// ──────────────────────────────────────────────

/** 사용자 메모 */
export interface JournalNoteDto {
  id: number
  noteText: string
  createdAt: string
  updatedAt: string
}

/** 매매일지 entry 단건 */
export interface TradingJournalEntryDto {
  id: number
  eventType: JournalEventType
  instrumentCode: string | null
  instrumentName: string | null
  occurredAt: string
  summary: string
  realizedPnl: string | null
  payload: Record<string, unknown>
  notes: JournalNoteDto[]
}

/** 페이징 응답 (Spring Page<T> 정합) */
export interface JournalPageResponse {
  content: TradingJournalEntryDto[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

// ──────────────────────────────────────────────
// Request 타입
// ──────────────────────────────────────────────

/** GET /api/journal/entries 쿼리 파라미터 */
export interface JournalEntriesParams {
  userId: number
  from?: string
  to?: string
  instrumentCode?: string
  instrumentQuery?: string
  eventType?: JournalEventType
  page?: number
  size?: number
}

/** POST /api/journal/notes 요청 */
export interface JournalNoteRequest {
  userId: number
  journalEntryId: number
  noteText: string
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const journalApi = {
  /**
   * GET /api/journal/entries
   * 매매일지 시계열 조회 (페이징, 최신순)
   */
  listEntries: async (params: JournalEntriesParams): Promise<JournalPageResponse> => {
    const { eventType, ...rest } = params
    const res = await apiClient.get<JournalPageResponse>('/journal/entries', {
      params: {
        ...rest,
        eventTypes: eventType,
      },
    })
    return res.data
  },

  /**
   * GET /api/journal/entries/{id}
   * entry 단건 상세 + 메모 풀세트
   */
  getEntry: async (id: number, userId: number): Promise<TradingJournalEntryDto> => {
    const res = await apiClient.get<TradingJournalEntryDto>(`/journal/entries/${id}`, {
      params: { userId },
    })
    return res.data
  },

  /**
   * POST /api/journal/notes
   * 사용자 메모 작성 (free-form text)
   */
  writeNote: async (req: JournalNoteRequest): Promise<JournalNoteDto> => {
    const res = await apiClient.post<JournalNoteDto>('/journal/notes', req)
    return res.data
  },
}
