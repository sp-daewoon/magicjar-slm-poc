/**
 * MarketIndexStrip — 4 지수 카드 가로 배치 (T-MARKET R2 §2-1 — DashboardPage)
 *
 * KOSPI · KOSDAQ · NASDAQ · S&P 500 추세 카드.
 * useMarketIndex hook으로 polling + STOMP merge.
 * 빈 list (데이터 0건) → "데이터 수집 중" graceful 표시.
 */
import { Link } from 'react-router-dom'
import { useMarketIndex } from '../../../api/hooks/useMarketIndex'
import { DEFAULT_INDEX_CODES, INDEX_LABELS } from '../../../api/market'
import { IndexTrendCard } from './IndexTrendCard'

interface Props {
  /** false 시 비활성 (setup 미완 등). default true. */
  enabled?: boolean
}

export function MarketIndexStrip({ enabled = true }: Props) {
  const { snapshot, isLoading, isError } = useMarketIndex({ enabled })

  return (
    <section
      aria-labelledby="market-index-strip-heading"
      className="rounded-2xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2
          id="market-index-strip-heading"
          className="text-sm font-semibold text-gray-200"
        >
          시장 지수
        </h2>
        <Link
          to="/market/trend"
          className="text-xs text-blue-400 hover:text-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-400 rounded"
          aria-label="시장 추세 상세 보기"
        >
          상세 추세 →
        </Link>
      </div>

      {isLoading ? (
        <div className="flex gap-3 overflow-x-auto">
          {DEFAULT_INDEX_CODES.map((code) => (
            <div
              key={code}
              className="h-[88px] min-w-[180px] flex-1 animate-pulse rounded-xl border border-gray-800 bg-gray-900/60"
            />
          ))}
        </div>
      ) : isError ? (
        <div
          role="alert"
          className="rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2 text-xs text-red-300"
        >
          시장 지수를 불러오는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.
        </div>
      ) : !snapshot || snapshot.indexes.length === 0 ? (
        <div
          role="status"
          className="rounded-lg border border-gray-800 bg-gray-900/50 px-3 py-3 text-xs text-gray-400"
        >
          시장 지수 데이터 수집 중입니다. 시장 개장 시간을 기다려 주세요.
        </div>
      ) : (
        <div className="flex gap-3 overflow-x-auto">
          {/* 4 default 지수 순서 LOCK — backend가 다른 순서로 줄 수도 있으므로 정렬 강제 */}
          {DEFAULT_INDEX_CODES.map((code) => {
            const row = snapshot.indexes.find((i) => i.code === code)
            if (!row) {
              return (
                <div
                  key={code}
                  className="flex min-w-[180px] flex-col gap-1 rounded-xl border border-gray-800 bg-gray-900/60 px-4 py-3"
                >
                  <span className="text-sm font-semibold text-gray-100">{INDEX_LABELS[code]}</span>
                  <span className="text-xs text-gray-500">데이터 없음</span>
                </div>
              )
            }
            return (
              <div key={code} className="flex-1">
                <IndexTrendCard index={row} />
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
