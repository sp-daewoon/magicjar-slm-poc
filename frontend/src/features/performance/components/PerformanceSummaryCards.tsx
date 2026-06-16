/**
 * PerformanceSummaryCards — 성과 5 지표 카드
 *
 * ADR 0072 §2-3 C-1 5 지표 fixed:
 * - 총 P&L (원 단위 천 단위 콤마)
 * - MDD (%)
 * - 승률 (%)
 * - 평균 손익비
 * - 거래 횟수
 *
 * mock 합성 0 — isLoading 시 스켈레톤, isError 시 오류 표시
 */
import type { PerformanceSummaryDto } from '../api/performanceApi'

interface PerformanceSummaryCardsProps {
  summary: PerformanceSummaryDto | undefined
  isLoading: boolean
  isError: boolean
}

interface KpiCardProps {
  label: string
  value: string
  subtext?: string
  color?: string
}

function KpiCard({ label, value, subtext, color = 'text-gray-100' }: KpiCardProps) {
  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
      {subtext && <p className="mt-1 text-xs text-gray-600">{subtext}</p>}
    </div>
  )
}

function SkeletonCard() {
  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-4">
      <div className="h-3 w-16 animate-pulse rounded bg-gray-800" />
      <div className="mt-3 h-6 w-24 animate-pulse rounded bg-gray-800" />
    </div>
  )
}

export function PerformanceSummaryCards({ summary, isLoading, isError }: PerformanceSummaryCardsProps) {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-gray-800 bg-gray-900 p-6 text-center text-sm text-red-400">
        성과 데이터를 불러오는 중 오류가 발생했습니다.
      </div>
    )
  }

  if (!summary) {
    return (
      <div className="rounded-lg border border-gray-800 bg-gray-900 p-6 text-center">
        <p className="text-sm text-gray-500">성과 데이터가 없습니다.</p>
        <p className="mt-1 text-xs text-gray-600">
          매매가 발생하면 성과 통계가 표시됩니다.
        </p>
      </div>
    )
  }

  const pnlColor =
    summary.totalPnl > 0
      ? 'text-emerald-400'
      : summary.totalPnl < 0
        ? 'text-red-400'
        : 'text-gray-400'

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {/* 총 P&L */}
      <KpiCard
        label="총 손익"
        value={`${summary.totalPnl > 0 ? '+' : ''}${summary.totalPnl.toLocaleString('ko-KR')}원`}
        color={pnlColor}
      />

      {/* MDD */}
      <KpiCard
        label="최대 낙폭 (MDD)"
        value={`${summary.mddPct.toFixed(2)}%`}
        color={summary.mddPct > 0 ? 'text-red-400' : 'text-gray-400'}
      />

      {/* 승률 */}
      <KpiCard
        label="승률"
        value={`${summary.winRatePct.toFixed(1)}%`}
        color={summary.winRatePct >= 50 ? 'text-emerald-400' : 'text-yellow-400'}
      />

      {/* 평균 손익비 */}
      <KpiCard
        label="평균 손익비"
        value={summary.profitLossRatio !== null ? summary.profitLossRatio.toFixed(2) : '-'}
        subtext="수익 / abs(손실)"
        color={
          summary.profitLossRatio !== null && summary.profitLossRatio >= 1
            ? 'text-emerald-400'
            : 'text-yellow-400'
        }
      />

      {/* 거래 횟수 */}
      <KpiCard
        label="거래 횟수"
        value={`${summary.tradeCount.toLocaleString('ko-KR')}회`}
      />
    </div>
  )
}
