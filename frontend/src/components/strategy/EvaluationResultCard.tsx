/**
 * EvaluationResultCard — 전략 평가 결과 카드
 *
 * evaluate 응답 EvaluateResponse 시각화:
 *   BUY   = 파란색
 *   SELL  = 빨간색
 *   HOLD  = 회색
 *
 * "관심종목 추가" 버튼 — ADR 0040 §2 정합
 *   useAddWatchlist (T-W1 기존 hook 재사용)
 *
 * 0건/에러 → "데이터 없음" 명시 (feedback_no_mock_data.md 정합)
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import type { EvaluateResponse } from '../../api/strategy'
import { useAddWatchlist } from '../../api/hooks/useWatchlist'

interface EvaluationResultCardProps {
  result: EvaluateResponse
  /** 평가한 종목 코드 (Watchlist 추가용) */
  code: string
  /** 종목명 (optional) */
  codeName?: string
}

const SIGNAL_CONFIG: Record<
  'BUY' | 'SELL' | 'HOLD',
  { label: string; badge: string; icon: string }
> = {
  BUY: {
    label: '매수',
    badge: 'bg-blue-900/40 text-blue-300 border-blue-800',
    icon: '▲',
  },
  SELL: {
    label: '매도',
    badge: 'bg-red-900/40 text-red-300 border-red-800',
    icon: '▼',
  },
  HOLD: {
    label: '보유',
    badge: 'bg-gray-800 text-gray-300 border-gray-700',
    icon: '—',
  },
}

const UNKNOWN_SIGNAL_CONFIG = {
  label: '알 수 없음',
  badge: 'bg-yellow-900/40 text-yellow-300 border-yellow-800',
  icon: '?',
}

function toFiniteNumber(value: number | string): number {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function formatReasonValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) {
    return value.map(formatReasonValue).filter(Boolean).join(', ')
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .map(([key, nestedValue]) => {
        const formatted = formatReasonValue(nestedValue)
        return formatted ? `${key}: ${formatted}` : ''
      })
      .filter(Boolean)
    return entries.join(' · ')
  }
  return String(value)
}

export function EvaluationResultCard({ result, code, codeName }: EvaluationResultCardProps) {
  const cfg = SIGNAL_CONFIG[result.signal as keyof typeof SIGNAL_CONFIG] ?? UNKNOWN_SIGNAL_CONFIG
  const [watchlistAdded, setWatchlistAdded] = useState(false)
  const { mutate: addWatchlist, isPending: isAdding } = useAddWatchlist()

  const handleAddWatchlist = () => {
    addWatchlist(
      { code },
      {
        onSuccess: () => {
          setWatchlistAdded(true)
        },
        onError: (err) => {
          console.error('관심종목 추가 실패:', err)
        },
      },
    )
  }

  const strengthPercent = Math.min(
    100,
    Math.max(0, Math.round(toFiniteNumber(result.strength) * 100)),
  )
  const reasonText = formatReasonValue(result.reason)
  const evalTimeMs = toFiniteNumber(result.evalTimeMs)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-gray-200">평가 결과</h3>
          {code && (
            <p className="text-xs text-gray-500">
              {codeName ? `${codeName} (${code})` : code}
            </p>
          )}
        </div>
        <span
          className={[
            'rounded-full border px-3 py-0.5 text-lg font-bold',
            cfg.badge,
          ].join(' ')}
        >
          {cfg.icon} {cfg.label}
        </span>
      </div>

      {/* 신호 강도 게이지 */}
      <div className="mb-3">
        <div className="mb-1 flex items-center justify-between">
          <span className="text-xs text-gray-500">신호 강도</span>
          <span className="font-mono text-xs text-white">{strengthPercent}%</span>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-gray-800">
          <div
            className={[
              'h-full rounded-full transition-all',
              result.signal === 'BUY'
                ? 'bg-blue-500'
                : result.signal === 'SELL'
                  ? 'bg-red-500'
                  : 'bg-gray-600',
            ].join(' ')}
            style={{ width: `${strengthPercent}%` }}
          />
        </div>
      </div>

      {/* 평가 근거 */}
      {reasonText && (
        <div className="mb-3 rounded-lg bg-gray-950 px-3 py-2">
          <div className="mb-1 text-xs text-gray-500">평가 근거</div>
          <p className="break-words text-sm text-gray-300">{reasonText}</p>
        </div>
      )}

      {/* 평가 시간 */}
      <div className="mb-3 text-right">
        <span className="text-xs text-gray-600">평가 시간: {evalTimeMs}ms</span>
      </div>

      {/* 관심종목 추가 (ADR 0040 §2 정합) */}
      <button
        className={[
          'w-full rounded-lg px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
          watchlistAdded
            ? 'cursor-default bg-gray-800 text-gray-500'
            : 'bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50',
        ].join(' ')}
        onClick={handleAddWatchlist}
        disabled={watchlistAdded || isAdding}
        aria-label={`${codeName ?? code} 관심종목 추가`}
      >
        {watchlistAdded ? '관심종목에 추가됨' : isAdding ? '추가 중...' : '관심종목 추가'}
      </button>
    </div>
  )
}

/**
 * 데이터 없음 표시 컴포넌트
 * feedback_no_mock_data.md 정합 — backend 0건/에러 시 명시
 */
export function EvaluationEmptyCard() {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-gray-800 bg-gray-900 p-6 text-center">
      <span className="mb-2 text-2xl text-gray-600">—</span>
      <p className="text-sm text-gray-500">데이터 없음</p>
      <p className="mt-1 text-xs text-gray-600">
        해당 종목의 캔들 데이터가 없거나 평가 결과가 없습니다
      </p>
    </div>
  )
}
