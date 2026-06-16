/**
 * BacktestDetail — 단건 백테스트 메타 + KPI + 시계열 (T-BT R3)
 *
 * 상태 처리:
 *   PENDING / RUNNING: 3초 폴링 + 진행 상태 표시
 *   SUCCEEDED: KPI 5건 + EquityCurveChart + TradeTable
 *   FAILED: 에러 메시지 + 재실행 안내
 *
 * feedback_no_mock_data.md 정합: FAILED 상태 시 명확한 에러 표시
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { backtestApi } from '../../api/backtest'
import type { BacktestRunDetailDto } from '../../api/backtest'
import { BacktestKpiPanel, kpiFromDetail } from './BacktestKpiPanel'
import { EquityCurveChart } from './EquityCurveChart'
import { TradeTable } from './TradeTable'

interface BacktestDetailProps {
  runId: number
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  onBack: () => void
}

function StatusBadge({ status }: { status: BacktestRunDetailDto['status'] }) {
  const styles = {
    PENDING: 'bg-yellow-900/50 text-yellow-300',
    RUNNING: 'bg-blue-900/50 text-blue-300',
    SUCCEEDED: 'bg-emerald-900/50 text-emerald-300',
    FAILED: 'bg-red-900/50 text-red-300',
  }
  const labels = {
    PENDING: '대기 중',
    RUNNING: '실행 중',
    SUCCEEDED: '완료',
    FAILED: '실패',
  }
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-semibold ${styles[status]}`}>
      {labels[status]}
    </span>
  )
}

function MetaRow({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="w-24 flex-shrink-0 text-xs text-gray-500">{label}</span>
      <span className="text-gray-300">{value ?? '—'}</span>
    </div>
  )
}

/**
 * ruleDsl 요약("strategyId=12 universe=[...] ...")에서 strategyId 추출.
 * BacktestPersister.buildRuleDslSummary 서버 생성 포맷 정합 — 포맷이 바뀌면 null로
 * 정직하게 degrade (도선 버튼만 숨김, 오동작 0).
 */
function parseStrategyIdFromRuleDsl(ruleDsl: string | null): string | null {
  if (!ruleDsl) return null
  const match = /(?:^|\s)strategyId=(\d+)/.exec(ruleDsl)
  return match?.[1] ?? null
}

/** ISO 8601 → KST 표시 */
function formatKst(iso: string | null): string | null {
  if (!iso) return null
  return new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
    .format(new Date(iso))
    .replace('T', ' ')
}

