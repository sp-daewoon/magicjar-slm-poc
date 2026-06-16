import type { ReactNode } from 'react'
import {
  buildRiskPlanLevels,
  formatRiskPlanPct,
  formatRiskPlanPrice,
  parsePositiveNumber,
} from './riskPlanDisplay'

interface RiskPlanPreviewPanelProps {
  stopLossPct: number | string | null | undefined
  takeProfitPct: number | string | null | undefined
  entryPrice?: number | string | null
  title?: string
  actionSlot?: ReactNode
  className?: string
}

export function RiskPlanPreviewPanel({
  stopLossPct,
  takeProfitPct,
  entryPrice,
  title = 'RiskPlan',
  actionSlot,
  className = '',
}: RiskPlanPreviewPanelProps) {
  const levels = buildRiskPlanLevels(entryPrice, stopLossPct, takeProfitPct)
  const stopPct = parsePositiveNumber(stopLossPct)
  const takePct = parsePositiveNumber(takeProfitPct)
  const hasPlan = stopPct !== null && takePct !== null
  const ratioLabel =
    levels && levels.rewardRiskRatio < 1
      ? `손익비 1:${levels.rewardRiskRatio.toFixed(2)} · 단타형`
      : levels
        ? `손익비 1:${levels.rewardRiskRatio.toFixed(2)}`
        : '매수 체결가 기준'

  return (
    <section className={['space-y-3', className].filter(Boolean).join(' ')} aria-label={title}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-xs font-semibold text-gray-200">{title}</div>
          <div className="mt-0.5 text-[11px] text-gray-500">
            {ratioLabel}
          </div>
        </div>
        {actionSlot}
      </div>

      <div className="grid grid-cols-3 gap-2 text-xs">
        <RiskMetric
          label="손절"
          value={`-${formatRiskPlanPct(stopLossPct)}%`}
          sub={levels ? formatRiskPlanPrice(levels.stopPrice) : '체결가 하단'}
          tone="risk"
        />
        <RiskMetric
          label="진입"
          value={levels ? formatRiskPlanPrice(levels.entryPrice) : 'BUY'}
          sub={levels ? '미리보기 기준가' : '체결가'}
          tone="entry"
        />
        <RiskMetric
          label="익절"
          value={`+${formatRiskPlanPct(takeProfitPct)}%`}
          sub={levels ? formatRiskPlanPrice(levels.takePrice) : '체결가 상단'}
          tone="reward"
        />
      </div>

      <div className="relative h-7 rounded-md bg-gray-950/70 px-2" aria-hidden="true">
        <div className="absolute left-4 right-4 top-1/2 h-0.5 -translate-y-1/2 bg-gradient-to-r from-red-500 via-amber-400 to-emerald-400" />
        <RiskMarker left="8%" color="bg-red-400" />
        <RiskMarker left="50%" color="bg-amber-300" />
        <RiskMarker left="92%" color="bg-emerald-400" />
      </div>

      {!hasPlan && (
        <p className="text-xs text-red-300" role="alert">
          손절과 익절을 0보다 크게 입력해야 합니다.
        </p>
      )}
    </section>
  )
}

function RiskMetric({
  label,
  value,
  sub,
  tone,
}: {
  label: string
  value: string
  sub: string
  tone: 'risk' | 'entry' | 'reward'
}) {
  const toneClass =
    tone === 'risk'
      ? 'text-red-300'
      : tone === 'reward'
        ? 'text-emerald-300'
        : 'text-amber-200'

  return (
    <div className="min-w-0 rounded-md bg-gray-950/60 px-2 py-2">
      <div className="text-[11px] text-gray-500">{label}</div>
      <div className={`mt-0.5 truncate font-semibold tabular-nums ${toneClass}`} title={value}>
        {value}
      </div>
      <div className="mt-0.5 truncate text-[11px] text-gray-500" title={sub}>
        {sub}
      </div>
    </div>
  )
}

function RiskMarker({ left, color }: { left: string; color: string }) {
  return (
    <span
      className={`absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-gray-950 ${color}`}
      style={{ left }}
    />
  )
}
