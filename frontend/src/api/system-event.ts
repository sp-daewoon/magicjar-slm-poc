/**
 * SystemEvent REST API 클라이언트 + TS 타입 (P2 Task 3)
 *
 * backend Phase A:
 *   - GET    /api/system-event              → SystemEventPageDto (items + totalCount + unreadCount + page + size)
 *   - PATCH  /api/system-event/{id}/viewed  → SystemEventResponseDto
 *   - PATCH  /api/system-event/{id}/resolved→ SystemEventResponseDto
 *
 * backend DTO 1:1 정합:
 *   api/src/main/kotlin/.../controller/SystemEventDto.kt
 *
 * 사용 패턴:
 *   - REST polling fallback: `useSystemEventList` (Task 5, refetchInterval 30s)
 *   - 사용자 확인/해결: `NotificationCenter`·`SystemEventPage` (Task 6·7) → `markViewed`·`markResolved`
 *
 * STOMP push 1차 path 는 `useSystemEventStompSubscription` (Task 4) 가 담당.
 *
 * spec:  _workspace/specs/2026-05-20-system-event-log-design.md §6·§13
 * plan:  _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 3
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// 타입 (backend SystemEventDto.kt 1:1 매핑)
// ──────────────────────────────────────────────

/** 심각도 — CRITICAL 만 toast 자동 트리거 (useSystemEventStompSubscription) */
export type SystemEventSeverity = 'INFO' | 'WARN' | 'ERROR' | 'CRITICAL'

/** 카테고리 — backend SystemEvent 도메인 enum 정합 */
export type SystemEventCategory =
  | 'RECONCILE'
  | 'WEBSOCKET'
  | 'KAFKA'
  | 'KIS_RATELIMIT'
  | 'DB'
  | 'REDIS'
  | 'OTHER'

/**
 * 시스템 이벤트 단건 — backend SystemEventResponseDto 정합.
 *
 * Instant → string (ISO-8601 UTC, Jackson default 직렬화).
 * Long    → number (axios JSON 파싱).
 */
export interface SystemEvent {
  /** DB ID */
  id: number
  /** 카테고리 */
  category: SystemEventCategory
  /** 심각도 (CRITICAL = toast 트리거) */
  severity: SystemEventSeverity
  /** 발사 component (예: OrderReconciliationWorker) */
  actor: string
  /** 이벤트 코드 — catalog 항목 (예: RECONCILE_FALLBACK_RESOLVED) */
  action: string
  /** 사용자 ID (null = system-wide broadcast) */
  userId: string | null
  /** 동일 사건 묶기 UUID (WS disconnect → reconnect 등) */
  correlationId: string | null
  /** 자유 형식 JSON 문자열 (재발생 사유·retry count 등) */
  detailJson: string
  /** 발생 시각 (ISO-8601 UTC) */
  occurredAt: string
  /** 사용자 확인 시각 (null = 미확인) */
  viewedAt: string | null
  /** 해결 처리 시각 (null = 미해결) */
  resolvedAt: string | null
}

/**
 * 페이지네이션 응답 — backend SystemEventPageDto 정합.
 *
 * `unreadCount` 는 userId 파라미터 있을 때만 사용자별 미확인 count,
 * userId 미지정 시 backend 가 0 반환 (spec §13).
 */
export interface SystemEventPage {
  /** 현재 페이지 이벤트 목록 (최신순) */
  items: SystemEvent[]
  /** 전체 row 개수 (DB count) */
  totalCount: number
  /** 사용자 미확인 row 개수 (벨 badge) — userId 있을 때만 의미 있음 */
  unreadCount: number
  /** 현재 페이지 (0-based) */
  page: number
  /** 페이지 크기 */
  size: number
}

/**
 * GET /api/system-event 쿼리 파라미터.
 *
 * 모두 optional — backend 가 default 처리 (page=0, size=20, unreadOnly=false).
 */
export interface ListParams {
  /** 사용자 ID (string — backend 가 String?) */
  userId?: string
  /** 미확인 (viewed_at IS NULL) 만 — userId 있을 때만 적용 */
  unreadOnly?: boolean
  /** 페이지 (0-based) */
  page?: number
  /** 페이지 크기 (default 20, max 100) */
  size?: number
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const systemEventApi = {
  /**
   * GET /api/system-event
   * 시스템 이벤트 목록 + 필터·페이지네이션 + unread count.
   *
   * STOMP push (useSystemEventStompSubscription) 의 fallback / 새로고침 path.
   */
  list: async (params: ListParams = {}): Promise<SystemEventPage> => {
    const res = await apiClient.get<SystemEventPage>('/system-event', { params })
    return res.data
  },

  /**
   * PATCH /api/system-event/{id}/viewed
   * 사용자 확인 표시 — viewed_at 마킹 → 벨 badge unread count 감소.
   */
  markViewed: async (id: number): Promise<SystemEvent> => {
    const res = await apiClient.patch<SystemEvent>(`/system-event/${id}/viewed`)
    return res.data
  },

  /**
   * PATCH /api/system-event/{id}/resolved
   * 해결 처리 표시 — resolved_at 마킹 (dashboard dismiss / 운영자 acknowledged).
   */
  markResolved: async (id: number): Promise<SystemEvent> => {
    const res = await apiClient.patch<SystemEvent>(`/system-event/${id}/resolved`)
    return res.data
  },
}
