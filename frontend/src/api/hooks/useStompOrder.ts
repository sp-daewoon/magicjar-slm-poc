/**
 * useStompOrder — 주문 실시간 STOMP 구독 (T-O Phase 3 — ADR 0068 §16-3)
 *
 * STOMP topic: /topic/order.user.{userId}
 * StompOrderNotifier fanout 정합 (ADR 0068 §15-4 G-1 단일 토픽 결정)
 *
 * 4 event 색상 토스트 매트릭스 (§16-3 LOCK):
 *   REQUESTED:        파랑 (info)   "주문 접수 — 000660 매수 10주"
 *   FILLED (전체):    녹색 (success) "체결 완료 — 000660 10주 @ 100,000원"
 *   FILLED (부분):    노랑 (warning) "부분 체결 — 000660 5/10주 @ 100,000원"
 *   REJECTED:         빨강 (error)  "주문 거절 — 000660: {reason}"
 *   CANCELLED:        회색 (info)   "주문 취소 — 000660 10주"
 *
 * PARTIALLY_FILLED는 FILLED 흐름에 통합 (msg.partial=true)
 *
 * 분할 체결 토스트 spam 방지: 동일 orderId 후속 update는 1회 토스트 + silent invalidate (§16-6)
 *
 * 사용 예:
 *   const { latestOrderEvent } = useStompOrder({ userId: '42', enabled: true })
 */
import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useStompTopic } from '../../lib/stomp'
import { orderUser } from '../stompDestinations'
import { useToast } from '../../components/ui/ToastProvider'

// ──────────────────────────────────────────────
// 타입 (ADR 0068 §15-4 OrderStompPayload 정합)
// ──────────────────────────────────────────────

export type OrderEventType = 'REQUESTED' | 'FILLED' | 'REJECTED' | 'CANCELLED'

export interface OrderStompPayload {
  event: OrderEventType
  orderId?: number
  code?: string
  side?: 'BUY' | 'SELL'
  quantity?: number
  executedQty?: number
  executedPrice?: number
  partial?: boolean
  reason?: string
  ts?: string
}

export interface UseStompOrderOptions {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  enabled?: boolean
  onOrderEventReceived?: (event: OrderStompPayload) => void
}

export interface UseStompOrderResult {
  /** 가장 최근 수신 주문 이벤트 */
  latestOrderEvent: OrderStompPayload | null
  /** 수신 이벤트 스택 (최근 50건) */
  orderEventQueue: OrderStompPayload[]
  /** 큐 초기화 */
  clearQueue: () => void
}

// ──────────────────────────────────────────────
// 유틸
// ──────────────────────────────────────────────

function formatPrice(price: number | undefined): string {
  if (price === undefined || price === null) return ''
  return price.toLocaleString('ko-KR')
}

function sideLabel(side: 'BUY' | 'SELL' | undefined): string {
  if (side === 'BUY') return '매수'
  if (side === 'SELL') return '매도'
  return ''
}

/** ADR 0068 §16-3 토스트 메시지 + type 조립 */
function buildOrderToast(
  msg: OrderStompPayload,
): { message: string; type: 'info' | 'success' | 'warning' | 'error' } {
  const code = msg.code ?? ''
  switch (msg.event) {
    case 'REQUESTED':
      return {
        message: `주문 접수 — ${code} ${sideLabel(msg.side)} ${msg.quantity ?? 0}주`,
        type: 'info',
      }
    case 'FILLED':
      if (msg.partial) {
        return {
          message: `부분 체결 — ${code} ${msg.executedQty ?? 0}/${msg.quantity ?? 0}주 @ ${formatPrice(msg.executedPrice)}원`,
          type: 'warning',
        }
      }
      return {
        message: `체결 완료 — ${code} ${msg.quantity ?? 0}주 @ ${formatPrice(msg.executedPrice)}원`,
        type: 'success',
      }
    case 'REJECTED':
      return {
        message: `주문 거절 — ${code}: ${msg.reason ?? ''}`,
        type: 'error',
      }
    case 'CANCELLED':
      return {
        message: `주문 취소 — ${code} ${msg.quantity ?? 0}주`,
        type: 'info',
      }
    default:
      return { message: `주문 이벤트: ${msg.event}`, type: 'info' }
  }
}

const MAX_QUEUE = 50

export function useStompOrder({
  userId,
  enabled = true,
  onOrderEventReceived,
}: UseStompOrderOptions): UseStompOrderResult {
  const [latestOrderEvent, setLatestOrderEvent] = useState<OrderStompPayload | null>(null)
  const [orderEventQueue, setOrderEventQueue] = useState<OrderStompPayload[]>([])
  const onEventRef = useRef(onOrderEventReceived)
  onEventRef.current = onOrderEventReceived

  // 분할 체결 토스트 spam 방지: 동일 orderId로 FILLED 토스트를 이미 표시했는지 추적
  const toastedFillIds = useRef<Set<number>>(new Set())

  const { showToast } = useToast()
  const queryClient = useQueryClient()

  const topic = userId !== null && userId !== undefined ? orderUser(userId) : ''

  const handleOrderEvent = useCallback(
    (event: OrderStompPayload) => {
      setLatestOrderEvent(event)
      setOrderEventQueue((prev) => [event, ...prev].slice(0, MAX_QUEUE))

      // STOMP race 방지: ts 기준 정렬은 서버 ts 신뢰 (클라이언트 정렬 X)

      // 분할 체결 spam 방지: 동일 orderId FILLED 토스트는 1회만 (PARTIALLY_FILLED 포함)
      const orderId = event.orderId
      const isFillEvent = event.event === 'FILLED'
      const isPartial = event.partial === true

      if (isFillEvent && isPartial && orderId !== undefined) {
        // 부분 체결 — 이미 해당 orderId 토스트 표시했으면 silent
        if (toastedFillIds.current.has(orderId)) {
          // silent invalidate만
          void queryClient.invalidateQueries({ queryKey: ['orders'] })
          onEventRef.current?.(event)
          return
        }
        toastedFillIds.current.add(orderId)
      }

      if (isFillEvent && !isPartial && orderId !== undefined) {
        // 최종 체결 시 추적 ID 정리 (메모리 누수 방지)
        toastedFillIds.current.delete(orderId)
      }

      // 토스트 표시
      const { message, type } = buildOrderToast(event)
      showToast(message, type)

      // 목록 자동 새로고침 (§16-3 queryClient.invalidateQueries)
      void queryClient.invalidateQueries({ queryKey: ['orders'] })

      // 외부 콜백
      onEventRef.current?.(event)
    },
    [showToast, queryClient],
  )

  useStompTopic<OrderStompPayload>(
    topic,
    handleOrderEvent,
    enabled && userId !== null && userId !== undefined,
  )

  const clearQueue = useCallback(() => {
    setOrderEventQueue([])
  }, [])

  return { latestOrderEvent, orderEventQueue, clearQueue }
}
