/**
 * ExecutionTable — 분할 체결 시계열 테이블 (T-O Phase 3 — ADR 0068 §16)
 *
 * 표시 컬럼: executed_at · qty · price · fee · tax
 * 빈 시계열 graceful 안내 (feedback_no_mock_data 정합)
 * footer: 누적 수량 합계 (케이스 3 dogfooding §16-5)
 */
import type { OrderExecutionDto } from '../../api/order'

interface ExecutionTableProps {
  executions: OrderExecutionDto[]
  orderQuantity: number
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * 가격 표시 — backend가 String(BigDecimal toString)으로 직렬화함 (정밀도 보존).
 * parseFloat 후 toLocaleString. 한국 주식 정수 가격이라 정밀도 risk 작음.
 */
function formatPrice(s: string): string {
  const n = parseFloat(s)
  if (Number.isNaN(n)) return '—'
  return n.toLocaleString('ko-KR') + '원'
}

function formatOptional(s: string | null | undefined): string {
  if (s === null || s === undefined) return '—'
  const n = parseFloat(s)
  if (Number.isNaN(n)) return '—'
  return n.toLocaleString('ko-KR') + '원'
}

export function ExecutionTable({ executions, orderQuantity }: ExecutionTableProps) {
  if (executions.length === 0) {
    return (
      <div className="rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-6 text-center">
        <p className="text-sm text-gray-500">체결 내역이 없습니다.</p>
        <p className="mt-1 text-xs text-gray-600">주문 체결 시 이 목록에 표시됩니다.</p>
      </div>
    )
  }

  const totalExecuted = executions.reduce((sum, e) => sum + e.executedQty, 0)
  const isFullyFilled = totalExecuted >= orderQuantity

  return (
    <div className="overflow-hidden rounded-lg border border-gray-800">
      <table className="w-full text-sm">
        <thead className="bg-gray-900 text-xs text-gray-400">
          <tr>
            <th className="px-3 py-2 text-left">체결 시각</th>
            <th className="px-3 py-2 text-right">체결 수량</th>
            <th className="px-3 py-2 text-right">체결 단가</th>
            <th className="px-3 py-2 text-right">수수료</th>
            <th className="px-3 py-2 text-right">세금</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800 bg-gray-950">
          {executions.map((exec) => (
            <tr key={exec.id} className="hover:bg-gray-900/50">
              <td className="px-3 py-2 text-gray-300">{formatDateTime(exec.executedAt)}</td>
              <td className="px-3 py-2 text-right text-white">
                {exec.executedQty.toLocaleString('ko-KR')}주
              </td>
              <td className="px-3 py-2 text-right text-white">
                {formatPrice(exec.executedPrice)}
              </td>
              <td className="px-3 py-2 text-right text-gray-400">{formatOptional(exec.fee)}</td>
              <td className="px-3 py-2 text-right text-gray-400">{formatOptional(exec.tax)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot className="bg-gray-900/80">
          <tr>
            <td className="px-3 py-2 text-xs text-gray-400">합계</td>
            <td
              className={[
                'px-3 py-2 text-right text-xs font-semibold',
                isFullyFilled ? 'text-green-400' : 'text-yellow-400',
              ].join(' ')}
            >
              {totalExecuted.toLocaleString('ko-KR')}주 / {orderQuantity.toLocaleString('ko-KR')}주
              {isFullyFilled && (
                <span className="ml-1 text-green-400">(완료)</span>
              )}
              {!isFullyFilled && (
                <span className="ml-1 text-yellow-400">(부분)</span>
              )}
            </td>
            <td colSpan={3} />
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
