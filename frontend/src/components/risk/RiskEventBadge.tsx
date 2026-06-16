/**
 * RiskEventBadge — verdict 상태 배지 컴포넌트 (T-RISK Phase 3 — ADR 0067)
 *
 * ALLOWED   → 녹색
 * BLOCKED   → 빨강
 * AUTO_PAUSED → 회색
 */
import type { RiskVerdict } from '../../api/risk'

interface RiskEventBadgeProps {
  verdict: RiskVerdict
  className?: string
}

const VERDICT_STYLES: Record<RiskVerdict, string> = {
  ALLOWED:     'bg-emerald-900/60 text-emerald-300 border border-emerald-700',
  BLOCKED:     'bg-red-900/60 text-red-300 border border-red-700',
  AUTO_PAUSED: 'bg-gray-800/80 text-gray-400 border border-gray-600',
}

const VERDICT_LABELS: Record<RiskVerdict, string> = {
  ALLOWED:     '허용',
  BLOCKED:     '차단',
  AUTO_PAUSED: '자동 매수차단',
}

export function RiskEventBadge({ verdict, className = '' }: RiskEventBadgeProps) {
  return (
    <span
      className={[
        'inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold',
        VERDICT_STYLES[verdict],
        className,
      ].join(' ')}
    >
      {VERDICT_LABELS[verdict]}
    </span>
  )
}
