/**
 * CandleHealthBadge — 캔들 수집 건강도 1줄 표시 (readiness P1)
 *
 * 목적: 종목 상세 차트 영역 부근에서 캔들이 stale일 때
 *       "왜 (캔들) 수집이 안 됐는지"를 종목별 1줄로 노출.
 *
 * 표시 규칙 (디스패치 fix 명세 §1):
 *   - FRESH: 미표시 (또는 작은 녹색 점) — 정상은 화면을 어지럽히지 않음.
 *   - STALE: amber 1줄 (reasonDetail).
 *   - NO_DATA: red 1줄.
 *   - MARKET_CLOSED reason: 중립 회색 (status가 STALE이어도 "장 마감"은 정상 상황).
 *   - 조회 실패(404/에러): "수집 상태 확인 불가" 중립 회색 — 가짜 FRESH 금지.
 *
 * 방어적 파싱 (디스패치 LOCK):
 *   - status·reason enum 밖 값이어도 throw 금지.
 *   - 모르는 reason은 reasonDetail 원문을 그대로 표시.
 *   - reasonDetail 비었으면 reason 코드 자체를 fallback 표시.
 *
 * 디자인: 기존 SymbolDetailPage STOMP 배지(border + bg + text 토큰)와 동일 패턴.
 */
import type { CandleHealthDto } from '../../api/symbol'

interface CandleHealthBadgeProps {
  /** 조회 성공 시 데이터. 미조회/로딩 중이면 undefined. */
  data?: CandleHealthDto | null
  /** TanStack Query isError — 404/에러 시 "확인 불가" 표시. */
  isError?: boolean
  /** 최초 로딩 중 여부 (data·error 모두 없을 때). */
  isLoading?: boolean
}

type Tone = 'fresh' | 'neutral' | 'amber' | 'red'

const TONE_CLASS: Record<Tone, string> = {
  fresh: 'border-emerald-800/50 bg-emerald-900/20 text-emerald-400',
  neutral: 'border-gray-700 bg-gray-800/40 text-gray-400',
  amber: 'border-amber-800/50 bg-amber-900/20 text-amber-400',
  red: 'border-red-800/50 bg-red-900/20 text-red-400',
}

/**
 * status·reason → 표시 tone 결정.
 * MARKET_CLOSED는 status와 무관하게 중립(정상 장외) 처리.
 */
function resolveTone(status: string, reason: string): Tone {
  if (reason === 'MARKET_CLOSED') return 'neutral'
  if (status === 'NO_DATA') return 'red'
  if (status === 'STALE') return 'amber'
  if (status === 'FRESH') return 'fresh'
  // 모르는 status — 보수적으로 amber (주의 환기)
  return 'amber'
}

/** reasonDetail이 비었을 때 reason 코드 기반 한국어 fallback. */
function fallbackLabel(reason: string): string {
  switch (reason) {
    case 'OK_REALTIME':
      return '실시간 수집 정상'
    case 'MARKET_CLOSED':
      return '장 마감 — 수집 정상 중단'
    case 'WS_NOT_SUBSCRIBED':
      return '실시간 미구독 상태'
    case 'NO_TICK':
      return '체결(tick) 미수신'
    case 'INSERT_FAILED':
      return '캔들 적재 실패'
    case 'BACKFILL_FAILED':
      return '백필 실패'
    case 'BACKFILL_PERMANENT_GAP':
      return '영구 갭 (수집 불가 구간)'
    default:
      // 모르는 reason — 원문 코드 노출
      return `수집 상태: ${reason}`
  }
}

function ToneIcon({ tone }: { tone: Tone }) {
  if (tone === 'fresh') {
    return <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-400" aria-hidden="true" />
  }
  return (
    <svg
      className="h-4 w-4 shrink-0"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
      />
    </svg>
  )
}

export function CandleHealthBadge({ data, isError, isLoading }: CandleHealthBadgeProps) {
  // 조회 실패 — 가짜 FRESH 금지, "확인 불가" 명시.
  if (isError || (!data && !isLoading)) {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="candle-health-badge"
        data-tone="neutral"
        className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-xs ${TONE_CLASS.neutral}`}
      >
        <ToneIcon tone="neutral" />
        <span>캔들 수집 상태 확인 불가</span>
      </div>
    )
  }

  // 최초 로딩 — 조용히 표시 안 함 (스켈레톤 불필요한 저빈도 위젯).
  if (!data) return null

  const tone = resolveTone(String(data.status), String(data.reason))

  // FRESH 정상은 화면을 어지럽히지 않음 — 작은 점 + 짧은 라벨만.
  if (tone === 'fresh') {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="candle-health-badge"
        data-tone="fresh"
        className={`inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs ${TONE_CLASS.fresh}`}
      >
        <ToneIcon tone="fresh" />
        <span>캔들 실시간 수집 정상</span>
      </div>
    )
  }

  const detail = data.reasonDetail?.trim() || fallbackLabel(String(data.reason))

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="candle-health-badge"
      data-tone={tone}
      data-reason={String(data.reason)}
      className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-xs ${TONE_CLASS[tone]}`}
    >
      <ToneIcon tone={tone} />
      <span className="font-medium">캔들 수집</span>
      <span className="text-gray-500" aria-hidden="true">
        ·
      </span>
      <span>{detail}</span>
    </div>
  )
}
