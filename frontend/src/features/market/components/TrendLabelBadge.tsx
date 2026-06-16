/**
 * TrendLabelBadge — 추세 라벨 배지 (UP·SIDEWAYS·DOWN) — T-MARKET R2 §2-1.
 *
 * 색 코드 — 사용자 직관:
 *   UP        → emerald (강세)
 *   SIDEWAYS  → amber  (중립)
 *   DOWN      → red    (약세)
 */
import type { TrendLabel } from '../../../api/market'

interface Props {
  label: TrendLabel
  /** 작은 크기 strip 카드용 (default false=중간). */
  compact?: boolean
}

const STYLES: Record<TrendLabel, { cls: string; text: string }> = {
  UP: {
    cls: 'bg-emerald-900/60 text-emerald-300 border border-emerald-700',
    text: '상승',
  },
  SIDEWAYS: {
    cls: 'bg-amber-900/40 text-amber-300 border border-amber-700',
    text: '횡보',
  },
  DOWN: {
    cls: 'bg-red-900/60 text-red-300 border border-red-700',
    text: '하락',
  },
}

export function TrendLabelBadge({ label, compact = false }: Props) {
  const style = STYLES[label]
  const size = compact ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-0.5 text-xs'
  return (
    <span
      className={`inline-flex items-center rounded-full font-semibold ${size} ${style.cls}`}
      aria-label={`추세 ${style.text}`}
    >
      {style.text}
    </span>
  )
}
