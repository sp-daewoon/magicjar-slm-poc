/**
 * PerformancePanel — 대시보드 내부 성과 탭
 *
 * 성과 API는 폴링 없이 5분 캐시를 쓰며, 본 패널이 마운트될 때만 조회한다.
 * DashboardPage 기본 계좌 현황 진입 시 KIS 계좌 조회와 성과 조회가 동시에 발생하지 않게 분리한다.
 */
import { useState } from 'react'
import { PerformancePeriodSelector } from './PerformancePeriodSelector'
import { PerformanceSummaryCards } from './PerformanceSummaryCards'
import { EquityCurveChart } from './EquityCurveChart'
import { DailyPnlBarChart } from './DailyPnlBarChart'
import { PerformanceTradeBreakdownTable } from './PerformanceTradeBreakdownTable'
import { usePerformanceSummary } from '../hooks/usePerformanceSummary'
import { useEquityCurve } from '../hooks/useEquityCurve'
import { usePerformanceTrades } from '../hooks/usePerformanceTrades'
import type { PerformancePeriod } from '../api/performanceApi'
import type { AccountType } from '../../../api/account'

interface PerformancePanelProps {
  userId: number
  accountType: AccountType
}

export function PerformancePanel({ userId, accountType }: PerformancePanelProps) {
  const [period, setPeriod] = useState<PerformancePeriod>('MONTH')

  const {
    data: summary,
    isLoading: summaryLoading,
    isError: summaryError,
  } = usePerformanceSummary({ userId, period, mode: accountType })

  const {
    data: equityCurve,
    isLoading: curveLoading,
    isError: curveError,
  } = useEquityCurve({
    userId,
    from: summary?.periodStart,
    to: summary?.periodEnd,
    mode: accountType,
    enabled: Boolean(summary),
  })

  const {
    data: tradeBreakdown,
    isLoading: tradeLoading,
    isError: tradeError,
  } = usePerformanceTrades({
    userId,
    period,
    periodStart: summary?.periodStart,
    periodEnd: summary?.periodEnd,
    mode: accountType,
    enabled: Boolean(summary),
  })

  return (
    <section aria-label="성과 대시보드">
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-gray-100">성과</h2>
          <p className="mt-0.5 text-sm text-gray-500">
            P&amp;L · MDD · 승률 · 평균 손익비 · Equity Curve
            <span className="ml-1 text-gray-600">
              · {accountType === 'REAL' ? '실전' : '모의'} 기준
            </span>
          </p>
        </div>
        <PerformancePeriodSelector value={period} onChange={setPeriod} />
      </div>

      <div className="mb-6">
        <PerformanceSummaryCards
          summary={summary}
          isLoading={summaryLoading}
          isError={summaryError}
        />
      </div>

      <div className="mb-4">
        <h3 className="mb-2 text-sm font-semibold text-gray-300">Equity Curve (누적 손익)</h3>
        <EquityCurveChart
          points={equityCurve?.points}
          isLoading={curveLoading}
          isError={curveError}
        />
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-gray-300">일별 P&amp;L</h3>
        <DailyPnlBarChart
          points={equityCurve?.points}
          isLoading={curveLoading}
          isError={curveError}
        />
      </div>

      <div className="mt-6">
        <PerformanceTradeBreakdownTable
          breakdown={tradeBreakdown}
          isLoading={tradeLoading}
          isError={tradeError}
        />
      </div>

      {summary?.cacheHit && (
        <p className="mt-3 text-right text-xs text-gray-600">캐시 HIT</p>
      )}
    </section>
  )
}
