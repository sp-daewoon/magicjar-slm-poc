/**
 * PositionOrderModal — 보유 종목 추가 매수 / 매도 / 전량 청산 확인 모달.
 *
 * `/positions`의 KIS 실시간 보유 종목을 기준으로 기존 수동 주문 API
 * `POST /api/orders/manual`에 MARKET 주문을 위임한다.
 */
import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { AccountHoldingDto, AccountType } from '../../api/account'
import type { OrderSide, TradingMode } from '../../api/order'
import { useManualOrderMutation } from '../../api/hooks/useManualOrderMutation'
import { useModalBehavior } from '../../hooks/useModalBehavior'

const MAX_BUY_QUANTITY = 999_999

interface PositionOrderModalProps {
  holding: AccountHoldingDto | null
  side: OrderSide | null
  userId: number
  accountType: AccountType
  orderMode: TradingMode | null
  allowReal: boolean
  hasConsent: boolean
  killSwitchOn: boolean
  cooldownRemainSec: number
  initialQuantity: number
  onClose: () => void
  onSubmitted: (holding: AccountHoldingDto, side: OrderSide) => void
}

function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

function clampQuantity(
  value: number,
  max: number,
): number {
  if (!Number.isFinite(value)) return 1
  return Math.min(max, Math.max(1, Math.trunc(value)))
}

function percentQuantity(
  total: number,
  percent: number,
): number {
  if (percent >= 1) return total
  return clampQuantity(Math.floor(total * percent), total)
}

function sideLabel(side: OrderSide): string {
  return side === 'BUY' ? '추가 매수' : '매도'
}

