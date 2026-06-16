/**
 * TradeTable — 거래 기록 페이징 테이블 (T-BT R3)
 *
 * 표시 컬럼: ts · side · price · quantity · fee · ruleSignal
 * 페이징: 이전/다음 버튼 (부모에서 page/onPageChange 제어)
 *
 * feedback_no_mock_data.md 정합: trades 0건 → "거래 기록 없음" 안내
 * 룰 5: LLM provider import 0건
 */
import type { BacktestTradeResultDto, BacktestPageDto } from '../../api/backtest'

interface TradeTableProps {
  tradePage: BacktestPageDto<BacktestTradeResultDto>
  page: number
  onPageChange: (page: number) => void
  isLoading?: boolean
}

/** KST 날짜+시간 포맷 */
function formatKst(isoString: string): string {
  const d = new Date(isoString)
  return new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
    .format(d)
    .replace('T', ' ')
}

/** BigDecimal string → 천 단위 콤마 (KRW) */
function fmtKrw(v: string | null): string {
  if (!v) return '—'
  const n = parseFloat(v)
  if (isNaN(n)) return '—'
  return n.toLocaleString('ko-KR')
}

export function TradeTable({ tradePage, page, onPageChange, isLoading }: TradeTableProps) {
  const { content: trades, totalPages, totalElements } = tradePage

  if (trades.length === 0 && !isLoading) {
    return (
      <div className="flex h-32 items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
        <p className="text-sm text-gray-500">거래 기록이 없습니다</p>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-xl border border-gray-800 bg-gray-900">
      {/* 헤더 */}
      <div className="flex items-center justify-between border-b border-gray-800 px-4 py-2.5">
        <h3 className="text-sm font-medium text-gray-300">
          거래 기록{' '}
          <span className="text-xs font-normal text-gray-600">
            (총 {totalElements.toLocaleString('ko-KR')}건)
          </span>
        </h3>
        {totalPages > 1 && (
          <div className="flex items-center gap-1 text-xs text-gray-500">
            <button
              className="rounded px-2 py-0.5 hover:bg-gray-800 disabled:opacity-40"
              disabled={page === 0 || isLoading}
              onClick={() => onPageChange(page - 1)}
            >
              이전
            </button>
            <span>
              {page + 1} / {totalPages}
            </span>
            <button
              className="rounded px-2 py-0.5 hover:bg-gray-800 disabled:opacity-40"
              disabled={page >= totalPages - 1 || isLoading}
              onClick={() => onPageChange(page + 1)}
            >
              다음
            </button>
          </div>
        )}
      </div>

      {/* 테이블 */}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[600px] text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-xs text-gray-500">
              <th className="px-4 py-2 text-left font-medium">체결시각 (KST)</th>
              <th className="px-4 py-2 text-left font-medium">방향</th>
              <th className="px-4 py-2 text-right font-medium">체결가 (원)</th>
              <th className="px-4 py-2 text-right font-medium">수량</th>
              <th className="px-4 py-2 text-right font-medium">수수료+세금</th>
              <th className="px-4 py-2 text-left font-medium">신호 규칙</th>
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr>
                <td colSpan={6} className="py-8 text-center text-xs text-gray-600">
                  불러오는 중...
                </td>
              </tr>
            ) : (
              trades.map((trade) => (
                <tr
                  key={trade.id}
                  className="border-b border-gray-800/50 transition-colors hover:bg-gray-800/30"
                >
                  <td className="px-4 py-2 tabular-nums text-gray-400">
                    {formatKst(trade.ts)}
                  </td>
                  <td className="px-4 py-2">
                    <span
                      className={[
                        'rounded px-1.5 py-0.5 text-xs font-bold',
                        trade.side === 'BUY'
                          ? 'bg-blue-900/50 text-blue-300'
                          : 'bg-red-900/50 text-red-300',
                      ].join(' ')}
                    >
                      {trade.side === 'BUY' ? '매수' : '매도'}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums text-gray-300">
                    {fmtKrw(trade.price)}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums text-gray-300">
                    {trade.quantity.toLocaleString('ko-KR')}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums text-gray-500">
                    {fmtKrw(trade.fee)}
                  </td>
                  <td className="px-4 py-2">
                    {trade.ruleSignal ? (
                      <code className="rounded bg-gray-800 px-1.5 py-0.5 text-xs text-gray-400">
                        {trade.ruleSignal}
                      </code>
                    ) : (
                      <span className="text-xs text-gray-700">—</span>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
