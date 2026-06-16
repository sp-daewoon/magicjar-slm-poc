/**
 * OrderCard — 주문 단건 카드 (T-O Phase 3 — ADR 0068 §16 · T-MAN Phase 2 — ADR 0082)
 *
 * 표시: status badge · source badge(자동/수동) · side · code · qty · price · createdAt · mode(MOCK/REAL)
 * 클릭 시 /orders/:id 상세 이동
 *
 * T-MAN B-2-extended: source 필드는 backend 합류 전까지 optional — undefined 시 AUTO default 가정.
 */
import { useNavigate } from 'react-router-dom'
import type { OrderListItemDto } from '../../api/order'
import { OrderStatusBadge } from './OrderStatusBadge'
import { OrderTriggerBadge } from './OrderTriggerBadge'

interface OrderCardProps {
  order: OrderListItemDto
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * 가격 표시 — backend가 String(BigDecimal toString)으로 직렬화함 (정밀도 보존).
 * parseFloat 후 toLocaleString. 한국 주식 정수 가격이라 정밀도 risk 작음.
 */
function formatPrice(price: string | null | undefined): string {
  if (price === null || price === undefined) return '시장가'
  const n = parseFloat(price)
  if (Number.isNaN(n)) return '시장가'
  return n.toLocaleString('ko-KR') + '원'
}

export function OrderCard({ order }: OrderCardProps) {
  const navigate = useNavigate()

  const sideLabel = order.side === 'BUY' ? '매수' : '매도'
  const sideColor = order.side === 'BUY' ? 'text-red-400' : 'text-blue-400'
  const isReconcileReason =
    order.status === 'PENDING_RECONCILE' || order.rejectReason?.startsWith('KIS_RECONCILE_PENDING')
  const reasonColor = isReconcileReason
    ? order.status === 'EXPIRED'
      ? 'text-orange-300'
      : 'text-amber-300'
    : 'text-red-400'

  return (
    <button
      type="button"
      onClick={() => navigate(`/orders/${order.id}`)}
      className="w-full rounded-xl border border-gray-800 bg-gray-900 p-4 text-left transition-colors hover:border-gray-700 hover:bg-gray-800/70 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
    >
      <div className="flex items-start justify-between gap-2">
        {/* 왼쪽: 종목 + 방향 + 모드 + 출처(자동/수동) */}
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <span className="text-base font-semibold text-white">
              {order.code}
              {order.name && <span className="ml-1 text-gray-400">· {order.name}</span>}
            </span>
            <span className={`text-sm font-medium ${sideColor}`}>{sideLabel}</span>
            {order.mode === 'REAL' && (
              <span className="rounded bg-red-900/50 px-1.5 py-0.5 text-xs font-bold text-red-400">
                REAL
              </span>
            )}
            {order.mode === 'MOCK' && (
              <span className="rounded bg-gray-700 px-1.5 py-0.5 text-xs text-gray-400">
                MOCK
              </span>
            )}
            {/* T-MAN B-2-extended source badge (자동 vs 수동) — undefined 시 AUTO default 가정 */}
            {order.source === 'MANUAL' ? (
              <span
                className="rounded bg-amber-900/50 px-1.5 py-0.5 text-xs font-bold text-amber-300"
                aria-label="수동 주문"
                title="사용자 수동 주문 (POST /api/orders/manual)"
              >
                수동
              </span>
            ) : (
              <span
                className="rounded bg-cyan-950/60 px-1.5 py-0.5 text-xs text-cyan-300"
                aria-label="자동매매 주문"
                title="자동매매 시그널 path 주문"
              >
                자동
              </span>
            )}
            {/* ADR 0115 §2-B 발사 출처 badge (전략사인/자동익절/손절/분할익절/수동/외부) — trigger_ref_id hover */}
            <OrderTriggerBadge trigger={order.orderTrigger} triggerRefId={order.triggerRefId} />
          </div>
          <div className="text-sm text-gray-400">
            {order.qty.toLocaleString('ko-KR')}주 &middot; {formatPrice(order.limitPrice)} &middot;{' '}
            {order.priceType === 'MARKET' ? '시장가' : '지정가'}
          </div>
          {order.rejectReason && (
            <div className={`mt-0.5 text-xs ${reasonColor}`}>
              {isReconcileReason ? '확인 사유' : '거절 사유'}: {order.rejectReason}
            </div>
          )}
        </div>

        {/* 오른쪽: 상태 + 시각 */}
        <div className="flex flex-col items-end gap-1">
          <OrderStatusBadge status={order.status} />
          <span className="text-xs text-gray-500">{formatDate(order.requestedAt)}</span>
          {order.completedAt && (
            <span className="text-xs text-gray-600">완료: {formatDate(order.completedAt)}</span>
          )}
        </div>
      </div>
    </button>
  )
}
