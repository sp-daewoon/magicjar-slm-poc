/**
 * 실시간 구독 owner 문자열 인간화 (/admin/realtime 구독 테이블).
 *
 * 최고 관리자 불만: "owner 문자열(strategy:strategy:86) 의미 불명".
 *
 * backend 계약 (market-data — 정규화된 단일 prefix):
 *   strategy:86 · detail:74683223 · holding:<id> · pending_buy:<id> · 호가 DETAIL 은 detail
 * 단, 롤링 배포 중 legacy 이중 prefix(`strategy:strategy:86`)가 올 수 있어
 * 표시 전 중복 prefix collapse 를 거친다.
 */

/** badge 색상 분류 (Tailwind class 묶음) */
export type OwnerBadgeTone =
  | 'strategy'
  | 'detail'
  | 'holding'
  | 'vi'
  | 'pending_buy'
  | 'screener'
  | 'default'

export interface OwnerView {
  /** 배지에 표시할 한국어 라벨 (예: "전략 #86") */
  label: string
  /** 색상 분류 */
  tone: OwnerBadgeTone
  /** hover title — 원본 + 한 줄 설명 */
  title: string
}

/** Tailwind 배지 색상 (tone → class) */
export const OWNER_BADGE_CLASS: Record<OwnerBadgeTone, string> = {
  strategy: 'bg-violet-950 text-violet-300 border-violet-800',
  detail: 'bg-blue-950 text-blue-300 border-blue-800',
  holding: 'bg-emerald-950 text-emerald-300 border-emerald-800',
  vi: 'bg-cyan-950 text-cyan-300 border-cyan-800',
  pending_buy: 'bg-orange-950 text-orange-300 border-orange-800',
  screener: 'bg-amber-950 text-amber-300 border-amber-800',
  default: 'bg-gray-800 text-gray-300 border-gray-700',
}

/**
 * 중복 prefix collapse — `strategy:strategy:86` → `strategy:86`.
 * 롤링 배포 중 legacy 이중 prefix 흡수. 같은 prefix 가 연속되면 1개로 접는다.
 */
export function collapseOwnerPrefix(raw: string): string {
  const parts = raw.split(':')
  if (parts.length <= 1) return raw
  const collapsed: string[] = []
  for (const p of parts) {
    // 직전과 같은 토큰이면 중복으로 보고 접는다 (strategy:strategy → strategy)
    if (collapsed.length > 0 && collapsed[collapsed.length - 1] === p) continue
    collapsed.push(p)
  }
  return collapsed.join(':')
}

/** owner legend 항목 (테이블 하단 범례) */
export interface OwnerLegendEntry {
  tone: OwnerBadgeTone
  label: string
  desc: string
}

export const OWNER_LEGEND: OwnerLegendEntry[] = [
  { tone: 'strategy', label: '전략 #N', desc: '전략이 ACTIVE 상태라 체결가를 추적합니다.' },
  { tone: 'detail', label: '종목 상세', desc: '종목 상세 화면 진입 중인 종목입니다.' },
  { tone: 'holding', label: '보유 감시', desc: '보유 포지션 감시를 위해 체결가를 유지합니다.' },
  { tone: 'vi', label: 'VI 보유 감시', desc: '보유 포지션의 장운영/VI 상태를 추적합니다.' },
  { tone: 'pending_buy', label: '매수 대기', desc: '매수 주문 대기 중 체결가를 추적합니다.' },
  { tone: 'screener', label: '스크리너 후보', desc: '스크리너 후보 평가/관찰 수요입니다.' },
]

/**
 * owner 문자열 → 인간화 view.
 *
 * @example ownerView('strategy:86')        // { label: '전략 #86', tone: 'strategy', ... }
 * @example ownerView('detail:74683223')    // { label: '종목 상세', tone: 'detail', ... }
 * @example ownerView('strategy:strategy:86')// collapse 후 '전략 #86'
 */
export function ownerView(raw: string): OwnerView {
  const normalized = collapseOwnerPrefix(raw.trim())
  const colon = normalized.indexOf(':')
  const prefix = (colon >= 0 ? normalized.slice(0, colon) : normalized).toLowerCase()
  const arg = colon >= 0 ? normalized.slice(colon + 1) : ''

  switch (prefix) {
    case 'strategy':
      return {
        label: arg ? `전략 #${arg}` : '전략',
        tone: 'strategy',
        title: `${raw} — 전략 #${arg || '?'}이 ACTIVE 상태라 체결가를 추적합니다.`,
      }
    case 'detail':
      return {
        label: '종목 상세',
        tone: 'detail',
        title: `${raw} — 종목 상세 화면 진입 중인 종목입니다.`,
      }
    case 'holding':
      return {
        label: '보유 감시',
        tone: 'holding',
        title: `${raw} — 보유 포지션 감시를 위해 체결가를 유지합니다.`,
      }
    case 'vi':
      return {
        label: arg === 'held' ? 'VI 보유 감시' : 'VI 감시',
        tone: 'vi',
        title: `${raw} — 보유 포지션의 장운영/VI 상태를 추적합니다.`,
      }
    case 'pending_buy':
      return {
        label: '매수 대기',
        tone: 'pending_buy',
        title: `${raw} — 매수 주문 대기 중 체결가를 추적합니다.`,
      }
    case 'watchlist':
      return {
        label: '관심목록(구형)',
        tone: 'default',
        title: `${raw} — 현재 WS owner로 사용하지 않는 구형 값입니다.`,
      }
    case 'screener':
      return {
        label: '스크리너 후보',
        tone: 'screener',
        title: `${raw} — 스크리너 후보 평가/관찰 수요입니다.`,
      }
    default:
      return {
        label: normalized || '(owner 없음)',
        tone: 'default',
        title: raw,
      }
  }
}
