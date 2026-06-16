/**
 * SectorRankingPanel — todayTop 5 / longTop 5 탭 (T-MARKET R2 §2-1)
 *
 * MarketTrendPage 우측 패널.
 * 탭 토글: todayTop (오늘 강세) / longTop (장기 강세 RS+우상향 fit).
 * 섹터 클릭 → onClick(sectorCode) 콜백 (종목 리스트 펼침은 향후 확장 슬롯).
 */
import { useState } from 'react'
import { toNumberOrNull, type SectorRankEntryDto, type SectorRankingResponse } from '../../../api/market'

interface Props {
  ranking: SectorRankingResponse | undefined
  isLoading?: boolean
  isError?: boolean
  /** 섹터 클릭 시 콜백 — 종목 리스트 펼침 (향후 확장). */
  onSelectSector?: (sectorCode: string, sectorName: string) => void
}

type Tab = 'todayTop' | 'longTop'

function formatChangeRate(value: number | null): { text: string; cls: string } {
  if (value === null) return { text: '-', cls: 'text-gray-500' }
  const sign = value > 0 ? '+' : ''
  const cls = value > 0 ? 'text-emerald-400' : value < 0 ? 'text-red-400' : 'text-gray-400'
  return { text: `${sign}${value.toFixed(2)}%`, cls }
}

function formatTradeValue(value: number | null): string {
  if (value === null) return '-'
  // 억 단위 (1억=10^8) — 한국 시장 관습.
  const eok = value / 100_000_000
  if (eok >= 10000) return `${(eok / 10000).toFixed(1)}조`
  if (eok >= 1) return `${Math.round(eok).toLocaleString('ko-KR')}억`
  return `${value.toLocaleString('ko-KR')}원`
}

function SectorRow({
  entry,
  rank,
  onClick,
}: {
  entry: SectorRankEntryDto
  rank: number
  onClick?: () => void
}) {
  const change = formatChangeRate(toNumberOrNull(entry.changeRate))
  const trade = formatTradeValue(toNumberOrNull(entry.tradeValue))
  const inner = (
    <>
      <span className="w-5 shrink-0 text-center text-xs font-bold text-gray-500 tabular-nums">
        {rank}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-sm font-medium text-gray-100">{entry.sectorName}</span>
          <span className={`text-sm font-medium tabular-nums ${change.cls}`}>{change.text}</span>
        </div>
        <div className="mt-0.5 flex items-center justify-between gap-2 text-[11px] text-gray-500">
          <span className="truncate" title={entry.metric}>
            {entry.metric}
          </span>
          <span className="shrink-0 tabular-nums">{trade}</span>
        </div>
      </div>
    </>
  )

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className="flex w-full items-start gap-2 rounded-lg border border-transparent px-2 py-2 text-left transition-colors hover:border-gray-700 hover:bg-gray-800/50 focus:outline-none focus:ring-2 focus:ring-blue-500"
        aria-label={`섹터 ${entry.sectorName} 선택`}
      >
        {inner}
      </button>
    )
  }
  return <div className="flex w-full items-start gap-2 px-2 py-2">{inner}</div>
}

export function SectorRankingPanel({
  ranking,
  isLoading = false,
  isError = false,
  onSelectSector,
}: Props) {
  const [tab, setTab] = useState<Tab>('todayTop')

  const list = ranking ? (tab === 'todayTop' ? ranking.todayTop : ranking.longTop) : []

  return (
    <section
      aria-labelledby="sector-ranking-heading"
      className="flex flex-col gap-3 rounded-2xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 id="sector-ranking-heading" className="text-sm font-semibold text-gray-200">
          섹터 ranking
        </h2>
        {ranking && (
          <span className="text-[10px] text-gray-500">
            MA {ranking.periodsUsed.join('·')} · RS {ranking.rsPeriod}
          </span>
        )}
      </div>

      {/* 탭 */}
      <div role="tablist" aria-label="ranking 타입" className="flex gap-1 rounded-lg bg-gray-900 p-1">
        <button
          role="tab"
          aria-selected={tab === 'todayTop'}
          onClick={() => setTab('todayTop')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
            tab === 'todayTop'
              ? 'bg-blue-600 text-white'
              : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200'
          }`}
        >
          오늘 강세
        </button>
        <button
          role="tab"
          aria-selected={tab === 'longTop'}
          onClick={() => setTab('longTop')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
            tab === 'longTop'
              ? 'bg-blue-600 text-white'
              : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200'
          }`}
        >
          장기 강세
        </button>
      </div>

      {/* 본문 */}
      {isLoading ? (
        <div className="space-y-1">
          {[0, 1, 2, 3, 4].map((i) => (
            <div key={i} className="h-12 animate-pulse rounded border border-gray-800 bg-gray-900" />
          ))}
        </div>
      ) : isError ? (
        <div
          role="alert"
          className="rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2 text-xs text-red-300"
        >
          섹터 ranking을 불러오는 중 오류가 발생했습니다.
        </div>
      ) : list.length === 0 ? (
        <div
          role="status"
          className="rounded-lg border border-gray-800 bg-gray-900/50 px-3 py-3 text-xs text-gray-400"
        >
          섹터 ranking 데이터가 아직 수집되지 않았습니다.
        </div>
      ) : (
        <div className="space-y-0.5" role="tabpanel" aria-labelledby="sector-ranking-heading">
          {list.map((entry, idx) => (
            <SectorRow
              key={entry.sectorCode}
              entry={entry}
              rank={idx + 1}
              onClick={
                onSelectSector ? () => onSelectSector(entry.sectorCode, entry.sectorName) : undefined
              }
            />
          ))}
        </div>
      )}
    </section>
  )
}
