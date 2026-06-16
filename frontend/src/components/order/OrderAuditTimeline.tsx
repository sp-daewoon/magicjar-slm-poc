/**
 * OrderAuditTimeline — audit_event 시계열 (T-O Phase 3 — ADR 0068 §16)
 *
 * 표시: eventType · createdAt · payload (JSON 요약)
 * 빈 시계열 graceful 안내 (feedback_no_mock_data 정합)
 *
 * 중요 event type 색상:
 *   REAL_GATE_DENIED, KIS_REJECT, KIS_EXCEPTION → 빨강
 *   FULL_FILL                                   → 녹색
 *   PARTIAL_FILL, KIS_RECONCILE_PENDING         → 노랑
 *   REQUESTED, KIS_ACK, KIS_REQUEST             → 파랑
 *   USER_CANCEL_REQUEST, KIS_CANCEL_ACK         → 회색
 */
import type { OrderAuditEventDto, OrderAuditEventType } from '../../api/order'

interface OrderAuditTimelineProps {
  events: OrderAuditEventDto[]
}

const EVENT_STYLES: Partial<Record<OrderAuditEventType, { dot: string; label: string }>> = {
  REQUESTED: { dot: 'bg-blue-500', label: '주문 접수' },
  KIS_REQUEST: { dot: 'bg-blue-400', label: 'KIS 요청' },
  KIS_ACK: { dot: 'bg-blue-300', label: 'KIS 접수' },
  FILL_RECEIVED: { dot: 'bg-yellow-400', label: '체결 통보' },
  PARTIAL_FILL: { dot: 'bg-yellow-400', label: '부분 체결' },
  FULL_FILL: { dot: 'bg-green-500', label: '전체 체결' },
  KIS_REJECT: { dot: 'bg-red-500', label: 'KIS 거부' },
  KIS_EXCEPTION: { dot: 'bg-red-600', label: 'KIS 오류' },
  KIS_INVALID_RESPONSE: { dot: 'bg-red-600', label: 'KIS 응답 오류' },
  REAL_GATE_DENIED: { dot: 'bg-red-700', label: '실거래 게이트 차단' },
  IDEMPOTENCY_SKIPPED: { dot: 'bg-gray-400', label: '중복 스킵' },
  KILLSWITCH_DENIED: { dot: 'bg-red-800', label: '킬스위치 차단' },
  REDIS_DOWN_FALLBACK: { dot: 'bg-red-700', label: 'Redis 장애 차단' },
  INVALID_ORDER_INTENT: { dot: 'bg-red-700', label: '주문 값 오류' },
  KIS_EXECUTION_SYNC_STALE: { dot: 'bg-orange-500', label: '체결 동기화 지연' },
  KIS_TIMEOUT: { dot: 'bg-orange-500', label: 'KIS 타임아웃' },
  KIS_RECONCILE_PENDING: { dot: 'bg-amber-400', label: 'KIS 확인 중' },
  KIS_RECONCILE_RESOLVED: { dot: 'bg-green-400', label: 'KIS 확인 완료' },
  KIS_RECONCILE_TIMEOUT: { dot: 'bg-orange-500', label: 'KIS 확인 만료' },
  ORDER_DB_SYNC_FAILED: { dot: 'bg-red-700', label: '주문 DB 동기화 실패' },
  USER_CANCEL_REQUEST: { dot: 'bg-gray-400', label: '취소 요청' },
  KIS_CANCEL_ACK: { dot: 'bg-gray-300', label: 'KIS 취소 접수' },
  KIS_CANCEL_REJECT: { dot: 'bg-red-400', label: 'KIS 취소 거부' },
  EXECUTION_TIMEOUT: { dot: 'bg-orange-500', label: '체결 타임아웃' },
  EXECUTION_OVERFLOW: { dot: 'bg-red-500', label: '체결 초과' },
}

function getEventStyle(type: OrderAuditEventType) {
  return EVENT_STYLES[type] ?? { dot: 'bg-gray-500', label: type }
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function summarizePayload(payloadJson: string | null): string | null {
  if (!payloadJson) return null
  try {
    const parsed = JSON.parse(payloadJson) as Record<string, unknown>
    const entries = Object.entries(parsed)
      .slice(0, 3)
      .map(([k, v]) => `${k}: ${String(v)}`)
    return entries.join(' · ')
  } catch {
    return payloadJson.length > 80 ? payloadJson.slice(0, 80) + '...' : payloadJson
  }
}

export function OrderAuditTimeline({ events }: OrderAuditTimelineProps) {
  if (events.length === 0) {
    return (
      <div className="rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-6 text-center">
        <p className="text-sm text-gray-500">감사 이벤트가 없습니다.</p>
      </div>
    )
  }

  return (
    <div className="space-y-0">
      {events.map((event, idx) => {
        const style = getEventStyle(event.eventType)
        const summary = summarizePayload(event.payloadJson)
        const isLast = idx === events.length - 1

        return (
          <div key={event.id} className="flex gap-3">
            {/* 타임라인 선 + 점 */}
            <div className="flex flex-col items-center">
              <div className={`mt-1 h-2.5 w-2.5 flex-shrink-0 rounded-full ${style.dot}`} />
              {!isLast && <div className="mt-1 w-px flex-1 bg-gray-800" />}
            </div>

            {/* 내용 */}
            <div className={`pb-4 ${isLast ? 'pb-0' : ''}`}>
              <div className="flex items-baseline gap-2">
                <span className="text-sm font-medium text-gray-200">{style.label}</span>
                <span className="text-xs text-gray-500">{formatDateTime(event.ts)}</span>
              </div>
              {summary && (
                <p className="mt-0.5 text-xs text-gray-500">{summary}</p>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
