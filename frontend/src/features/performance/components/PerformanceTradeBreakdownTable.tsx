/**
 * PerformanceTradeBreakdownTable — 총 손익 상세 근거
 *
 * 매도 체결별 실현손익을 표시한다.
 */
import type { PerformanceTradeBreakdownDto, PerformanceTradeDto } from '../api/performanceApi'

interface PerformanceTradeBreakdownTableProps {
  breakdown: PerformanceTradeBreakdownDto | undefined
  isLoading: boolean
  isError: boolean
}

const modeLabel: Record<PerformanceTradeDto['mode'], string> = {
  MOCK: '모의',
  REAL: '실전',
}

const sourceLabel: Record<PerformanceTradeDto['source'], string> = {
  AUTO: '자동',
  MANUAL: '수동',
}

function formatWon(value: number, signed = false): string {
  const prefix = signed && value > 0 ? '+' : ''
  return `${prefix}${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}원`
}

function formatNumber(value: number, maximumFractionDigits = 4): string {
  return value.toLocaleString('ko-KR', { maximumFractionDigits })
}

function formatKstMinute(value: string): string {
  return new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
    .format(new Date(value))
    .replace('T', ' ')
}

function SkeletonRows() {
  return (
    <>
      {Array.from({ length: 3 }).map((_, index) => (
        <tr key={index} className="border-b border-gray-800">
          {Array.from({ length: 8 }).map((__, cellIndex) => (
            <td key={cellIndex} className="px-3 py-3">
              <div className="h-3 w-full animate-pulse rounded bg-gray-800" />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

export function PerformanceTradeBreakdownTable({
  breakdown,
  isLoading,
  isError,
}: PerformanceTradeBreakdownTableProps) {
  const pnlColor =
    (breakdown?.totalRealizedPnl ?? 0) > 0
      ? 'text-emerald-400'
      : (breakdown?.totalRealizedPnl ?? 0) < 0
        ? 'text-red-400'
        : 'text-gray-400'

  return (
    <section className="rounded-lg border border-gray-800 bg-gray-900">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-800 px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold text-gray-200">실현손익 상세</h2>
          <p className="mt-0.5 text-xs text-gray-500">
            {breakdown
              ? `${breakdown.periodStart} ~ ${breakdown.periodEnd}`
              : '기간 계산 중'}
          </p>
        </div>
        <div className="text-right">
          <p className={`text-sm font-semibold tabular-nums ${pnlColor}`}>
            {formatWon(breakdown?.totalRealizedPnl ?? 0, true)}
          </p>
          <p className="mt-0.5 text-xs text-gray-500">
            {(breakdown?.tradeCount ?? 0).toLocaleString('ko-KR')}건
          </p>
        </div>
      </div>

      {isError ? (
        <div className="px-4 py-8 text-center text-sm text-red-400">
          실현손익 상세를 불러오는 중 오류가 발생했습니다.
        </div>
      ) : !isLoading && breakdown && breakdown.items.length === 0 ? (
        <div className="px-4 py-8 text-center text-sm text-gray-500">
          실현손익이 발생한 매도 체결이 없습니다.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-[980px] w-full text-left text-sm">
            <thead className="border-b border-gray-800 bg-gray-950/50 text-xs uppercase text-gray-500">
              <tr>
                <th className="px-3 py-2 font-medium">체결시각</th>
                <th className="px-3 py-2 font-medium">종목</th>
                <th className="px-3 py-2 font-medium">구분</th>
                <th className="px-3 py-2 text-right font-medium">수량</th>
                <th className="px-3 py-2 text-right font-medium">매도가</th>
                <th className="px-3 py-2 text-right font-medium">평균매수가</th>
                <th className="px-3 py-2 text-right font-medium">비용</th>
                <th className="px-3 py-2 text-right font-medium">실현손익</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <SkeletonRows />
              ) : (
                breakdown?.items.map((item) => {
                  const itemPnlColor =
                    item.realizedPnl > 0
                      ? 'text-emerald-400'
                      : item.realizedPnl < 0
                        ? 'text-red-400'
                        : 'text-gray-400'
                  return (
                    <tr key={item.executionId} className="border-b border-gray-800 last:border-b-0">
                      <td className="whitespace-nowrap px-3 py-3 text-gray-300">
                        {formatKstMinute(item.executedAt)}
                      </td>
                      <td className="px-3 py-3">
                        <div className="font-medium text-gray-100">{item.name ?? item.code}</div>
                        <div className="text-xs text-gray-500">{item.code}</div>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex flex-wrap gap-1">
                          <span className="rounded border border-blue-500/30 bg-blue-500/10 px-1.5 py-0.5 text-xs text-blue-300">
                            {modeLabel[item.mode]}
                          </span>
                          <span className="rounded border border-gray-600 bg-gray-800 px-1.5 py-0.5 text-xs text-gray-300">
                            {sourceLabel[item.source]}
                          </span>
                        </div>
                        <div className="mt-1 text-xs text-gray-600">주문 #{item.orderId}</div>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums text-gray-300">
                        {formatNumber(item.executedQty)}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums text-gray-300">
                        {formatWon(item.executedPrice)}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums text-gray-300">
                        {formatWon(item.avgBuyPrice)}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums text-gray-500">
                        {formatWon(item.fee + item.tax)}
                      </td>
                      <td className={`px-3 py-3 text-right font-semibold tabular-nums ${itemPnlColor}`}>
                        {formatWon(item.realizedPnl, true)}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
