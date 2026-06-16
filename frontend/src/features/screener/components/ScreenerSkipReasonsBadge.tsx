/**
 * ScreenerSkipReasonsBadge — skip 사유 매트릭스 badge 표시
 *
 * ADR 0071 §2-7 G-1 graceful skip 4종 한국어 label:
 *   CandleMissing      → "캔들 미수집"
 *   CandleInsufficient → "캔들 부족"
 *   EvalTimeout        → "DSL 평가 timeout"
 *   EvalError          → "DSL 평가 오류"
 *
 * skippedReasons 오브젝트가 비어 있으면 렌더 0.
 */

/** skip 사유 키 → 한국어 label 매핑 */
const SKIP_LABEL_MAP: Record<string, string> = {
  CandleMissing: '캔들 미수집',
  CandleInsufficient: '캔들 부족',
  EvalTimeout: 'DSL 평가 timeout',
  EvalError: 'DSL 평가 오류',
}

/** badge 색상 (사유 유형별) */
const SKIP_COLOR_MAP: Record<string, string> = {
  CandleMissing: 'bg-gray-700 text-gray-300',
  CandleInsufficient: 'bg-yellow-900/50 text-yellow-300',
  EvalTimeout: 'bg-orange-900/50 text-orange-300',
  EvalError: 'bg-red-900/50 text-red-300',
}

interface Props {
  /** skip 사유별 카운트 — key: SkipReasonKey 또는 한국어 label */
  reasons: Record<string, number>
}

export function ScreenerSkipReasonsBadge({ reasons }: Props) {
  const entries = Object.entries(reasons).filter(([, count]) => count > 0)
  if (entries.length === 0) return null

  return (
    <div className="flex flex-wrap gap-2" aria-label="skip 사유 목록">
      {entries.map(([key, count]) => {
        const label = SKIP_LABEL_MAP[key] ?? key
        const color = SKIP_COLOR_MAP[key] ?? 'bg-gray-700 text-gray-300'
        return (
          <span
            key={key}
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium ${color}`}
            title={`${label}: ${count}건`}
          >
            <span>{label}</span>
            <span className="rounded-full bg-black/20 px-1.5 py-0.5 text-xs tabular-nums">
              {count.toLocaleString()}
            </span>
          </span>
        )
      })}
    </div>
  )
}
