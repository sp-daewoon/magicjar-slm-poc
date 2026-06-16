/**
 * PaperBadge — "모의(페이퍼) · 실거래 아님" 명시 배지 (ADR 0122 §6 UX 가드 LOCK)
 *
 * 페이퍼 전략·포지션·체결·손익 표시 전부에 일관 적용한다.
 * 사용자가 실 계좌 화면(POSITIONS·ORDERS)과 1초도 혼동하지 않도록 amber 계열 구분 색상 고정.
 *
 * ### 가드 (방향성 보존)
 * - 실 계좌 화면(emerald/blue 계열)과 명확히 다른 amber 톤 — 시각 회귀 기준.
 * - "가짜 데이터" 표현 금지(§6). "모의 매매"는 실 시세 기반 실제 시뮬이므로 mock 아님.
 */

interface PaperBadgeProps {
  /** 'sm' (인라인 칩) | 'md' (섹션 헤더) — default 'sm' */
  size?: 'sm' | 'md'
  /** 추가 클래스 */
  className?: string
}

export function PaperBadge({ size = 'sm', className = '' }: PaperBadgeProps) {
  const sizeCls =
    size === 'md' ? 'px-2.5 py-1 text-xs gap-1.5' : 'px-1.5 py-0.5 text-[10px] gap-1'
  return (
    <span
      className={[
        'inline-flex items-center rounded-full border border-amber-600/60 bg-amber-900/40 font-semibold text-amber-300',
        sizeCls,
        className,
      ].join(' ')}
      title="모의(페이퍼) 트레이딩 — 가상 자본 기준 시뮬이며 실제 계좌 주문이 아닙니다."
      aria-label="모의 페이퍼, 실거래 아님"
    >
      <span aria-hidden>◈</span>
      모의 · 실거래 아님
    </span>
  )
}
