/**
 * OrderTriggerBadge — 주문 발사 출처 색상 배지 (ADR 0115 §2-B 키 사슬 무결성, 불변식 #1·#4)
 *
 * 최고 관리자 직접 요구(2026-06-10): 주문/시그널 내역에서 "이 주문이 어떤 출처로 나왔는가"를
 * 한눈에 식별. 0-sentinel 폐지 — "출처를 모르는 주문" 금지.
 *
 * 색상 토큰 LOCK (task spec 정합 — Tailwind 팔레트):
 *   STRATEGY_SIGNAL     → 파랑  "전략 사인"
 *   AUTO_EXIT           → 노랑  "자동 익절"
 *   STOP_LOSS           → 빨강  "손절"
 *   VI_AUTO_EXIT        → 자주  "VI 자동청산"
 *   PARTIAL_TAKE_PROFIT → 주황  "분할 익절"
 *   MANUAL              → 회색  "수동"
 *   EXTERNAL            → 보라  "외부"
 *   null/미분류         → 점선 회색  "미분류" (V80 이전 legacy)
 *
 * trigger_ref_id 가 있으면 title(hover)로 유발 근거 키 노출.
 */
import type { OrderTrigger } from '../../api/order'

interface OrderTriggerBadgeProps {
  /** 발사 출처 enum. null/undefined = legacy 미분류. */
  trigger?: OrderTrigger | null
  /** 유발 근거 실키 (signal id / strategy id 등) — hover title 노출용. */
  triggerRefId?: number | null
  /** 추가 설명 (예: 전략명·사인 id) — hover title 에 합쳐 표시. */
  refLabel?: string
  className?: string
}

const TRIGGER_STYLES: Record<OrderTrigger, { bg: string; label: string; refKind: string }> = {
  STRATEGY_SIGNAL: {
    bg: 'bg-blue-100 text-blue-800 border border-blue-300',
    label: '전략 사인',
    refKind: '사인',
  },
  AUTO_EXIT: {
    bg: 'bg-yellow-100 text-yellow-800 border border-yellow-300',
    label: '자동 익절',
    refKind: '전략',
  },
  STOP_LOSS: {
    bg: 'bg-red-100 text-red-800 border border-red-300',
    label: '손절',
    refKind: '포지션',
  },
  VI_AUTO_EXIT: {
    bg: 'bg-rose-100 text-rose-800 border border-rose-300',
    label: 'VI 자동청산',
    refKind: '전략',
  },
  PARTIAL_TAKE_PROFIT: {
    bg: 'bg-orange-100 text-orange-800 border border-orange-300',
    label: '분할 익절',
    refKind: '전략',
  },
  MANUAL: {
    bg: 'bg-gray-100 text-gray-700 border border-gray-300',
    label: '수동',
    refKind: '',
  },
  EXTERNAL: {
    bg: 'bg-purple-100 text-purple-800 border border-purple-300',
    label: '외부',
    refKind: '',
  },
}

const LEGACY_STYLE = {
  bg: 'bg-gray-100 text-gray-500 border border-dashed border-gray-400',
  label: '미분류',
}

export function OrderTriggerBadge({
  trigger,
  triggerRefId,
  refLabel,
  className = '',
}: OrderTriggerBadgeProps) {
  const style = trigger ? TRIGGER_STYLES[trigger] : LEGACY_STYLE
  const refKind = trigger ? TRIGGER_STYLES[trigger].refKind : ''

  // hover title — 유발 근거 키 + 라벨
  const titleParts: string[] = []
  if (!trigger) {
    titleParts.push('발사 출처 미분류 (V80 이전 legacy 주문)')
  } else if (refLabel) {
    titleParts.push(refLabel)
  } else if (triggerRefId != null && refKind) {
    titleParts.push(`${refKind} #${triggerRefId}`)
  } else if (triggerRefId != null) {
    titleParts.push(`근거 키 #${triggerRefId}`)
  }
  const title = titleParts.length > 0 ? titleParts.join(' · ') : undefined

  return (
    <span
      title={title}
      className={[
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
        style.bg,
        className,
      ].join(' ')}
    >
      {style.label}
      {trigger && triggerRefId != null && refKind && (
        <span className="font-mono text-[10px] opacity-70">#{triggerRefId}</span>
      )}
    </span>
  )
}
