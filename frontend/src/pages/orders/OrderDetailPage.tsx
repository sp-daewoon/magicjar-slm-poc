/**
 * OrderDetailPage — /orders/:id 주문 상세 페이지 (T-O Phase 3 — ADR 0068 §16)
 *
 * 표시 섹션:
 * 1. 주문 기본 정보 (status badge · side · code · qty · price · mode · 시각)
 * 2. ExecutionTable — 분할 체결 시계열 (1:N)
 * 3. OrderAuditTimeline — audit_event 시계열
 * 4. CancelOrderButton — REQUESTED·ACK·PARTIALLY_FILLED 상태만 표시
 *
 * 빈 데이터 graceful (feedback_no_mock_data 정합)
 */
import { useParams, useNavigate } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { useOrder } from '../../api/hooks/useOrder'
import { OrderStatusBadge } from '../../components/order/OrderStatusBadge'
import { OrderTriggerBadge } from '../../components/order/OrderTriggerBadge'
import { ExecutionTable } from '../../components/order/ExecutionTable'
import { OrderAuditTimeline } from '../../components/order/OrderAuditTimeline'
import { CancelOrderButton } from '../../components/order/CancelOrderButton'

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

export function OrderDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const orderId = id ? parseInt(id, 10) : null

  const { data: detail, isLoading, isError, error } = useOrder(orderId, userId)
  // backend OrderDetailResponse 정합: { request, executions, auditEvents }
  // request 안에 평면 필드(code, side, qty, ...) 위치
  const order = detail?.request

  // 로딩
  if (isLoading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6">
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
            <p className="text-sm text-gray-500">주문 상세 로딩 중...</p>
          </div>
        </div>
      </div>
    )
  }

  // 오류
  if (isError || !detail || !order) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6">
        <button
          type="button"
          onClick={() => navigate('/orders')}
          className="mb-4 flex items-center gap-1 text-sm text-gray-400 hover:text-gray-200"
        >
          &larr; 주문 목록으로
        </button>
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 px-4 py-12 text-center">
          <p className="text-sm text-red-400">주문 정보를 불러올 수 없습니다.</p>
          {error && <p className="mt-1 text-xs text-red-600">{error.message}</p>}
        </div>
      </div>
    )
  }

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
    <div className="mx-auto max-w-3xl px-4 py-6">
      {/* 뒤로 가기 */}
      <button
        type="button"
        onClick={() => navigate('/orders')}
        className="mb-4 flex items-center gap-1 text-sm text-gray-400 hover:text-gray-200 focus:outline-none"
      >
        &larr; 주문 목록으로
      </button>

      {/* 주문 기본 정보 카드 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-2xl font-bold text-white">
                {order.code}
                {order.name && <span className="ml-2 text-gray-400">· {order.name}</span>}
              </h1>
              <span className={`text-lg font-semibold ${sideColor}`}>{sideLabel}</span>
              {order.mode === 'REAL' && (
                <span className="rounded bg-red-900/50 px-2 py-0.5 text-xs font-bold text-red-400">
                  REAL
                </span>
              )}
              {order.mode === 'MOCK' && (
                <span className="rounded bg-gray-700 px-2 py-0.5 text-xs text-gray-400">
                  MOCK
                </span>
              )}
            </div>
            <p className="mt-1 text-sm text-gray-400">주문 ID: #{order.id}</p>
          </div>
          <div className="flex flex-col items-end gap-1">
            <OrderStatusBadge status={order.status} />
            <CancelOrderButton
              orderId={order.id}
              userId={userId!}
              status={order.status}
              code={order.code}
              side={order.side}
              quantity={order.qty}
            />
          </div>
        </div>

        {/* 상세 정보 그리드 */}
        <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 border-t border-gray-800 pt-4 text-sm md:grid-cols-3">
          <div>
            <p className="text-xs text-gray-500">수량</p>
            <p className="font-medium text-white">{order.qty.toLocaleString('ko-KR')}주</p>
          </div>
          <div>
            <p className="text-xs text-gray-500">가격 유형</p>
            <p className="font-medium text-white">
              {order.priceType === 'MARKET' ? '시장가' : '지정가'}
            </p>
          </div>
          <div>
            <p className="text-xs text-gray-500">지정가</p>
            <p className="font-medium text-white">{formatPrice(order.limitPrice)}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500">접수 시각</p>
            <p className="text-gray-300">{formatDate(order.requestedAt)}</p>
          </div>
          {order.completedAt && (
            <div>
              <p className="text-xs text-gray-500">완료 시각</p>
              <p className="text-gray-300">{formatDate(order.completedAt)}</p>
            </div>
          )}
          {order.kisOrderNo && (
            <div>
              <p className="text-xs text-gray-500">KIS 주문번호</p>
              <p className="font-mono text-xs text-gray-300">{order.kisOrderNo}</p>
            </div>
          )}
          {/* ADR 0115 §2-B 발사 출처 — 키 사슬 추적성 (불변식 #1·#4) */}
          <div>
            <p className="text-xs text-gray-500">발사 출처</p>
            <div className="mt-0.5">
              <OrderTriggerBadge trigger={order.orderTrigger} triggerRefId={order.triggerRefId} />
            </div>
          </div>
          {order.rejectReason && (
            <div className="col-span-2 md:col-span-3">
              <p className="text-xs text-gray-500">{isReconcileReason ? '확인 사유' : '거절 사유'}</p>
              <p className={`font-medium ${reasonColor}`}>{order.rejectReason}</p>
            </div>
          )}
        </div>
      </div>

      {/* 체결 내역 */}
      <div className="mt-5">
        <h2 className="mb-2 text-sm font-semibold text-gray-300">
          체결 내역 ({detail.executions.length}건)
        </h2>
        <ExecutionTable executions={detail.executions} orderQuantity={order.qty} />
      </div>

      {/* 감사 이벤트 시계열 */}
      <div className="mt-5">
        <h2 className="mb-3 text-sm font-semibold text-gray-300">
          감사 이벤트 ({detail.auditEvents.length}건)
        </h2>
        <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
          <OrderAuditTimeline events={detail.auditEvents} />
        </div>
      </div>
    </div>
  )
}
