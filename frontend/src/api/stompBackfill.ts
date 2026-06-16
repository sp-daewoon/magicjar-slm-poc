/**
 * STOMP Backfill REST API 클라이언트 (P1-3 frontend)
 *
 * GET /api/stomp/events/unread?userId=&since=&channel= → UnreadStompEventCountDto
 * GET /api/stomp/events?userId=&since=&limit=&channel= → StompEventBackfillResponse
 *
 * 목적: STOMP 재연결 시점에 누락된 이벤트를 backend REST로 backfill — 끊김 구간 동안 발생한
 * order/signal/risk 이벤트를 사용자에게 한 번에 토스트/모달로 노출.
 *
 * backend contract LOCK (backend-core dispatch 별도 — frontend는 DTO 정합만):
 *   - userId: number (ADR 0089)
 *   - since: ISO 8601 instant (UTC) — localStorage `stomp:lastSeenAt:${userId}` 사용
 *   - limit: max 100 (frontend 기본 100 — 누락 시 limit reach 시 추가 페이지네이션 X — 매우 드문 케이스)
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────────────────────

/** useStompReconnect 가 toast backfill 대상으로 삼는 채널. system-event 는 별도 알림센터가 담당. */
export type StompBackfillChannel = 'order' | 'signal' | 'risk'

/**
 * STOMP 미수신 이벤트 DTO — backend StompEventDto 1:1.
 *
 * backend 는 payloadJson 문자열을 그대로 반환한다. hook 에서 channel 별로 JSON.parse 후
 * order/signal/risk 토스트를 구성한다.
 */
export interface StompEventDto {
  /** DB sequence ID (재발행 idempotency anchor) */
  id: number | null
  userId: number
  /** STOMP topic 그룹 (order/signal/risk 등) */
  channel: string
  /** 채널별 이벤트 세부 유형 */
  eventType: string
  /** 이벤트 원본 payload JSON 문자열 */
  payloadJson: string
  /** ISO 8601 instant (UTC) — stomp_event.created_at */
  createdAt: string
}

/** GET /api/stomp/events 응답 — backend StompEventBackfillResponse 1:1. */
export interface StompEventBackfillResponse {
  content: StompEventDto[]
  count: number
  since: string
  channel: string | null
}

/** GET /api/stomp/events/unread 응답 — count 단건 */
export interface UnreadStompEventCountDto {
  count: number
  /** 가장 오래된 미수신 이벤트 시각 (count=0 시 null) */
  firstTs: string | null
  /** 가장 최근 미수신 이벤트 시각 (frontend lastSeenAt 갱신 anchor) */
  lastTs: string | null
  since: string
  channel: string | null
}

// ──────────────────────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────────────────────

export const stompBackfillApi = {
  /**
   * GET /api/stomp/events/unread?userId=&since=
   * 사용자 미수신 이벤트 개수 + latest occurredAt anchor 조회.
   *
   * STOMP 재연결 시점에 호출 — count=0 시 backfill skip (빈 요청 0).
   */
  getUnreadCount: async (
    userId: number,
    since: string,
    channel?: StompBackfillChannel,
  ): Promise<UnreadStompEventCountDto> => {
    const res = await apiClient.get<UnreadStompEventCountDto>('/stomp/events/unread', {
      params: { userId, since, channel },
    })
    return res.data
  },

  /**
   * GET /api/stomp/events?userId=&since=&limit=
   * 누락된 이벤트 본체 시계열 조회 (occurredAt ASC).
   *
   * limit=100 default — 100건 초과 누락 시점은 상위 경고(토스트) + 사용자 수동 새로고침 유도.
   */
  list: async (
    userId: number,
    since: string,
    limit = 100,
    channel?: StompBackfillChannel,
  ): Promise<StompEventDto[]> => {
    const res = await apiClient.get<StompEventBackfillResponse>('/stomp/events', {
      params: { userId, since, limit, channel },
    })
    return res.data.content
  },
}
