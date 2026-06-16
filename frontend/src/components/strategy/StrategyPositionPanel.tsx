/**
 * StrategyPositionPanel — 전략별 보유 현황 표시 (ADR 0110 · 분할매수 R3-a)
 *
 * 그 전략이 산 물량(수량·평단·진행 회차 N/M·실현손익)을 표시한다.
 * 데이터는 strategy_position (GET /api/strategies/{id}/position). 보유 0건이면 "보유 없음"(mock 합성 0건).
 *
 * scaleIn.totalSlices가 있으면 진행 회차를 N/M(filledLots/totalSlices)로 표시한다.
 *
 * 룰 5: LLM provider import 0건
 */
import { useStrategyPosition } from '../../api/hooks/useStrategyPosition'
import type { ScaleInDto } from '../../api/strategy'

interface StrategyPositionPanelProps {
  strategyId: string
  /** 분할 매수 설정 (진행 회차 N/M 표기용) */
  scaleIn?: ScaleInDto | null
  className?: string
}

function formatKrw(value: number): string {
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

function formatSignedKrw(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${Math.round(value).toLocaleString('ko-KR')}원`
}

export function StrategyPositionPanel({ strategyId, scaleIn, className = '' }: StrategyPositionPanelProps) {
  const { data, isLoading, isError } = useStrategyPosition(strategyId)

  const held = (data ?? []).filter((p) => p.quantity > 0)

  return (
    <section className={['rounded-lg border border-gray-800 bg-gray-950/40 p-3', className].filter(Boolean).join(' ')} aria-label="전략별 보유 현황">
      <div className="mb-2 text-xs font-semibold text-gray-300">전략 보유 현황</div>

      {isLoading && <p className="text-xs text-gray-500">불러오는 중...</p>}

      {isError && (
        <p className="text-xs text-amber-300" role="alert">
          보유 현황을 불러오지 못했습니다.
        </p>
      )}

      {!isLoading && !isError && held.length === 0 && (
        <p className="text-xs text-gray-500">보유 없음</p>
      )}

      {!isLoading && !isError && held.length > 0 && (
        <ul className="space-y-2">
          {held.map((p) => {
            const totalSlices = scaleIn?.totalSlices
            const pnlTone = p.realizedPnl > 0 ? 'text-emerald-300' : p.realizedPnl < 0 ? 'text-red-300' : 'text-gray-400'
            return (
              <li key={`${p.strategyId}-${p.code}`} className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs sm:grid-cols-4">
                <div>
                  <div className="text-[11px] text-gray-500">종목</div>
                  <div className="font-medium text-gray-200">{p.codeName ?? p.code}</div>
                </div>
                <div>
                  <div className="text-[11px] text-gray-500">수량 · 평단</div>
                  <div className="font-medium tabular-nums text-gray-200">
                    {p.quantity.toLocaleString('ko-KR')}주 · {formatKrw(p.avgPrice)}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] text-gray-500">진행 회차</div>
                  <div className="font-medium tabular-nums text-indigo-300">
                    {totalSlices ? `${p.filledLots} / ${totalSlices}` : `${p.filledLots}회`}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] text-gray-500">실현손익</div>
                  <div className={`font-medium tabular-nums ${pnlTone}`}>{formatSignedKrw(p.realizedPnl)}</div>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
