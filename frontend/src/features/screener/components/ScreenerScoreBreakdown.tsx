/**
 * ScreenerScoreBreakdown — 점수 구성 4 기준 시각화
 *
 * ADR 0071 §2-5 E-1 랭킹 4 기준 가중치 정합:
 *   signal (0.40) · volume (0.30) · atr (0.20) · marketCap (0.10)
 *
 * 각 기준별 contribution bar 표시 (normalized 0~1 × weight)
 * 외부 chart 라이브러리 의존 없이 단순 progress bar 구현
 */
import type { ScreenerScoreComponentsDto } from '../api/screenerApi'

interface Criterion {
  key: keyof ScreenerScoreComponentsDto
  label: string
  weight: number
  color: string
}

const CRITERIA: Criterion[] = [
  { key: 'signal', label: '시그널', weight: 0.4, color: 'bg-emerald-500' },
  { key: 'volume', label: '거래대금', weight: 0.3, color: 'bg-blue-500' },
  { key: 'atr', label: '변동성(ATR)', weight: 0.2, color: 'bg-yellow-500' },
  { key: 'marketCap', label: '시총', weight: 0.1, color: 'bg-purple-500' },
]

interface Props {
  scoreComponents: ScreenerScoreComponentsDto
  /** compact 모드 — 테이블 셀 내 인라인 표시 시 true */
  compact?: boolean
}

export function ScreenerScoreBreakdown({ scoreComponents, compact = false }: Props) {
  if (compact) {
    // compact: 세로 bar 4개 인라인
    return (
      <div className="flex items-end gap-0.5" aria-label="점수 구성" style={{ height: 16 }}>
        {CRITERIA.map((c) => {
          const value = scoreComponents[c.key] ?? 0
          const heightPct = Math.max(4, Math.round(value * 100))
          return (
            <div
              key={c.key}
              className={`w-2 rounded-sm ${c.color} opacity-80`}
              style={{ height: `${heightPct}%` }}
              title={`${c.label}: ${(value * 100).toFixed(0)}%`}
            />
          )
        })}
      </div>
    )
  }

  // 전체 모드: 라벨 + 가로 progress bar
  return (
    <div className="space-y-2" aria-label="점수 구성 상세">
      {CRITERIA.map((c) => {
        const value = scoreComponents[c.key] ?? 0
        const pct = Math.round(value * 100)
        return (
          <div key={c.key} className="space-y-0.5">
            <div className="flex justify-between text-xs text-gray-400">
              <span>
                {c.label}
                <span className="ml-1 text-gray-600">(×{c.weight})</span>
              </span>
              <span className="tabular-nums text-gray-300">{pct}%</span>
            </div>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-800">
              <div
                className={`h-full rounded-full ${c.color}`}
                style={{ width: `${pct}%` }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}
