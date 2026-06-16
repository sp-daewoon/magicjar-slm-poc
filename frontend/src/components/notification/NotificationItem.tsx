/**
 * NotificationItem — 시스템 이벤트 알림센터 단건 row (P2 Task 6)
 *
 * NotificationCenter 드롭다운 내부에서 사용되는 단일 이벤트 시각화.
 *
 * ### 시각화 규칙
 * - severity 색상 매트릭스 (INFO blue / WARN amber / ERROR orange / CRITICAL red+bold)
 * - viewedAt === null → unread (좌측 인디케이터 + 약간 어두운 배경)
 * - occurredAt → ko-KR locale (KST 자동 변환)
 * - onClick → markViewed(id) trigger
 *
 * ### 디자인 정합
 * - magicJar 다크 테마 (gray-950 base + gray-800 hover)
 * - OrderCard·RiskEventList 와 동일 row 패턴
 *
 * spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.6
 * plan: _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 6 Step 1
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import type { SystemEvent, SystemEventSeverity } from '../../api/system-event'

interface Props {
  event: SystemEvent
  /** 클릭 시 markViewed mutation trigger — id 단일 인자 (useMutation.mutate 직결) */
  onClick: (id: number) => void
}

/**
 * 다크 테마 severity 색상 매트릭스 — backlog Risk·Order 패턴 정합.
 *
 * INFO     blue  — 정보성
 * WARN     amber — 주의
 * ERROR    orange— 비정상 (단일 사건 — 재시도 또는 복구 가능)
 * CRITICAL red+bold — 즉시 사용자 행동 필요 (toast 자동 발사)
 */
const SEVERITY_TEXT_CLASS: Record<SystemEventSeverity, string> = {
  INFO: 'text-blue-300',
  WARN: 'text-amber-300',
  ERROR: 'text-orange-400',
  CRITICAL: 'text-red-400 font-bold',
}

export function NotificationItem({ event, onClick }: Props) {
  const isUnread = event.viewedAt === null
  return (
    <button
      type="button"
      onClick={() => onClick(event.id)}
      className={[
        'flex w-full flex-col items-start gap-1 border-b border-gray-800 p-3 text-left transition-colors',
        'hover:bg-gray-800/60 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-inset',
        isUnread ? 'bg-gray-900/60' : 'bg-transparent',
      ].join(' ')}
      aria-label={`${event.category} ${event.action} ${isUnread ? '(미확인)' : ''}`}
    >
      <div className="flex w-full items-center gap-2">
        {isUnread && (
          <span
            aria-hidden
            className="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-blue-400"
          />
        )}
        <span className={`text-sm ${SEVERITY_TEXT_CLASS[event.severity]}`}>
          [{event.category}] {event.action}
        </span>
      </div>
      <div className="text-xs text-gray-500">
        {new Date(event.occurredAt).toLocaleString('ko-KR')}
      </div>
    </button>
  )
}
