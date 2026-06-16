/**
 * SymbolOrderbook — 매수·매도 10단계 호가표
 *
 * spec §6-2 §5 호가 실시간 정합:
 * - H0UNASP0 통합 호가
 * - 페이지 mount → POST /api/symbols/{code}/orderbook/subscribe (SymbolDetailPage에서 처리)
 * - STOMP /topic/market.orderbook.{code} 실시간 갱신
 * - 호가 데이터 없음 (장 마감 등) → "호가 데이터 없음" 표시 (graceful)
 * - polling fallback 배지: slot='queued' 시 "폴링 fallback 중" 배지 (spec §10-1 정합)
 *
 * 결함 3 수정: orderbook === null 시 장 시간 여부에 따라 메시지 분기.
 *   - 장외 시간: "장 마감 (호가는 거래시간에만 제공)"
 *   - 장 중 null: "호가 데이터 대기 중..." (기존 유지)
 */
import type { OrderbookPayload, OrderbookLevel } from '../../api/symbol'
import { isKoreanMarketOpen } from '../../lib/marketHours'

interface SymbolOrderbookProps {
  code: string
  orderbook: OrderbookPayload | null
  orderbookSlot?: 'live' | 'queued' | null
  isStompConnected: boolean
  selectedPrice?: string
  onSelectPrice?: (price: string, side: 'ask' | 'bid') => void
}

function formatPrice(price: string): string {
  const num = parseFloat(price)
  if (isNaN(num)) return '-'
  return num.toLocaleString('ko-KR')
}

function formatQuantity(qty: number): string {
  if (!qty) return '-'
  if (qty >= 1_000_000) return `${(qty / 1_000_000).toFixed(1)}M`
  if (qty >= 1_000) return `${(qty / 1_000).toFixed(0)}K`
  return qty.toLocaleString('ko-KR')
}

interface OrderbookRowProps {
  level: OrderbookLevel
  side: 'ask' | 'bid'
  maxQty: number
  selectedPrice?: string
  onSelectPrice?: (price: string, side: 'ask' | 'bid') => void
}

function OrderbookRow({ level, side, maxQty, selectedPrice, onSelectPrice }: OrderbookRowProps) {
  const qty = level.quantity
  const barWidth = maxQty > 0 ? Math.round((qty / maxQty) * 100) : 0
  const isAsk = side === 'ask'
  const isSelected = selectedPrice === level.price

  const priceClassName = [
    'relative z-10 rounded px-1.5 py-0.5 font-medium tabular-nums transition-colors focus:outline-none focus:ring-2 focus:ring-cyan-400',
    isAsk ? 'text-blue-300 hover:bg-blue-500/15' : 'text-red-300 hover:bg-red-500/15',
    isSelected ? 'bg-cyan-500/15 ring-1 ring-cyan-300' : '',
  ].join(' ')

  const priceNode = onSelectPrice ? (
    <button
      type="button"
      onClick={() => onSelectPrice(level.price, side)}
      className={priceClassName}
      aria-label={`${formatPrice(level.price)}원 ${isAsk ? '매도' : '매수'}호가 지정가 선택`}
      title="지정가 선택"
    >
      {formatPrice(level.price)}
    </button>
  ) : (
    <span className={priceClassName}>{formatPrice(level.price)}</span>
  )

  return (
    <div className="relative flex items-center justify-between gap-2 py-0.5 px-2 rounded text-xs tabular-nums overflow-hidden">
      {/* 배경 바 (수량 비례) */}
      <div
        className={`absolute inset-y-0 ${isAsk ? 'right-0' : 'left-0'} opacity-20 ${
          isAsk ? 'bg-blue-500' : 'bg-red-500'
        }`}
        style={{ width: `${barWidth}%` }}
        aria-hidden="true"
      />

      {isAsk ? (
        <>
          {priceNode}
          <span className="relative z-10 text-gray-400">{formatQuantity(qty)}</span>
        </>
      ) : (
        <>
          <span className="relative z-10 text-gray-400">{formatQuantity(qty)}</span>
          {priceNode}
        </>
      )}
    </div>
  )
}

export function SymbolOrderbook({
  code,
  orderbook,
  orderbookSlot,
  isStompConnected,
  selectedPrice,
  onSelectPrice,
}: SymbolOrderbookProps) {
  if (!orderbook) {
    // 결함 3: 장 외 시간이면 "장 마감" 메시지, 장 중이면 기존 대기 메시지
    const marketOpen = isKoreanMarketOpen()
    const emptyMessage = !isStompConnected
      ? '실시간 연결 재시도 중... (STOMP 끊김)'
      : marketOpen
        ? '호가 데이터 대기 중...'
        : '장 마감 (호가는 거래시간에만 제공)'

    return (
      <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="text-xs font-medium text-gray-500">호가창</div>
          {!isStompConnected && (
            <span className="rounded-full bg-yellow-900/40 px-2 py-0.5 text-xs text-yellow-400">
              연결 대기 중
            </span>
          )}
          {!marketOpen && isStompConnected && (
            <span className="inline-flex items-center gap-1 rounded-full bg-gray-800/60 px-2 py-0.5 text-xs text-gray-400">
              <span className="h-1.5 w-1.5 rounded-full bg-gray-500" aria-hidden="true" />
              장 마감
            </span>
          )}
        </div>
        <div className="flex h-40 items-center justify-center">
          <p className="text-xs text-gray-600">{emptyMessage}</p>
        </div>
      </div>
    )
  }

  const asks = [...(orderbook.asks ?? [])].reverse() // 낮은 매도호가가 하단
  const bids = orderbook.bids ?? []
  const allQtys = [...asks, ...bids].map((l) => l.quantity)
  const maxQty = Math.max(...allQtys, 1)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-3">
        <div className="text-xs font-medium text-gray-500">호가창 · {code}</div>
        <div className="flex items-center gap-2">
          {orderbookSlot === 'queued' && (
            <span className="rounded-full bg-orange-900/40 px-2 py-0.5 text-xs text-orange-400">
              polling fallback 중
            </span>
          )}
          {orderbookSlot === 'live' && isStompConnected && (
            <span className="inline-flex items-center gap-1 rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">
              <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400" aria-hidden="true" />
              실시간
            </span>
          )}
        </div>
      </div>

      {/* 컬럼 헤더 */}
      <div className="grid grid-cols-2 gap-1 mb-1">
        <div className="flex justify-between px-2 text-xs text-blue-400/60">
          <span>매도호가</span>
          <span>수량</span>
        </div>
        <div className="flex justify-between px-2 text-xs text-red-400/60">
          <span>수량</span>
          <span>매수호가</span>
        </div>
      </div>

      {/* 호가 행 */}
      <div className="grid grid-cols-2 gap-x-1">
        {/* 매도 (asks) — 10단계 */}
        <div className="flex flex-col gap-0.5">
          {asks.slice(0, 10).map((level, i) => (
            <OrderbookRow
              key={`ask-${i}`}
              level={level}
              side="ask"
              maxQty={maxQty}
              selectedPrice={selectedPrice}
              onSelectPrice={onSelectPrice}
            />
          ))}
        </div>
        {/* 매수 (bids) — 10단계 */}
        <div className="flex flex-col gap-0.5">
          {bids.slice(0, 10).map((level, i) => (
            <OrderbookRow
              key={`bid-${i}`}
              level={level}
              side="bid"
              maxQty={maxQty}
              selectedPrice={selectedPrice}
              onSelectPrice={onSelectPrice}
            />
          ))}
        </div>
      </div>
    </div>
  )
}
