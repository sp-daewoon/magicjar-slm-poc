/**
 * PnlBadge — 일일 PnL 표시 (DLL 대비 색상 변경) (T-RISK Phase 3 — ADR 0067)
 *
 * - pnl >= 0 → 녹색 (수익)
 * - pnl < 0 && |pnl| < dll → 주황 (손실이지만 DLL 미달)
 * - pnl < 0 && |pnl| >= dll → 빨강 (DLL 초과 — 위험)
 */

interface PnlBadgeProps {
  /** 일일 PnL (원, 음수=손실) */
  pnl: number
  /** 일일 손실 한도 (원, 양수) */
  dll?: number
  className?: string
}

/** 원화 포맷 */
function formatKrw(value: number): string {
  const abs = Math.abs(value)
  const sign = value >= 0 ? '+' : '-'
  return `${sign}${abs.toLocaleString('ko-KR')}원`
}

export function PnlBadge({ pnl, dll, className = '' }: PnlBadgeProps) {
  let colorClass: string
  if (pnl >= 0) {
    colorClass = 'bg-emerald-900/60 text-emerald-300 border border-emerald-700'
  } else if (dll !== undefined && Math.abs(pnl) >= dll) {
    colorClass = 'bg-red-900/60 text-red-300 border border-red-700'
  } else {
    colorClass = 'bg-amber-900/60 text-amber-300 border border-amber-700'
  }

  return (
    <span
      className={[
        'inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold tabular-nums',
        colorClass,
        className,
      ].join(' ')}
      title={dll !== undefined ? `DLL: ${dll.toLocaleString('ko-KR')}원` : undefined}
    >
      {formatKrw(pnl)}
    </span>
  )
}
