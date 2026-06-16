/**
 * DaytradeScoreBreakdown — 단타 발굴 후보 점수 분해 4 기준 시각화
 *
 * 백엔드 ScreenerScoreBreakdown 4 기준(signal·volume·atr·cap) 가중치 정합:
 *   signal (0.40) · volume(거래대금) (0.30) · atr(변동성) (0.20) · cap(시총) (0.10)
 *
 * 사용자가 "왜 이 종목이 후보인가"를 수치로 이해할 수 있게 각 기준별 기여를 bar로 노출.
 * 외부 chart 라이브러리 의존 없이 단순 progress bar (기존 ScreenerScoreBreakdown idiom).
 *
 * ※ 기존 ScreenerScoreBreakdown 은 'marketCap' key를 읽지만 백엔드 직렬화 key는 'cap' 이다
 *   (domain/ScreenerScoreBreakdown.kt:31). 본 컴포넌트는 'cap' key를 정확히 읽어 시총 bar를 렌더한다.
 */
import type { DaytradeScoreComponentsDto } from '../api/daytradeDiscoverApi'

interface Criterion {
  key: keyof DaytradeScoreComponentsDto
  label: string
  weight: number
  color: string
}

const CRITERIA: Criterion[] = [
  { key: 'signal', label: '시그널', weight: 0.4, color: 'bg-emerald-500' },
  { key: 'volume', label: '거래대금', weight: 0.3, color: 'bg-blue-500' },
  { key: 'atr', label: '변동성(ATR)', weight: 0.2, color: 'bg-yellow-500' },
  { key: 'cap', label: '시총', weight: 0.1, color: 'bg-purple-500' },
]

interface Props {
  scoreComponents: DaytradeScoreComponentsDto
  /** compact 모드 — 테이블 셀 내 인라인 표시 시 true */
  compact?: boolean
}

export function DaytradeScoreBreakdown({ scoreComponents, compact = false }: Props) {
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

  // 전체 모드: 라벨 + 가로 progress bar (가중 기여 = value × weight)
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