export function BacktestDetail({ runId, userId, onBack }: BacktestDetailProps) {
  const [tradePage, setTradePage] = useState(0)

  // 단건 메타 조회 (PENDING/RUNNING 시 3초 폴링)
  const {
    data: detail,
    isLoading: isDetailLoading,
    isError: isDetailError,
  } = useQuery({
    queryKey: ['backtest', runId, userId],
    queryFn: () => backtestApi.getDetail(runId, userId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status === 'PENDING' || status === 'RUNNING') return 3000
      return false
    },
    staleTime: 5000,
  })

  // 결과 시계열 조회 (SUCCEEDED 상태일 때만)
  const {
    data: resultPage,
    isLoading: isResultLoading,
  } = useQuery({
    queryKey: ['backtest-result', runId, userId, tradePage],
    queryFn: () => backtestApi.getResult(runId, userId, tradePage, 100, 0, 100),
    enabled: detail?.status === 'SUCCEEDED',
    staleTime: 60_000,
  })

  if (isDetailLoading) {
    return (
      <div className="flex h-40 items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-gray-700 border-t-blue-500" />
          <p className="text-xs text-gray-500">백테스트 정보를 불러오는 중...</p>
        </div>
      </div>
    )
  }

  if (isDetailError || !detail) {
    return (
      <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-6 text-center">
        <p className="text-sm text-red-400">백테스트 정보를 불러올 수 없습니다</p>
        <button
          className="mt-3 text-xs text-gray-500 underline hover:text-gray-400"
          onClick={onBack}
        >
          목록으로 돌아가기
        </button>
      </div>
    )
  }

  const isInProgress = detail.status === 'PENDING' || detail.status === 'RUNNING'

  return (
    <div className="space-y-4">
      {/* 헤더 */}
      <div className="flex items-center gap-3">
        <button
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          onClick={onBack}
        >
          목록으로
        </button>
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-gray-200">백테스트 #{runId}</span>
          <StatusBadge status={detail.status} />
        </div>
        {isInProgress && (
          <div className="flex items-center gap-1.5 text-xs text-blue-400">
            <div className="h-3 w-3 animate-spin rounded-full border-2 border-blue-900 border-t-blue-400" />
            실행 중 (자동 갱신)
          </div>
        )}
      </div>

      {/* 메타 정보 */}
      <div className="grid gap-2 rounded-xl border border-gray-800 bg-gray-900 p-4 sm:grid-cols-2">
        <MetaRow label="종목코드" value={detail.code} />
        <MetaRow label="인터벌" value={detail.interval} />
        <MetaRow label="기간" value={`${detail.rangeFrom?.slice(0, 10)} ~ ${detail.rangeTo?.slice(0, 10)}`} />
        <MetaRow label="완료 시각" value={formatKst(detail.completedAt)} />
        <div className="sm:col-span-2">
          <MetaRow label="규칙 DSL" value={detail.ruleDsl} />
        </div>
      </div>

      {/* FAILED 상태 */}
      {detail.status === 'FAILED' && (
        <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-4">
          <p className="text-sm font-semibold text-red-400">백테스트 실패</p>
          <p className="mt-1 text-xs text-red-500">
            캔들 데이터가 부족하거나 전략 실행 중 오류가 발생했습니다.
            백필(backfill)이 완료된 후 다시 시도해주세요.
          </p>
        </div>
      )}

      {/* SUCCEEDED: 다음 단계 도선 + KPI + 차트 + 테이블 */}
      {detail.status === 'SUCCEEDED' && (
        <>
          {/* 검증 퍼널 다음 단계 (2026-06-10 보고서 — 백테스트 결과 후속 도선 단절 수선).
              활성화 토글 자체는 전략 목록 화면 단일 진입(보호 상태 로직 중복 회피 — 보수적 보강). */}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-blue-900/40 bg-blue-950/10 px-4 py-3">
            <p className="text-xs text-gray-400">
              결과가 만족스러우면 <span className="text-gray-300">전략 목록</span>에서 활성화해
              자동매매 감시를 시작하고, 보완이 필요하면 빌더에서 수정하세요.
            </p>
            <div className="flex items-center gap-2">
              {(() => {
                const strategyId = parseStrategyIdFromRuleDsl(detail.ruleDsl)
                return strategyId !== null ? (
                  <Link
                    to={`/strategy/builder?strategyId=${strategyId}`}
                    className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-300 hover:bg-gray-800"
                  >
                    빌더에서 수정
                  </Link>
                ) : null
              })()}
              <Link
                to="/strategy/list"
                className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
              >
                전략 목록에서 활성화 →
              </Link>
            </div>
          </div>

          <BacktestKpiPanel kpi={kpiFromDetail(detail)} />

          {resultPage ? (
            <>
              <EquityCurveChart positions={resultPage.positions.content} />
              <TradeTable
                tradePage={resultPage.trades}
                page={tradePage}
                onPageChange={setTradePage}
                isLoading={isResultLoading}
              />
            </>
          ) : (
            <div className="flex h-32 items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
              <div className="flex items-center gap-2 text-xs text-gray-500">
                <div className="h-3 w-3 animate-spin rounded-full border-2 border-gray-700 border-t-gray-400" />
                결과 데이터를 불러오는 중...
              </div>
            </div>
          )}
        </>
      )}

      {/* PENDING / RUNNING: 대기 안내 */}
      {isInProgress && (
        <div className="rounded-xl border border-blue-900/50 bg-blue-900/10 p-4 text-center">
          <p className="text-sm text-blue-400">백테스트 실행 중입니다</p>
          <p className="mt-1 text-xs text-blue-600">3초마다 자동 갱신됩니다</p>
        </div>
      )}
    </div>
  )
}
