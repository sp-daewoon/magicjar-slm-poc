/**
 * WatchlistBulkRegisterButton — 선택된 종목 Watchlist 일괄 등록 버튼
 *
 * ADR 0071 §8 "Watchlist 일괄 등록" 정합.
 * 성공 후 토스트 결과 표시 (성공 N건 / 실패 M건)
 */
import type { BulkAddResult } from '../hooks/useBulkAddWatchlist'

export type { BulkAddResult }

interface Props {
  selectedCodes: Set<string>
  isPending: boolean
  onRegister: () => void
  lastResult?: BulkAddResult | null
}

export function WatchlistBulkRegisterButton({
  selectedCodes,
  isPending,
  onRegister,
  lastResult,
}: Props) {
  const count = selectedCodes.size

  return (
    <div className="flex flex-col items-end gap-1">
      {/* 등록 결과 피드백 */}
      {lastResult && (
        <div className="text-xs">
          {lastResult.succeeded.length > 0 && (
            <span className="text-emerald-400">
              {lastResult.succeeded.length}건 등록 완료
            </span>
          )}
          {lastResult.failed.length > 0 && (
            <span className="ml-2 text-red-400">
              {lastResult.failed.length}건 실패 (중복 등)
            </span>
          )}
        </div>
      )}
      <button
        type="button"
        disabled={count === 0 || isPending}
        onClick={onRegister}
        className={[
          'rounded-lg px-4 py-2 text-sm font-semibold transition-colors',
          count > 0 && !isPending
            ? 'bg-emerald-600 text-white hover:bg-emerald-700'
            : 'cursor-not-allowed bg-gray-700 text-gray-500',
        ].join(' ')}
        aria-busy={isPending}
      >
        {isPending
          ? '등록 중...'
          : count > 0
            ? `Watchlist에 ${count}개 등록`
            : 'Watchlist 등록'}
      </button>
    </div>
  )
}
