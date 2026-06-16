/**
 * CrossSignalBadge — MA cross signal 배지 (GOLDEN·DEAD·NEUTRAL) — T-MARKET R2 §2-1.
 *
 * 강조 효과:
 *   GOLDEN  → emerald + pulse (사용자 주의 환기)
 *   DEAD    → red    + pulse
 *   NEUTRAL → gray (무알림)
 *
 * MarketTrendPage 중앙 패널에서 발생 시 점멸 안내 (CSS animate-pulse 적용).
 */
import type { CrossSignal } from '../../../api/market'

interface Props {
  signal: CrossSignal
  /** 발생 강조 점멸 적용 여부. default false. */
  pulse?: boolean
}

const STYLES: Record<CrossSignal, { cls: string; text: string }> = {
  GOLDEN: {
    cls: 'bg-emerald-800/80 text-emerald-100 border border-emerald-500',
    text: 'GOLDEN CROSS',
  },
  DEAD: {
    cls: 'bg-red-800/80 text-red-100 border border-red-500',
    text: 'DEAD CROSS',
  },
  NEUTRAL: {
    cls: 'bg-gray-800 text-gray-400 border border-gray-700',
    text: '교차 없음',
  },
}

export function CrossSignalBadge({ signal, pulse = false }: Props) {
  const style = STYLES[signal]
  const isImportant = signal === 'GOLDEN' || signal === 'DEAD'
  const animation = pulse && isImportant ? 'animate-pulse' : ''
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-[11px] font-bold tracking-wide ${style.cls} ${animation}`}
      aria-label={`MA cross ${style.text}`}
    >
      {style.text}
    </span>
  )
}
