/**
 * CancelOrderButton — 주문 취소 버튼 + 확인 모달 (T-O Phase 3 — ADR 0068 §16)
 *
 * - REQUESTED·ACK·PARTIALLY_FILLED 상태만 활성화 (그 외 비활성)
 * - 확인 모달: "정말 취소하시겠습니까? {code} {side} {qty}주"
 * - 이중 클릭 방지: mutation isPending 시 버튼 disabled
 * - 취소 성공 시 toast (success)
 * - 취소 실패 시 toast (error)
 *
 * §16-6 오류 risk 정합: 취소 버튼 의도치 않은 클릭 → 확인 모달 의무
 */
import { useState } from 'react'
import type { OrderStatus, OrderSide } from '../../api/order'
import { useCancelOrder } from '../../api/hooks/useCancelOrder'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useToast } from '../ui/ToastProvider'

interface CancelOrderButtonProps {
  orderId: number
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  status: OrderStatus
  code: string
  side: OrderSide
  quantity: number
}

const CANCELABLE_STATUSES: OrderStatus[] = ['REQUESTED', 'ACK', 'PARTIALLY_FILLED']

function sideLabel(side: OrderSide): string {
  return side === 'BUY' ? '매수' : '매도'
}

export function CancelOrderButton({
  orderId,
  userId,
  status,
  code,
  side,
  quantity,
}: CancelOrderButtonProps) {
  const [showModal, setShowModal] = useState(false)
  const { mutate, isPending } = useCancelOrder()
  const { showToast } = useToast()

  const canCancel = CANCELABLE_STATUSES.includes(status)

  // ESC 닫기 + body scroll lock (확인 모달 표시 중에만 · 취소 요청 진행 중엔 닫기 잠금 — 기존 닫기 버튼 disabled 의미 보존)
  useModalBehavior({
    open: showModal,
    onClose: () => {
      if (!isPending) setShowModal(false)
    },
  })

  function handleCancelConfirm() {
    mutate(
      { id: orderId, userId },
      {
        onSuccess: () => {
          showToast(`주문 취소 완료 — ${code} ${sideLabel(side)} ${quantity}주`, 'success')
          setShowModal(false)
        },
        onError: (err) => {
          showToast(`취소 실패: ${err.message}`, 'error')
          setShowModal(false)
        },
      },
    )
  }

  if (!canCancel) {
    return null
  }

  return (
    <>
      {/* 취소 버튼 */}
      <button
        type="button"
        onClick={() => setShowModal(true)}
        disabled={isPending}
        className="rounded-lg border border-red-800 px-3 py-1.5 text-sm font-medium text-red-400 transition-colors hover:bg-red-900/30 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-red-400 focus:ring-offset-1 focus:ring-offset-gray-950"
      >
        주문 취소
      </button>

      {/* 확인 모달 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="w-full max-w-sm rounded-2xl border border-gray-700 bg-gray-900 p-6 shadow-2xl">
            <h3 className="text-lg font-bold text-white">주문 취소 확인</h3>
            <div className="mt-3 rounded-lg bg-gray-800 px-4 py-3">
              <div className="flex justify-between text-sm">
                <span className="text-gray-400">종목</span>
                <span className="font-medium text-white">{code}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-400">방향</span>
                <span
                  className={side === 'BUY' ? 'font-medium text-red-400' : 'font-medium text-blue-400'}
                >
                  {sideLabel(side)}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-400">수량</span>
                <span className="font-medium text-white">{quantity.toLocaleString('ko-KR')}주</span>
              </div>
            </div>
            <p className="mt-3 text-sm text-gray-400">
              이 주문을 취소하면 KIS에 취소 요청이 전송됩니다. 이미 체결된 수량은 취소되지 않습니다.
            </p>
            <div className="mt-4 flex gap-2">
              <button
                type="button"
                onClick={() => setShowModal(false)}
                disabled={isPending}
                className="flex-1 rounded-lg border border-gray-700 py-2 text-sm font-medium text-gray-300 hover:bg-gray-800 disabled:opacity-50 focus:outline-none"
              >
                닫기
              </button>
              <button
                type="button"
                onClick={handleCancelConfirm}
                disabled={isPending}
                className="flex-1 rounded-lg bg-red-700 py-2 text-sm font-bold text-white hover:bg-red-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-red-400"
              >
                {isPending ? '취소 중...' : '취소 확인'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
