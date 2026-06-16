/**
 * AccountHoldingCard — KIS 보유 종목 카드 (hotfix /positions 빈 화면 — 사용자 dogfooding)
 *
 * 데이터 출처: GET /api/account/positions (KIS inquire-balance output1)
 * 매트릭스: code·name·quantity·avgPrice·currentPrice·evaluationAmount·profitLoss·profitLossRate
 *
 * 기존 PositionCard 는 DB position 테이블(PositionDto: id·qty·entryTs·source)을 렌더링.
 * 본 컴포넌트는 KIS 실시간(AccountHoldingDto: quantity·evaluationAmount·profitLoss·profitLossRate)
 * 을 그대로 표시 — entryTs/source 없음, 대신 실시간 평가손익·수익률 셀이 핵심.
 *
 * 룰 5: LLM provider import 0건
 */
import type { AccountHoldingDto } from '../../api/account'

/** 원화 포맷 — 천 단위 콤마 + "원" 접미 */
function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

/** 수익률 % — 소수 2자리 + 부호 */
function formatRate(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

/** 손익 색상 — 한국 표준 (상승=빨강, 하락=파랑) */
function pnlColor(value: number): string {
  if (value > 0) return 'text-red-400'
  if (value < 0) return 'text-blue-400'
  return 'text-gray-400'
}

interface AccountHoldingCardProps {
  holding: AccountHoldingDto
  canTrade?: boolean
  tradeDisabledReason?: string | null
  buyCooldownRemainSec?: number
  sellCooldownRemainSec?: number
  onBuyMore?: (holding: AccountHoldingDto) => void
  onSellPartial?: (holding: AccountHoldingDto) => void
  onClosePosition?: (holding: AccountHoldingDto) => void
}

export function AccountHoldingCard({
  holding,
  canTrade = false,
  tradeDisabledReason = null,
  buyCooldownRemainSec = 0,
  sellCooldownRemainSec = 0,
  onBuyMore,
  onSellPartial,
  onClosePosition,
}: AccountHoldingCardProps) {
  const {
    code,
    name,
    quantity,
    avgPrice,
    currentPrice,
    evaluationAmount,
    profitLoss,
    profitLossRate,
  } = holding
  const buyBlocked = !canTrade || buyCooldownRemainSec > 0
  const sellBlocked = !canTrade || sellCooldownRemainSec > 0
  const buyDisabledReason =
    buyCooldownRemainSec > 0 ? `추가 매수 재시도 대기 ${buyCooldownRemainSec}초` : tradeDisabledReason
  const disabledReason =
    buyDisabledReason ??
    (sellCooldownRemainSec > 0 ? `매도 재시도 대기 ${sellCooldownRemainSec}초` : tradeDisabledReason)

  return (
    <div
      className="rounded-xl border border-gray-800 bg-gray-900/60 p-4 transition-colors hover:bg-gray-800/40"
      aria-label={`보유 종목 ${name || code}`}
    >
      {/* 상단: 종목코드 + 종목명 */}
      <div className="flex items-start justify-between">
        <div>
          <span className="font-mono text-base font-bold text-white">{code}</span>
          {name && <span className="ml-2 text-sm text-gray-400">{name}</span>}
        </div>
      </div>

      {/* 중단: 4 셀 (수량·평균가·현재가·평가금액) */}
      <div className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <p className="text-xs text-gray-500">보유 수량</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {quantity.toLocaleString('ko-KR')}주
          </p>
        </div>
        <div>
          <p className="text-xs text-gray-500">평균 매수가</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {formatKrw(avgPrice)}
          </p>
        </div>
        <div>
          <p className="text-xs text-gray-500">현재가</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {formatKrw(currentPrice)}
          </p>
        </div>
        <div>
          <p className="text-xs text-gray-500">평가금액</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {formatKrw(evaluationAmount)}
          </p>
        </div>
      </div>

      {/* 하단: 평가손익 + 수익률 */}
      <div className="mt-3 flex items-center justify-between border-t border-gray-800 pt-3">
        <div>
          <p className="text-xs text-gray-500">평가손익</p>
          <p
            className={`mt-0.5 text-sm font-semibold tabular-nums ${pnlColor(profitLoss)}`}
          >
            {profitLoss > 0 ? '+' : ''}
            {formatKrw(profitLoss)}
          </p>
        </div>
        <div className="text-right">
          <p className="text-xs text-gray-500">수익률</p>
          <p
            className={`mt-0.5 text-sm font-semibold tabular-nums ${pnlColor(profitLossRate)}`}
          >
            {formatRate(profitLossRate)}
          </p>
        </div>
      </div>

      <div className="mt-3 border-t border-gray-800 pt-3">
        <div className="grid grid-cols-3 gap-2">
          <button
            type="button"
            onClick={() => onBuyMore?.(holding)}
            disabled={buyBlocked}
            className={[
              'rounded-lg px-2 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400',
              buyBlocked
                ? 'cursor-not-allowed bg-gray-800 text-gray-500'
                : 'border border-emerald-700 bg-emerald-950/40 text-emerald-200 hover:bg-emerald-900/60',
            ].join(' ')}
          >
            추가 매수
          </button>
          <button
            type="button"
            onClick={() => onSellPartial?.(holding)}
            disabled={sellBlocked}
            className={[
              'rounded-lg px-3 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
              sellBlocked
                ? 'cursor-not-allowed bg-gray-800 text-gray-500'
                : 'border border-blue-700 bg-blue-950/40 text-blue-200 hover:bg-blue-900/60',
            ].join(' ')}
          >
            일부 매도
          </button>
          <button
            type="button"
            onClick={() => onClosePosition?.(holding)}
            disabled={sellBlocked}
            className={[
              'rounded-lg px-3 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-red-400',
              sellBlocked
                ? 'cursor-not-allowed bg-gray-800 text-gray-500'
                : 'border border-red-700 bg-red-950/40 text-red-200 hover:bg-red-900/60',
            ].join(' ')}
          >
            전량 청산
          </button>
        </div>
        {disabledReason && (
          <p className="mt-2 text-[11px] leading-snug text-gray-500">{disabledReason}</p>
        )}
      </div>
    </div>
  )
}
