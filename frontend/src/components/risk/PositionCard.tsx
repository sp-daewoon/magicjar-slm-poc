/**
 * PositionCard — 보유 포지션 카드 컴포넌트 (T-RISK Phase 3 — ADR 0067)
 *
 * 표시: code·codeName·qty·avg_price·source·entry_ts
 * 현재가 제공 시: 평가손익 PnlBadge 표시
 */
import type { PositionDto } from '../../api/position'
import { PnlBadge } from './PnlBadge'

/** KST 날짜+시간 포맷 */
function formatKst(iso: string): string {
  try {
    return new Date(iso).toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: '2-digit',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

/** 원화 포맷 */
function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

interface PositionCardProps {
  position: PositionDto
}

export function PositionCard({ position }: PositionCardProps) {
  const { code, codeName, qty, avgPrice, currentPrice, source, entryTs } = position

  // 평가 손익 계산 (현재가 있을 때)
  const evalPnl =
    currentPrice !== undefined ? (currentPrice - avgPrice) * qty : undefined
  const evalAmount = currentPrice !== undefined ? currentPrice * qty : undefined

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-4 hover:bg-gray-800/40 transition-colors">
      {/* 상단: 종목 코드 + 소스 배지 */}
      <div className="flex items-start justify-between">
        <div>
          <span className="text-base font-bold text-white">{code}</span>
          {codeName && (
            <span className="ml-2 text-sm text-gray-400">{codeName}</span>
          )}
        </div>
        <span
          className={[
            'rounded px-2 py-0.5 text-xs font-semibold',
            source === 'REAL'
              ? 'bg-amber-900/60 text-amber-300 border border-amber-700'
              : 'bg-blue-900/60 text-blue-300 border border-blue-700',
          ].join(' ')}
        >
          {source}
        </span>
      </div>

      {/* 중단: 수량·평균 매수가 */}
      <div className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <p className="text-xs text-gray-500">보유 수량</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {qty.toLocaleString('ko-KR')}주
          </p>
        </div>
        <div>
          <p className="text-xs text-gray-500">평균 매수가</p>
          <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
            {formatKrw(avgPrice)}
          </p>
        </div>
        {currentPrice !== undefined && (
          <>
            <div>
              <p className="text-xs text-gray-500">현재가</p>
              <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
                {formatKrw(currentPrice)}
              </p>
            </div>
            <div>
              <p className="text-xs text-gray-500">평가금액</p>
              <p className="mt-0.5 text-sm font-semibold text-white tabular-nums">
                {evalAmount !== undefined ? formatKrw(evalAmount) : '—'}
              </p>
            </div>
          </>
        )}
      </div>

      {/* 하단: 평가손익 + 진입 시각 */}
      <div className="mt-3 flex items-center justify-between border-t border-gray-800 pt-3">
        <div>
          {evalPnl !== undefined ? (
            <PnlBadge pnl={evalPnl} />
          ) : (
            <span className="text-xs text-gray-600">현재가 미제공</span>
          )}
        </div>
        <div className="text-xs text-gray-600">진입 {formatKst(entryTs)}</div>
      </div>
    </div>
  )
}
