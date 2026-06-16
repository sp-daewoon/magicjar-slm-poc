/**
 * BacktestKpiPanel — KPI 5건 카드 표시 (T-BT R3)
 *
 * 표시 항목:
 *   - 총수익률 (totalReturn)
 *   - 승률 (winRate)
 *   - MDD (mdd / maxDrawdown)
 *   - Sharpe 비율 (sharpe / sharpeRatio)
 *   - 거래 수 (tradeCount / totalTrades)
 *
 * feedback_no_mock_data.md 정합: 값 없으면 "—" 표시
 * 룰 5: LLM provider import 0건
 */
import type { BacktestRunDetailDto } from '../../api/backtest'

interface KpiData {
  totalReturn: string | null
  winRate: string | null
  mdd: string | null
  sharpe: string | null
  tradeCount: number | null
}

interface BacktestKpiPanelProps {
  kpi: KpiData
}

/** BigDecimal string → 퍼센트 포맷 (0.15 → 15.00%) */
function fmtPct(v: string | null): string {
  if (v === null || v === undefined) return '—'
  const n = parseFloat(v)
  if (isNaN(n)) return '—'
  const sign = n > 0 ? '+' : ''
  return `${sign}${(n * 100).toFixed(2)}%`
}

/** BigDecimal string → 소수점 4자리 */
function fmtDecimal(v: string | null): string {
  if (v === null || v === undefined) return '—'
  const n = parseFloat(v)
  if (isNaN(n)) return '—'
  return n.toFixed(4)
}

/** 수익률 색상 클래스 */
function returnColor(v: string | null): string {
  if (!v) return 'text-gray-300'
  const n = parseFloat(v)
  if (n > 0) return 'text-emerald-400'
  if (n < 0) return 'text-red-400'
  return 'text-gray-300'
}

interface KpiCardProps {
  label: string
  value: string
  valueClass?: string
  subLabel?: string
}

function KpiCard({ label, value, valueClass = 'text-gray-200', subLabel }: KpiCardProps) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border border-gray-800 bg-gray-900 px-4 py-3">
      <span className="text-xs text-gray-500">{label}</span>
      <span className={`text-xl font-bold tabular-nums ${valueClass}`}>{value}</span>
      {subLabel && <span className="text-xs text-gray-600">{subLabel}</span>}
    </div>
  )
}

export function BacktestKpiPanel({ kpi }: BacktestKpiPanelProps) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      <KpiCard
        label="총수익률"
        value={fmtPct(kpi.totalReturn)}
        valueClass={returnColor(kpi.totalReturn)}
      />
      <KpiCard
        label="승률"
        value={fmtPct(kpi.winRate)}
        valueClass="text-blue-300"
      />
      <KpiCard
        label="MDD"
        value={kpi.mdd ? fmtPct(kpi.mdd) : '—'}
        valueClass="text-orange-400"
        subLabel="최대 낙폭"
      />
      <KpiCard
        label="Sharpe"
        value={fmtDecimal(kpi.sharpe)}
        valueClass="text-purple-300"
        subLabel="샤프 비율"
      />
      <KpiCard
        label="총 거래"
        value={kpi.tradeCount !== null ? String(kpi.tradeCount) : '—'}
        valueClass="text-gray-200"
        subLabel="거래 횟수"
      />
    </div>
  )
}

/** BacktestRunDetailDto에서 KpiData 추출 */
export function kpiFromDetail(detail: BacktestRunDetailDto): KpiData {
  return {
    totalReturn: detail.totalReturn,
    winRate: detail.winRate,
    mdd: detail.mdd,
    sharpe: detail.sharpe,
    tradeCount: detail.tradeCount,
  }
}
