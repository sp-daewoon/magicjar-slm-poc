/**
 * OrderStatusBadge — 주문 상태 색상 배지 (T-O Phase 3 — ADR 0068 §16-2)
 *
 * 색상 매트릭스 LOCK (§16-2):
 *   REQUESTED      → bg-blue-100   text-blue-800   "접수"
 *   ACK            → bg-blue-200   text-blue-900   "KIS 접수"
 *   PENDING_RECONCILE → bg-amber-100 text-amber-800 "KIS 확인 중"
 *   PARTIALLY_FILLED → bg-yellow-100 text-yellow-800 "부분 체결"
 *   FILLED         → bg-green-100  text-green-800  "체결 완료"
 *   REJECTED       → bg-red-100    text-red-800    "거부"
 *   CANCELLED      → bg-gray-100   text-gray-700   "취소"
 *   EXPIRED        → bg-gray-200   text-gray-800   "만료"
 */
import type { OrderStatus } from '../../api/order'

interface OrderStatusBadgeProps {
  status: OrderStatus
  className?: string
}

const STATUS_STYLES: Record<OrderStatus, { bg: string; label: string }> = {
  REQUESTED: { bg: 'bg-blue-100 text-blue-800', label: '접수' },
  ACK: { bg: 'bg-blue-200 text-blue-900', label: 'KIS 접수' },
  PENDING_RECONCILE: { bg: 'bg-amber-100 text-amber-800', label: 'KIS 확인 중' },
  PARTIALLY_FILLED: { bg: 'bg-yellow-100 text-yellow-800', label: '부분 체결' },
  FILLED: { bg: 'bg-green-100 text-green-800', label: '체결 완료' },
  REJECTED: { bg: 'bg-red-100 text-red-800', label: '거부' },
  CANCELLED: { bg: 'bg-gray-100 text-gray-700', label: '취소' },
  EXPIRED: { bg: 'bg-gray-200 text-gray-800', label: '만료' },
}

export function OrderStatusBadge({ status, className = '' }: OrderStatusBadgeProps) {
  const style = STATUS_STYLES[status] ?? { bg: 'bg-gray-100 text-gray-700', label: status }

  return (
    <span
      className={[
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        style.bg,
        className,
      ].join(' ')}
    >
      {style.label}
    </span>
  )
}
