/**
 * ComplexityCard — 전략 복잡도 4단 표시
 *
 * validate 응답 StrategyComplexityDto 시각화:
 *   LOW    = 녹색
 *   MEDIUM = 노란색
 *   HIGH   = 오렌지
 *   CRITICAL = 빨간색
 *
 * 룰 5: LLM provider import 0건
 */
import type { StrategyComplexityDto } from '../../api/strategy'

interface ComplexityCardProps {
  complexity: StrategyComplexityDto
}

const COMPLEXITY_CONFIG: Record<
  StrategyComplexityDto['complexityClass'],
  { label: string; color: string; badge: string }
> = {
  LOW: {
    label: '낮음',
    color: 'text-emerald-400',
    badge: 'bg-emerald-900/40 text-emerald-300 border-emerald-800',
  },
  MEDIUM: {
    label: '보통',
    color: 'text-yellow-400',
    badge: 'bg-yellow-900/40 text-yellow-300 border-yellow-800',
  },
  HIGH: {
    label: '높음',
    color: 'text-orange-400',
    badge: 'bg-orange-900/40 text-orange-300 border-orange-800',
  },
  CRITICAL: {
    label: '위험',
    color: 'text-red-400',
    badge: 'bg-red-900/40 text-red-300 border-red-800',
  },
}

export function ComplexityCard({ complexity }: ComplexityCardProps) {
  const cfg = COMPLEXITY_CONFIG[complexity.complexityClass]

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-200">전략 복잡도</h3>
        <span
          className={[
            'rounded-full border px-3 py-0.5 text-sm font-bold',
            cfg.badge,
          ].join(' ')}
        >
          {complexity.complexityClass} — {cfg.label}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-sm">
        <div className="flex items-center justify-between rounded-lg bg-gray-950 px-3 py-2">
          <span className="text-gray-500">AST 깊이</span>
          <span className="font-mono text-white">{complexity.astDepth}</span>
        </div>
        <div className="flex items-center justify-between rounded-lg bg-gray-950 px-3 py-2">
          <span className="text-gray-500">노드 수</span>
          <span className="font-mono text-white">{complexity.nodeCount}</span>
        </div>
        <div className="flex items-center justify-between rounded-lg bg-gray-950 px-3 py-2">
          <span className="text-gray-500">지표 호출</span>
          <span className="font-mono text-white">{complexity.indicatorCallCount}</span>
        </div>
        <div className="flex items-center justify-between rounded-lg bg-gray-950 px-3 py-2">
          <span className="text-gray-500">예상 평가 시간</span>
          <span className="font-mono text-white">{complexity.estimatedEvalTimeMs}ms</span>
        </div>
      </div>

      {complexity.complexityClass === 'CRITICAL' && (
        <p className="mt-3 rounded-lg bg-red-900/20 px-3 py-2 text-xs text-red-400">
          전략이 너무 복잡합니다. 노드를 줄이거나 지표를 단순화하세요.
        </p>
      )}
    </div>
  )
}
