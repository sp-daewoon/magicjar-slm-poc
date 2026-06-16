/**
 * SymbolHeader — 종목명·코드·시장·산업 헤더 + 관심종목 토글 + [종목검색으로] 버튼
 *
 * spec §6-2 정합:
 * - 종목명 부재 시 code 그대로 표시 (graceful — spec §10-10)
 * - 산업 부재 시 표시 생략 (instrument 테이블 T-IM1 미완 graceful)
 * - [종목검색으로] 버튼 → 상세 진입 state.returnTo 또는 /instruments
 */
import { useLocation, useNavigate } from 'react-router-dom'
import type { SymbolDto } from '../../api/symbol'
import { WatchlistToggleButton } from '../../components/watchlist/WatchlistToggleButton'
import { useWatchlistToggle } from '../../components/watchlist/useWatchlistToggle'

interface SymbolHeaderProps {
  symbol: Pick<SymbolDto, 'code' | 'name' | 'market' | 'industry'>
}

interface SymbolLocationState {
  returnTo?: string
  returnLabel?: string
}

export function SymbolHeader({ symbol }: SymbolHeaderProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const locationState = location.state as SymbolLocationState | null
  const returnTo = locationState?.returnTo?.startsWith('/') ? locationState.returnTo : '/instruments'
  const returnLabel = locationState?.returnLabel || '종목검색'
  const {
    isWatched,
    isLoading: isWatchlistLoading,
    isMutating: isWatchlistMutating,
    pendingCode,
    addToWatchlist,
    removeFromWatchlist,
  } = useWatchlistToggle()
  const watched = isWatched(symbol.code)
  const togglePending = pendingCode === symbol.code && isWatchlistMutating

  return (
    <div className="flex items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        {/* 종목명 + 코드 */}
        <div className="flex items-baseline gap-2">
          <h1 className="text-xl font-bold text-white">
            {symbol.name ?? symbol.code}
          </h1>
          {symbol.name && (
            <span className="font-mono text-sm text-gray-400 tracking-wider">
              ({symbol.code})
            </span>
          )}
        </div>

        {/* 시장 + 산업 배지 */}
        <div className="flex items-center gap-2">
          {symbol.market && (
            <span className="rounded-md bg-blue-900/40 px-2 py-0.5 text-xs font-medium text-blue-300">
              {symbol.market}
            </span>
          )}
          {symbol.industry && (
            <span className="rounded-md bg-gray-800 px-2 py-0.5 text-xs text-gray-400">
              {symbol.industry}
            </span>
          )}
        </div>
      </div>

      <div className="flex shrink-0 flex-wrap justify-end gap-2">
        <WatchlistToggleButton
          code={symbol.code}
          name={symbol.name}
          isWatched={watched}
          isPending={togglePending}
          disabled={isWatchlistLoading || isWatchlistMutating}
          onAdd={addToWatchlist}
          onRemove={removeFromWatchlist}
        />
        <button
          type="button"
          onClick={() => navigate(returnTo)}
          className="flex h-9 items-center gap-1.5 rounded-lg border border-gray-700 bg-gray-800 px-3 text-sm text-gray-300 transition-colors hover:border-gray-600 hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
          aria-label={`${returnLabel} 화면으로 돌아가기`}
        >
          <svg
            className="h-4 w-4"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
            />
          </svg>
          {returnLabel}으로
        </button>
      </div>
    </div>
  )
}
