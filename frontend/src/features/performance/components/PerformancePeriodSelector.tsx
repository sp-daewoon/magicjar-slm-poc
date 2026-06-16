/**
 * PerformancePeriodSelector — 기간 단위 선택 버튼 그룹
 *
 * ADR 0072 §2-3 C-1 5 기간 fixed (DAY·WEEK·MONTH·YEAR·ALL)
 */
import { PERIOD_LABELS, type PerformancePeriod } from '../api/performanceApi'

interface PerformancePeriodSelectorProps {
  value: PerformancePeriod
  onChange: (period: PerformancePeriod) => void
}

const PERIODS: PerformancePeriod[] = ['DAY', 'WEEK', 'MONTH', 'YEAR', 'ALL']

export function PerformancePeriodSelector({ value, onChange }: PerformancePeriodSelectorProps) {
  return (
    <div className="flex items-center gap-1 rounded-lg border border-gray-800 bg-gray-900/60 p-1" role="group" aria-label="성과 기간 선택">
      {PERIODS.map((period) => (
        <button
          key={period}
          type="button"
          onClick={() => onChange(period)}
          className={[
            'rounded-md px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900',
            value === period
              ? 'bg-gray-700 text-white'
              : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
          ].join(' ')}
          aria-pressed={value === period}
        >
          {PERIOD_LABELS[period]}
        </button>
      ))}
    </div>
  )
}
