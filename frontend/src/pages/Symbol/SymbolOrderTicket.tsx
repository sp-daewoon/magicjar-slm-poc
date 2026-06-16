/**
 * SymbolOrderTicket — 종목상세 내 수동 주문 티켓.
 *
 * 보유 여부는 입력 제한이 아니라 참고 정보로만 사용한다.
 * 실제 주문 가능 여부는 기존 ManualOrderService → OrderRouter → KIS 검증이 권위다.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { accountApi, type AccountHoldingDto, type AccountType } from '../../api/account'
import { useManualOrderMutation } from '../../api/hooks/useManualOrderMutation'
import type { ManualOrderRequest, OrderPriceType, OrderSide, TradingMode } from '../../api/order'
import { OrderPreviewModal } from '../../components/order/OrderPreviewModal'

const ACCOUNT_OPTIONS: { value: AccountType; label: string }[] = [
  { value: 'REAL', label: '실전' },
  { value: 'MOCK', label: '모의' },
]

const SYMBOL_ORDER_COOLDOWN_SECONDS = 60

interface SymbolOrderTicketProps {
  code: string
  name: string
  currentPrice: string | null
  userId: number | null
  accountType: AccountType
  accountTypeReady: boolean
  onAccountTypeChange: (next: AccountType) => void
  holding: AccountHoldingDto | null
  isHoldingLoading: boolean
  isHoldingError: boolean
  orderMode: TradingMode | null
  isRuntimeFlagsLoading: boolean
  allowReal: boolean
  hasConsent: boolean
  killSwitchOn: boolean
  selectedLimitPrice: string
  onLimitPriceChange: (next: string) => void
}

function symbolOrderCooldownKey(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): string {
  return `symbol_order_cooldown:${userId}:${accountType}:${code}:${side}`
}

function getSymbolOrderCooldownRemainSec(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): number {
  try {
    const raw = sessionStorage.getItem(symbolOrderCooldownKey(userId, accountType, code, side))
    if (!raw) return 0
    const expiryEpoch = parseInt(raw, 10)
    if (Number.isNaN(expiryEpoch)) return 0
    const remain = expiryEpoch - Math.floor(Date.now() / 1000)
    return remain > 0 ? remain : 0
  } catch {
    return 0
  }
}

function setSymbolOrderCooldown(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): void {
  try {
    const expiry = Math.floor(Date.now() / 1000) + SYMBOL_ORDER_COOLDOWN_SECONDS
    sessionStorage.setItem(symbolOrderCooldownKey(userId, accountType, code, side), String(expiry))
  } catch {
    // sessionStorage 차단 환경에서는 UI 재시도 쿨다운만 생략된다.
  }
}

function parseNumber(value: string | null): number {
  if (!value) return NaN
  const n = Number(value)
  return Number.isFinite(n) ? n : NaN
}

function formatKrw(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '-'
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

function normalizeNumericText(value: string): string {
  return value.replace(/,/g, '').trim()
}

function formatNumericInput(value: string): string {
  const normalized = normalizeNumericText(value)
  if (!normalized) return ''
  const [integerPart, decimalPart] = normalized.split('.', 2)
  const integer = Number(integerPart)
  if (!Number.isFinite(integer)) return value
  const formattedInteger = integer.toLocaleString('ko-KR', { maximumFractionDigits: 0 })
  return decimalPart === undefined ? formattedInteger : `${formattedInteger}.${decimalPart}`
}

function sideLabel(side: OrderSide): string {
  return side === 'BUY' ? '매수' : '매도'
}

function priceTypeLabel(priceType: OrderPriceType): string {
  return priceType === 'MARKET' ? '시장가' : '지정가'
}

export function SymbolOrderTicket({
  code,
  name,
  currentPrice,
  userId,
  accountType,
  accountTypeReady,
  onAccountTypeChange,
  holding,
  isHoldingLoading,
  isHoldingError,
  orderMode,
  isRuntimeFlagsLoading,
  allowReal,
  hasConsent,
  killSwitchOn,
  selectedLimitPrice,
  onLimitPriceChange,
}: SymbolOrderTicketProps) {
  const queryClient = useQueryClient()
  const manualOrderMutation = useManualOrderMutation({ navigateOnSuccess: false })
  const [side, setSide] = useState<OrderSide>('BUY')
  const [priceType, setPriceType] = useState<OrderPriceType>('LIMIT')
  const [quantityText, setQuantityText] = useState('1')
  const [previewOpen, setPreviewOpen] = useState(false)
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null)
  const [cooldownTick, setCooldownTick] = useState(0)
  const seededPriceRef = useRef(false)
  const postOrderRefreshTimersRef = useRef<number[]>([])

  const currentPriceNumber = parseNumber(currentPrice)
  const normalizedLimitPrice = normalizeNumericText(selectedLimitPrice)
  const limitPriceNumber = parseNumber(normalizedLimitPrice)
  const parsedQuantity = quantityText === '' ? NaN : Number(quantityText)
  const quantity = Number.isInteger(parsedQuantity) ? parsedQuantity : NaN
  const quantityValid = quantity >= 1
  const limitPriceValid = priceType === 'MARKET' || (Number.isFinite(limitPriceNumber) && limitPriceNumber > 0)
  const estimatedPrice = priceType === 'MARKET' ? currentPriceNumber : limitPriceNumber
  const estimatedAmount = quantityValid ? estimatedPrice * quantity : NaN
  const isReal = orderMode === 'REAL'
  const modeMatchesAccount = orderMode === accountType
  const realGatePassed = !isReal || (allowReal && hasConsent)
  const cooldownRemainSec = useMemo(() => {
    void cooldownTick
    if (!userId) return 0
    return getSymbolOrderCooldownRemainSec(userId, accountType, code, side)
  }, [accountType, code, cooldownTick, side, userId])

  const quickQuantityOptions = useMemo(() => {
    if (side === 'SELL' && holding && holding.quantity > 0) {
      return Array.from(
        new Set([
          Math.max(1, Math.floor(holding.quantity * 0.25)),
          Math.max(1, Math.floor(holding.quantity * 0.5)),
          holding.quantity,
        ]),
      ).map((value) => ({
        label: value === holding.quantity ? '전량' : `${value.toLocaleString('ko-KR')}주`,
        value,
      }))
    }
    return [
      { label: '1주', value: 1 },
      { label: '5주', value: 5 },
      { label: '10주', value: 10 },
    ]
  }, [holding, side])

  const holdingStatus = useMemo(() => {
    if (!userId) return '계좌 미등록'
    if (isHoldingLoading) return '확인 중'
    if (isHoldingError) return '조회 실패'
    if (!holding) return '보유 없음'
    return `${holding.quantity.toLocaleString('ko-KR')}주`
  }, [holding, isHoldingError, isHoldingLoading, userId])

  const sellNeedsKisCheck =
    side === 'SELL' &&
    quantityValid &&
    (!holding || quantity > holding.quantity)

  const submitDisabledReason = useMemo(() => {
    if (!userId) return 'KIS 계좌 등록 후 주문할 수 있습니다.'
    if (!accountTypeReady) return '계좌 구분을 확인 중입니다.'
    if (isRuntimeFlagsLoading || orderMode === null) return '주문 런타임 모드를 확인 중입니다.'
    if (!modeMatchesAccount) return `현재 주문 모드가 ${orderMode}라 ${accountType} 종목상세 주문을 막았습니다.`
    if (killSwitchOn) return 'KillSwitch ON 상태라 주문이 차단됩니다.'
    if (!realGatePassed) return 'REAL 게이트가 통과되지 않았습니다.'
    if (cooldownRemainSec > 0) return `동일 종목 ${sideLabel(side)} 재시도까지 ${cooldownRemainSec}초 남았습니다.`
    if (!quantityValid) return '주문 수량을 확인해 주세요.'
    if (!limitPriceValid) return '지정가를 확인해 주세요.'
    return null
  }, [
    accountType,
    accountTypeReady,
    cooldownRemainSec,
    isRuntimeFlagsLoading,
    killSwitchOn,
    limitPriceValid,
    modeMatchesAccount,
    orderMode,
    quantityValid,
    realGatePassed,
    side,
    userId,
  ])

  const canOpenPreview = submitDisabledReason === null && !manualOrderMutation.isPending

  useEffect(() => {
    const timer = window.setInterval(() => setCooldownTick((tick) => tick + 1), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    seededPriceRef.current = false
    setQuantityText('1')
    setPreviewOpen(false)
  }, [code])

  useEffect(() => {
    if (selectedLimitPrice) {
      setPriceType('LIMIT')
      return
    }
    if (seededPriceRef.current || !Number.isFinite(currentPriceNumber) || currentPriceNumber <= 0) return
    seededPriceRef.current = true
    onLimitPriceChange(String(Math.trunc(currentPriceNumber)))
  }, [currentPriceNumber, onLimitPriceChange, selectedLimitPrice])

  useEffect(
    () => () => {
      for (const timer of postOrderRefreshTimersRef.current) {
        window.clearTimeout(timer)
      }
    },
    [],
  )

  function setQuantityFromNumber(value: number) {
    const next = Number.isFinite(value) ? Math.max(1, Math.trunc(value)) : 1
    setQuantityText(String(next))
  }

  function handleQuantityChange(nextValue: string) {
    if (nextValue === '' || /^\d+$/.test(nextValue)) {
      setQuantityText(nextValue)
    }
  }

  function handleLimitPriceChange(nextValue: string) {
    const normalized = normalizeNumericText(nextValue)
    if (normalized === '' || /^\d+(\.\d*)?$/.test(normalized)) {
      onLimitPriceChange(normalized)
      if (normalized) setPriceType('LIMIT')
    }
  }

  async function forceRefreshHolding(notice: string) {
    if (!userId) return
    setRefreshNotice(notice)
    try {
      const fresh = await accountApi.getPositions(userId, accountType, { refresh: true })
      queryClient.setQueryData(['account', 'positions', userId, accountType], fresh)
      void queryClient.invalidateQueries({
        queryKey: ['account', 'summary', userId, accountType],
        exact: true,
      })
    } catch {
      setRefreshNotice('KIS 보유 수량 새로고침에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    }
  }

  function schedulePostOrderRefresh() {
    for (const timer of postOrderRefreshTimersRef.current) {
      window.clearTimeout(timer)
    }
    postOrderRefreshTimersRef.current = []
    void forceRefreshHolding('주문이 접수되었습니다. KIS 체결/잔고 반영을 확인 중입니다.')
    postOrderRefreshTimersRef.current = [1_500, 4_000, 8_000, 12_000].map((delay) =>
      window.setTimeout(() => {
        void forceRefreshHolding('주문 체결 반영을 다시 확인 중입니다.')
      }, delay),
    )
  }

  function handleOpenPreview() {
    if (!canOpenPreview) return
    setPreviewOpen(true)
  }

  function handleConfirmSubmit() {
    if (!userId || !canOpenPreview) return
    const request: ManualOrderRequest = {
      userId,
      code,
      side,
      priceType,
      quantity,
      limitPrice: priceType === 'LIMIT' ? normalizedLimitPrice : null,
    }
    manualOrderMutation.mutate(request, {
      onSuccess: () => {
        setSymbolOrderCooldown(userId, accountType, code, side)
        setCooldownTick((tick) => tick + 1)
        setPreviewOpen(false)
        schedulePostOrderRefresh()
      },
      onError: () => {
        setPreviewOpen(false)
      },
    })
  }

  const sideTone = side === 'BUY' ? 'text-red-300' : 'text-blue-300'

  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900 p-4" aria-label={`${code} 종목 주문`}>
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-white">종목 주문</h2>
          <p className="mt-0.5 text-xs text-gray-500">
            {name} <span className="font-mono">{code}</span>
          </p>
        </div>
        <span
          className={[
            'rounded-lg border px-2 py-0.5 text-xs font-semibold',
            isReal
              ? 'border-red-700 bg-red-950 text-red-200'
              : 'border-gray-700 bg-gray-800 text-gray-300',
          ].join(' ')}
        >
          주문 {orderMode ?? '확인 중'}
        </span>
      </div>

      <div className="mb-4 grid grid-cols-2 gap-2 text-xs">
        <div className="rounded-lg border border-gray-800 bg-gray-950 px-3 py-2">
          <p className="text-gray-500">현재가</p>
          <p className="mt-1 font-semibold text-white tabular-nums">
            {formatKrw(currentPriceNumber)}
          </p>
        </div>
        <div className="rounded-lg border border-gray-800 bg-gray-950 px-3 py-2">
          <p className="text-gray-500">{accountType} 보유</p>
          <p className="mt-1 font-semibold text-white tabular-nums">{holdingStatus}</p>
        </div>
      </div>

      <div className="mb-4">
        <p className="mb-2 text-xs font-medium text-gray-400">계좌</p>
        <div className="grid grid-cols-2 gap-2">
          {ACCOUNT_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => onAccountTypeChange(option.value)}
              disabled={!accountTypeReady}
              className={[
                'rounded-lg px-3 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-50',
                accountType === option.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 bg-gray-900 text-gray-400 hover:bg-gray-800',
              ].join(' ')}
              aria-pressed={accountType === option.value}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          handleOpenPreview()
        }}
      >
        <div>
          <p className="mb-2 text-xs font-medium text-gray-400">주문 구분</p>
          <div className="grid grid-cols-2 gap-2">
            {(['BUY', 'SELL'] as const).map((nextSide) => (
              <button
                key={nextSide}
                type="button"
                onClick={() => setSide(nextSide)}
                className={[
                  'rounded-lg px-3 py-2 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
                  side === nextSide
                    ? nextSide === 'BUY'
                      ? 'bg-red-600 text-white focus:ring-red-400'
                      : 'bg-blue-600 text-white focus:ring-blue-400'
                    : nextSide === 'BUY'
                      ? 'border border-gray-700 bg-gray-900 text-red-300 hover:bg-gray-800'
                      : 'border border-gray-700 bg-gray-900 text-blue-300 hover:bg-gray-800',
                ].join(' ')}
                aria-pressed={side === nextSide}
              >
                {sideLabel(nextSide)}
              </button>
            ))}
          </div>
        </div>

        <div>
          <p className="mb-2 text-xs font-medium text-gray-400">가격 유형</p>
          <div className="grid grid-cols-2 gap-2">
            {(['LIMIT', 'MARKET'] as const).map((nextType) => (
              <label
                key={nextType}
                className={[
                  'flex cursor-pointer items-center justify-center gap-2 rounded-lg border px-3 py-2 text-xs font-semibold transition-colors',
                  priceType === nextType
                    ? 'border-cyan-500 bg-cyan-500/10 text-cyan-200'
                    : 'border-gray-700 bg-gray-900 text-gray-400 hover:bg-gray-800',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name="symbolOrderPriceType"
                  value={nextType}
                  checked={priceType === nextType}
                  onChange={() => setPriceType(nextType)}
                  className="accent-cyan-500"
                />
                {priceTypeLabel(nextType)}
              </label>
            ))}
          </div>
        </div>

        {priceType === 'LIMIT' && (
          <div>
            <label htmlFor="symbolOrderLimitPrice" className="mb-2 block text-xs font-medium text-gray-400">
              주문 가격
            </label>
            <input
              id="symbolOrderLimitPrice"
              type="text"
              inputMode="decimal"
              value={formatNumericInput(selectedLimitPrice)}
              onChange={(e) => handleLimitPriceChange(e.target.value)}
              className="h-10 w-full rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm font-semibold text-white tabular-nums focus:border-cyan-500 focus:outline-none focus:ring-1 focus:ring-cyan-500"
              placeholder="0"
            />
          </div>
        )}

        <div>
          <label htmlFor="symbolOrderQuantity" className="mb-2 block text-xs font-medium text-gray-400">
            주문 수량
          </label>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setQuantityFromNumber((quantityValid ? quantity : 1) - 1)}
              className="h-10 w-10 rounded-lg border border-gray-700 bg-gray-900 text-lg text-gray-300 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
              aria-label="주문 수량 1주 줄이기"
            >
              -
            </button>
            <input
              id="symbolOrderQuantity"
              type="text"
              inputMode="numeric"
              value={quantityText}
              onChange={(e) => handleQuantityChange(e.target.value)}
              className="h-10 min-w-0 flex-1 rounded-lg border border-gray-700 bg-gray-950 px-3 text-center text-sm font-semibold text-white tabular-nums focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={() => setQuantityFromNumber((quantityValid ? quantity : 0) + 1)}
              className="h-10 w-10 rounded-lg border border-gray-700 bg-gray-900 text-lg text-gray-300 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
              aria-label="주문 수량 1주 늘리기"
            >
              +
            </button>
          </div>
          <div className="mt-2 grid grid-cols-3 gap-2">
            {quickQuantityOptions.map((option) => (
              <button
                key={`${option.label}-${option.value}`}
                type="button"
                onClick={() => setQuantityFromNumber(option.value)}
                className="rounded-lg border border-gray-700 bg-gray-900 px-2 py-1.5 text-xs text-gray-300 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        <dl className="grid grid-cols-2 gap-2 rounded-lg border border-gray-800 bg-gray-950 p-3 text-xs">
          <div>
            <dt className="text-gray-500">방향</dt>
            <dd className={`mt-1 font-semibold ${sideTone}`}>{sideLabel(side)}</dd>
          </div>
          <div>
            <dt className="text-gray-500">예상 금액</dt>
            <dd className="mt-1 font-semibold text-white tabular-nums">{formatKrw(estimatedAmount)}</dd>
          </div>
        </dl>

        {sellNeedsKisCheck && (
          <div className="rounded-lg border border-yellow-800 bg-yellow-950/30 px-3 py-2 text-xs text-yellow-300">
            보유 수량을 초과하거나 보유가 확인되지 않은 매도입니다. 주문은 접수 전후 KIS에서 최종 검증됩니다.
          </div>
        )}

        {submitDisabledReason && (
          <div className="rounded-lg border border-gray-800 bg-gray-950 px-3 py-2 text-xs text-gray-400">
            {submitDisabledReason}
          </div>
        )}

        {refreshNotice && (
          <div className="rounded-lg border border-blue-900 bg-blue-950/30 px-3 py-2 text-xs text-blue-200">
            {refreshNotice}
          </div>
        )}

        <button
          type="submit"
          disabled={!canOpenPreview}
          className={[
            'w-full rounded-xl px-4 py-3 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
            canOpenPreview
              ? isReal
                ? 'bg-red-600 text-white hover:bg-red-500 focus:ring-red-400'
                : side === 'BUY'
                  ? 'bg-red-600 text-white hover:bg-red-500 focus:ring-red-400'
                  : 'bg-blue-600 text-white hover:bg-blue-500 focus:ring-blue-400'
              : 'cursor-not-allowed bg-gray-800 text-gray-500',
          ].join(' ')}
        >
          {manualOrderMutation.isPending ? '접수 중...' : '미리보기'}
        </button>
      </form>

      <OrderPreviewModal
        open={previewOpen}
        code={code}
        codeName={name || code}
        side={side}
        priceType={priceType}
        quantity={quantity}
        limitPrice={priceType === 'LIMIT' ? normalizedLimitPrice : null}
        mode={orderMode ?? 'MOCK'}
        allowReal={allowReal}
        hasConsent={hasConsent}
        killSwitchOn={killSwitchOn}
        cooldownRemainSec={cooldownRemainSec}
        onConfirm={handleConfirmSubmit}
        onCancel={() => setPreviewOpen(false)}
        isPending={manualOrderMutation.isPending}
      />
    </section>
  )
}
