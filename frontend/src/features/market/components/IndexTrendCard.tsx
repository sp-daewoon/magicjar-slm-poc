/**
 * IndexTrendCard — 지수 카드 단건 (KOSPI·KOSDAQ·NASDAQ·S&P) — T-MARKET R2 §2-1.
 *
 * MarketIndexStrip 가로 4 카드 + MarketTrendPage 중앙 패널 공용.
 *
 * 표시: 지수명 + 마지막 종가 + 등락률 + 추세 라벨 + (MA periodsUsed)
 * dataInsufficient=true 시 "데이터 부족" 안내 (mock 합성 0 정합).
 */
import { INDEX_LABELS, toNumberOrNull, type IndexTrendResponse } from '../../../api/market'
import { TrendLabelBadge } from './TrendLabelBadge'

interface Props {
  index: IndexTrendResponse
  /** 클릭 시 콜백 (예: MarketTrendPage 선택 지수 변경). */
  onClick?: (code: string) => void
  /** 현재 선택 상태 표시 (좌측 패널 토글용). default false. */
  selected?: boolean
}

function formatNumber(value: number | null, fractionDigits = 2): string {
  if (value === null) return '-'
  return value.toLocaleString('ko-KR', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })
}

function formatChangeRate(value: number | null): { text: string; cls: string } {
  if (value === null) return { text: '-', cls: 'text-gray-500' }
  const sign = value > 0 ? '+' : ''
  const text = `${sign}${value.toFixed(2)}%`
  const cls = value > 0 ? 'text-emerald-400' : value < 0 ? 'text-red-400' : 'text-gray-400'
  return { text, cls }
}

export function IndexTrendCard({ index, onClick, selected = false }: Props) {
  const label = INDEX_LABELS[index.code] ?? index.code
  const lastClose = toNumberOrNull(index.lastClose)
  const changeRate = toNumberOrNull(index.changeRate)
  const change = formatChangeRate(changeRate)
  const hasQuote = lastClose !== null
  const isOverseasIndex = index.code.startsWith('.')
  const requiredPeriods = index.periodsUsed.length > 0 ? Math.max(...index.periodsUsed) : 60

  const isClickable = onClick !== undefined
  const baseCls =
    'flex min-w-[180px] flex-col gap-1 rounded-xl border bg-gray-900/60 px-4 py-3 transition-colors'
  const stateCls = selected
    ? 'border-blue-500 bg-blue-950/40'
    : isClickable
      ? 'border-gray-800 hover:border-gray-600 hover:bg-gray-900'
      : 'border-gray-800'

  const content = (
    <>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold text-gray-100">{label}</span>
        {/* periodsUsed echo — 매우 작은 자막 (MA 5·20·60) */}
        <span className="text-[10px] text-gray-500">MA {index.periodsUsed.join('·')}</span>
      </div>

      {!hasQuote ? (
        <div className="flex flex-col gap-1 py-1">
          <span className="text-sm text-gray-400">수집 대기</span>
          <span className="text-[10px] text-gray-600">
            {isOverseasIndex ? '미국장 최신값 대기 중' : '시장지수 수집 대기 중'}
          </span>
        </div>
      ) : (
        <>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-lg font-bold tabular-nums text-white">
              {formatNumber(lastClose)}
            </span>
            <span className={`text-sm font-medium tabular-nums ${change.cls}`}>{change.text}</span>
          </div>
          <div className="mt-0.5 flex items-center justify-between gap-2">
            <TrendLabelBadge label={index.trendLabel} compact />
            <div className="flex flex-wrap justify-end gap-1">
              {index.dataInsufficient && (
                <span className="text-[10px] text-amber-300">
                  데이터 부족 · {requiredPeriods}일 이상 수집 필요
                </span>
              )}
              {index.isUserCustom && (
                <span className="text-[10px] text-blue-400" title="사용자 설정 MA periods 적용">
                  user pref
                </span>
              )}
            </div>
          </div>
        </>
      )}
    </>
  )

  if (isClickable) {
    return (
      <button
        type="button"
        onClick={() => onClick?.(index.code)}
        className={`${baseCls} ${stateCls} focus:outline-none focus:ring-2 focus:ring-blue-500 text-left`}
        aria-pressed={selected}
        aria-label={`${label} 추세 카드`}
      >
        {content}
      </button>
    )
  }

  return (
    <div className={`${baseCls} ${stateCls}`} aria-label={`${label} 추세 카드`}>
      {content}
    </div>
  )
}