export function PositionOrderModal({
  holding,
  side,
  userId,
  accountType,
  orderMode,
  allowReal,
  hasConsent,
  killSwitchOn,
  cooldownRemainSec,
  initialQuantity,
  onClose,
  onSubmitted,
}: PositionOrderModalProps) {
  const queryClient = useQueryClient()
  const manualOrderMutation = useManualOrderMutation({ navigateOnSuccess: false })
  const [quantityText, setQuantityText] = useState('1')
  const [confirmed, setConfirmed] = useState(false)

  const isBuy = side === 'BUY'
  const quantityLimit = holding && side === 'SELL' ? holding.quantity : MAX_BUY_QUANTITY
  const parsedQuantity = quantityText === '' ? NaN : Number(quantityText)
  const quantity = Number.isInteger(parsedQuantity) ? parsedQuantity : NaN
  const modeMatchesAccount = orderMode === accountType
  const isReal = orderMode === 'REAL'
  const realGatePassed = !isReal || (allowReal && hasConsent)
  const quantityValid = holding !== null && side !== null && quantity >= 1 && quantity <= quantityLimit
  const isFullClose = holding !== null && side === 'SELL' && quantity === holding.quantity
  const estimatedAmount = holding && quantityValid ? holding.currentPrice * quantity : 0
  const actionLabel = side ? sideLabel(side) : '주문'
  const submitDisabledReason = useMemo(() => {
    if (!holding || !side) return '선택된 보유 종목이 없습니다.'
    if (orderMode === null) return '주문 런타임 모드를 확인 중입니다.'
    if (!modeMatchesAccount) return `현재 주문 모드가 ${orderMode}라 ${accountType} 포지션 ${actionLabel}를 막았습니다.`
    if (killSwitchOn) return 'KillSwitch ON 상태라 주문이 차단됩니다.'
    if (!realGatePassed) return 'REAL 게이트가 통과되지 않았습니다.'
    if (cooldownRemainSec > 0) return `동일 종목 ${actionLabel} 재시도까지 ${cooldownRemainSec}초 남았습니다.`
    if (!quantityValid) return `${actionLabel} 수량을 확인해 주세요.`
    if (!confirmed) return '주문 내용을 확인해 주세요.'
    return null
  }, [
    accountType,
    actionLabel,
    confirmed,
    cooldownRemainSec,
    holding,
    killSwitchOn,
    modeMatchesAccount,
    orderMode,
    quantityValid,
    realGatePassed,
    side,
  ])
  const canSubmit = submitDisabledReason === null && !manualOrderMutation.isPending

  useEffect(() => {
    if (!holding || !side) return
    const limit = side === 'SELL' ? holding.quantity : MAX_BUY_QUANTITY
    setQuantityText(String(clampQuantity(initialQuantity, limit)))
    setConfirmed(false)
  }, [holding, initialQuantity, side])

  // ESC 닫기 + body scroll lock (holding·side 둘 다 있을 때만 활성)
  useModalBehavior({ open: Boolean(holding && side), onClose })

  if (!holding || !side) return null

  function setQuantityFromNumber(value: number) {
    setQuantityText(String(clampQuantity(value, quantityLimit)))
  }

  function handleQuantityChange(nextValue: string) {
    if (nextValue === '' || /^\d+$/.test(nextValue)) {
      setQuantityText(nextValue)
    }
  }

  function quantityLabel(): string {
    if (quantityText === '') return '0'
    const value = Number(quantityText)
    if (!Number.isFinite(value)) return '0'
    return value.toLocaleString('ko-KR')
  }

  function handleSubmit() {
    if (!holding || !side || !canSubmit) return
    manualOrderMutation.mutate(
      {
        userId,
        code: holding.code,
        side,
        priceType: 'MARKET',
        quantity,
        limitPrice: null,
      },
      {
        onSuccess: () => {
          void queryClient.invalidateQueries({ queryKey: ['account'] })
          void queryClient.invalidateQueries({ queryKey: ['orders'] })
          onSubmitted(holding, side)
          onClose()
        },
      },
    )
  }

  const title = isBuy
    ? '보유 종목 추가 매수'
    : isFullClose
      ? '종목 전량 청산'
      : '보유 종목 매도'
  const quantityInputLabel = isBuy ? '매수 수량' : '매도 수량'
  const confirmLabel = isBuy ? '포지션 추가 매수 주문 내용 확인' : '포지션 매도 주문 내용 확인'
  const submitLabel = isBuy ? '추가 매수 접수' : isFullClose ? '전량 청산 접수' : '매도 주문 접수'

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="position-order-title"
      onClick={onClose}
    >
      <div
        className={`w-full max-w-lg rounded-2xl border ${
          isReal ? 'border-red-700 bg-red-950/30' : 'border-gray-800 bg-gray-900'
        } p-6 shadow-2xl`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <div>
            <h2 id="position-order-title" className="text-lg font-bold text-white">
              {title}
            </h2>
            <p className="mt-1 text-xs text-gray-500">
              시장가 {side} 주문으로 접수됩니다. 체결 후 KIS 보유 수량이 갱신됩니다.
            </p>
          </div>
          <span
            className={`rounded-lg border px-2 py-0.5 text-xs font-semibold ${
              isReal
                ? 'border-red-700 bg-red-950 text-red-200'
                : 'border-gray-700 bg-gray-800 text-gray-300'
            }`}
          >
            주문 {orderMode ?? '확인 중'} · 보기 {accountType}
          </span>
        </div>

        <dl className="mb-4 grid grid-cols-2 gap-3 rounded-xl border border-gray-800 bg-gray-950 p-4 text-sm">
          <div>
            <dt className="text-xs text-gray-500">종목</dt>
            <dd className="mt-0.5 font-semibold text-white">
              {holding.name || holding.code}
              <span className="ml-1 font-mono text-xs text-gray-500">{holding.code}</span>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500">보유 수량</dt>
            <dd className="mt-0.5 font-semibold text-white tabular-nums">
              {holding.quantity.toLocaleString('ko-KR')}주
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500">현재가</dt>
            <dd className="mt-0.5 font-semibold text-white tabular-nums">
              {formatKrw(holding.currentPrice)}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500">{isBuy ? '예상 매수금액' : '예상 매도금액'}</dt>
            <dd className="mt-0.5 font-semibold text-white tabular-nums">
              {formatKrw(estimatedAmount)}
            </dd>
          </div>
        </dl>

        {isReal && (
          <div className="mb-4 rounded-xl border border-red-700 bg-red-950/50 px-4 py-3">
            <p className="text-sm font-bold text-red-300">실전 계좌 {actionLabel} 주문입니다.</p>
            <p className="mt-1 text-xs text-red-400">
              시장가 주문은 체결 가격이 현재가와 달라질 수 있습니다.
            </p>
          </div>
        )}

        {!modeMatchesAccount && orderMode !== null && (
          <div className="mb-4 rounded-xl border border-yellow-700 bg-yellow-950/30 px-4 py-3 text-xs text-yellow-300">
            현재 주문 런타임은 {orderMode}입니다. {accountType} 탭의 보유 종목과 주문 계좌가 달라질 수 있어 주문을 차단했습니다.
          </div>
        )}

        <div className="mb-4">
          <label htmlFor="positionOrderQuantity" className="mb-2 block text-sm font-medium text-gray-300">
            {quantityInputLabel}
          </label>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setQuantityFromNumber((quantityValid ? quantity : 1) - 1)}
              className="h-10 w-10 rounded-lg border border-gray-700 bg-gray-900 text-lg text-gray-300 hover:bg-gray-800"
              aria-label={`${quantityInputLabel} 1주 줄이기`}
            >
              -
            </button>
            <input
              id="positionOrderQuantity"
              type="number"
              min={1}
              max={quantityLimit}
              step={1}
              value={quantityText}
              onChange={(e) => handleQuantityChange(e.target.value)}
              className="h-10 flex-1 rounded-lg border border-gray-700 bg-gray-950 px-3 text-center text-sm font-semibold text-white tabular-nums focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={() => setQuantityFromNumber((quantityValid ? quantity : 0) + 1)}
              className="h-10 w-10 rounded-lg border border-gray-700 bg-gray-900 text-lg text-gray-300 hover:bg-gray-800"
              aria-label={`${quantityInputLabel} 1주 늘리기`}
            >
              +
            </button>
          </div>
          <div className="mt-2 grid grid-cols-3 gap-2">
            {(isBuy
              ? [
                  { label: '1주', value: 1 },
                  { label: '5주', value: 5 },
                  { label: '10주', value: 10 },
                ]
              : [
                  { label: '25%', value: percentQuantity(holding.quantity, 0.25) },
                  { label: '50%', value: percentQuantity(holding.quantity, 0.5) },
                  { label: '전량', value: holding.quantity },
                ]
            ).map((item) => (
              <button
                key={item.label}
                type="button"
                onClick={() => setQuantityFromNumber(item.value)}
                className="rounded-lg border border-gray-700 bg-gray-900 px-3 py-1.5 text-xs text-gray-300 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>

        <label className="mb-4 flex cursor-pointer items-start gap-2 rounded-xl border border-gray-800 bg-gray-950 p-3">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
            className="mt-0.5 h-4 w-4 cursor-pointer accent-blue-500"
            aria-label={confirmLabel}
          />
          <span className="text-xs text-gray-300">
            {holding.name || holding.code} {quantityLabel()}주를 시장가로 {actionLabel}하는 내용을 확인했습니다.
          </span>
        </label>

        {submitDisabledReason && confirmed && (
          <p className="mb-3 rounded-lg border border-gray-800 bg-gray-950 px-3 py-2 text-xs text-gray-400">
            {submitDisabledReason}
          </p>
        )}

        <div className="flex gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={manualOrderMutation.isPending}
            className="flex-1 rounded-xl border border-gray-700 bg-gray-900 px-4 py-2.5 text-sm font-medium text-gray-300 transition-colors hover:bg-gray-800 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className={[
              'flex-1 rounded-xl px-4 py-2.5 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
              canSubmit
                ? isReal
                  ? 'bg-red-600 text-white hover:bg-red-500 focus:ring-red-400'
                  : isBuy
                    ? 'bg-emerald-600 text-white hover:bg-emerald-500 focus:ring-emerald-400'
                    : 'bg-blue-600 text-white hover:bg-blue-500 focus:ring-blue-400'
                : 'cursor-not-allowed bg-gray-800 text-gray-500',
            ].join(' ')}
          >
            {manualOrderMutation.isPending ? '접수 중...' : submitLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
