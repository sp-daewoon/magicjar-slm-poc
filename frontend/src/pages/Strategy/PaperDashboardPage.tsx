/**
 * PaperDashboardPage — 모의(페이퍼) 트레이딩 대시보드 (ADR 0122 R3 · spec §3-R2 frontend)
 *
 * 발굴 후보를 페이퍼로 승격한 전략의 상태·가상 자본·실현손익 + 페이퍼 포지션을 표시한다.
 *
 * 구성:
 *   1. PaperIsolationBanner — "모의 · 실거래 아님" 격리 안내(§6 LOCK)
 *   2. 활성 페이퍼 전략 목록 (presetTitle · 종목 · 가상 자본 · 상태 + 중단 + 시그널 보기)
 *   3. 페이퍼 포지션 목록 (종목 · 수량 · 평단 · 실현손익 — 모두 가상 ₩)
 *   4. 시그널 내역 (SignalListPanel 재사용 + STOMP 실시간) — "전략이 지금 작동 중인가"를
 *      확인하는 창 (2026-06-10 strategy-mock-paper-flow 보고서 발전점 — 작동 가시성 보강)
 *
 * ### 가드 (방향성 보존 — ADR 0122)
 * - 페이퍼 전략·포지션 표시 전부에 모의 배지(§6 LOCK). 실 계좌 화면과 시각적 분리(amber 톤).
 * - 실 자산 + 가상 자본 합산 표시 금지(§6). 이 화면은 페이퍼 전용 격리 진입점.
 * - 빈 응답은 "데이터 없음"으로 정직 표기(mock 합성 0 — feedback_no_mock_data).
 * - 자동 진입 0 — 이 화면은 조회·중단만. 페이퍼 생성은 발굴 화면 승급 액션에서만.
 * - 시그널 내역은 기존 조회 path(GET /signals + STOMP) 재사용 — 신규 API 0.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { useToast } from '../../components/ui/ToastProvider'
import { SignalListPanel } from '../../components/strategy/SignalListPanel'
import { useStompSignal } from '../../api/hooks/useStompSignal'
import {
  useDaytradePaperPositions,
  useDaytradePaperStop,
  useDaytradePaperStrategies,
} from '../../features/daytradePaper/hooks/useDaytradePaper'
import {
  PaperBadge,
} from '../../features/daytradePaper/components/PaperBadge'
import type {
  PaperPositionDto,
  PaperStrategyDto,
} from '../../features/daytradePaper/api/daytradePaperApi'

/** 가상 ₩ 포맷 — 천 단위 콤마 */
function virtualWon(amount: number): string {
  return `가상 ₩${Math.round(amount).toLocaleString()}`
}

/** 실현손익 부호 색상 (한국 표준 — 양수 red·음수 blue 가 아니라, 페이퍼는 가상이므로 중립 emerald/red 톤 유지) */
function pnlColor(pnl: number): string {
  if (pnl > 0) return 'text-emerald-400'
  if (pnl < 0) return 'text-red-400'
  return 'text-gray-400'
}

function pnlText(pnl: number): string {
  const sign = pnl > 0 ? '+' : ''
  return `${sign}${Math.round(pnl).toLocaleString()}`
}

/** 활성 페이퍼 전략 카드 */
function PaperStrategyCard({
  strategy,
  onStop,
  stopping,
  onShowSignals,
}: {
  strategy: PaperStrategyDto
  onStop: (strategyId: number) => void
  stopping: boolean
  onShowSignals: (strategyId: number) => void
}) {
  return (
    <div
      className="rounded-lg border border-amber-800/40 bg-gray-900/60 px-4 py-3"
      aria-label={`모의 전략 ${strategy.presetTitle}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Link
              to="/strategy/list"
              className="truncate text-sm font-medium text-white underline-offset-2 hover:text-amber-200 hover:underline focus:outline-none focus:ring-2 focus:ring-amber-400 focus:ring-offset-1 focus:ring-offset-gray-900"
              title="전략 목록으로 이동"
            >
              {strategy.presetTitle}
            </Link>
            <PaperBadge />
          </div>
          <p className="mt-0.5 font-mono text-[10px] text-gray-600">
            <Link
              to={`/strategy/builder?strategyId=${strategy.strategyId}`}
              className="text-gray-500 underline-offset-2 hover:text-blue-300 hover:underline focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
              title="전략 빌더에서 수정"
            >
              #{strategy.strategyId}
            </Link>{' '}
            · {strategy.presetKey}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {strategy.codes.map((code) => (
              <span
                key={code}
                className="rounded bg-gray-800 px-1.5 py-0.5 font-mono text-[10px] text-gray-400"
              >
                {code}
              </span>
            ))}
          </div>
        </div>
        <div className="shrink-0 text-right">
          <p className="text-xs text-gray-500">가상 자본</p>
          <p className="tabular-nums text-sm font-semibold text-amber-300">
            {virtualWon(strategy.virtualCapital)}
          </p>
          <span
            className={[
              'mt-1 inline-block rounded-full px-2 py-0.5 text-[10px]',
              strategy.status === 'ACTIVE'
                ? 'bg-emerald-900/40 text-emerald-300'
                : 'bg-gray-800 text-gray-400',
            ].join(' ')}
          >
            {strategy.status}
          </span>
        </div>
      </div>
      <div className="mt-3 flex items-center justify-between">
        <span className="text-[11px] text-gray-600">인터벌 {strategy.intervals.join(', ')}</span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => onShowSignals(strategy.strategyId)}
            className="rounded-lg border border-amber-800/50 px-3 py-1 text-xs text-amber-300 hover:bg-amber-950/30"
            title="이 전략의 시그널 내역으로 이동 (작동 확인)"
          >
            시그널 보기
          </button>
          {strategy.status === 'ACTIVE' && (
            <button
              type="button"
              disabled={stopping}
              onClick={() => onStop(strategy.strategyId)}
              className="rounded-lg border border-gray-700 px-3 py-1 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {stopping ? '중단 중...' : '전략 중단'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

/** 페이퍼 포지션 row */
function PaperPositionRow({ position }: { position: PaperPositionDto }) {
  return (
    <tr className="border-t border-gray-800/50">
      <td className="py-2 pr-3">
        <div className="text-sm text-white">{position.codeName ?? position.code}</div>
        <div className="font-mono text-[10px] text-gray-500">{position.code}</div>
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-300">
        {position.quantity.toLocaleString()}주
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-400">
        {virtualWon(position.avgPrice)}
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-400">{position.filledLots}</td>
      <td className={`py-2 pr-3 text-right tabular-nums ${pnlColor(position.realizedPnl)}`}>
        {pnlText(position.realizedPnl)}
      </td>
      <td className="py-2 text-right">
        {position.isClosed ? (
          <span className="rounded-full bg-gray-800 px-2 py-0.5 text-[10px] text-gray-400">
            청산
          </span>
        ) : (
          <span className="rounded-full bg-amber-900/40 px-2 py-0.5 text-[10px] text-amber-300">
            보유
          </span>
        )}
      </td>
    </tr>
  )
}

export function PaperDashboardPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const { showToast } = useToast()

  const strategiesQuery = useDaytradePaperStrategies(userId)
  const positionsQuery = useDaytradePaperPositions(userId)
  const stop = useDaytradePaperStop(userId)
  const [stoppingId, setStoppingId] = useState<number | null>(null)

  // 시그널 내역 전략 필터 ('' = 전체) — "시그널 보기" 클릭 시 해당 전략으로 진입
  const [signalStrategyId, setSignalStrategyId] = useState<string>('')

  // 실시간 시그널 prepend (전략 목록 화면과 동일 path 재사용 — ADR 0089 Phase 2)
  const { signalQueue } = useStompSignal({
    userId: userId ?? 0,
    enabled: userId !== null,
  })

  function handleShowSignals(strategyId: number) {
    setSignalStrategyId(String(strategyId))
    document.getElementById('paper-signal-section')?.scrollIntoView({ behavior: 'smooth' })
  }

  function handleStop(strategyId: number) {
    setStoppingId(strategyId)
    stop.mutate(strategyId, {
      onSuccess: () => showToast(`모의 전략 #${strategyId} 중단됨`, 'success'),
      onError: (err) => showToast(`중단 실패: ${err.message}`, 'error'),
      onSettled: () => setStoppingId(null),
    })
  }

  const strategies = strategiesQuery.data ?? []
  const positions = positionsQuery.data ?? []

  return (
    <div className="mx-auto max-w-4xl px-4 py-6">
      <header className="mb-5">
        <div className="flex items-center gap-2">
          <h1 className="text-xl font-bold text-white">모의 트레이딩</h1>
          <PaperBadge size="md" />
        </div>
        <p className="mt-1 text-sm text-gray-500">
          발굴 후보를 페이퍼로 승격한 전략의 상태·가상 자본·포지션·시그널을 확인합니다. 새 전략은{' '}
          <Link to="/strategy/daytrade-discover" className="text-amber-400 hover:underline">
            단타 발굴
          </Link>{' '}
          화면에서 "모의로 굴려보기"로 시작합니다. 발굴 프리셋의 forward 적중률은{' '}
          <Link to="/strategy/scorecard" className="text-amber-400 hover:underline">
            발굴 스코어카드
          </Link>
          에서 확인합니다.
        </p>
      </header>

      {/* userId 미등록 */}
      {userId === null && (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-gray-800 bg-gray-900/40 py-16">
          <p className="text-sm text-yellow-400">사용자 설정이 완료되지 않았습니다.</p>
          <p className="text-xs text-gray-500">설정 화면에서 KIS 키 등록 후 다시 시도해 주세요.</p>
        </div>
      )}

      {userId !== null && (
        <div className="space-y-6">
          {/* 전략 목록 */}
          <section>
            <h2 className="mb-2 text-sm font-semibold text-gray-300">활성 모의 전략</h2>
            {strategiesQuery.isLoading ? (
              <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-10 text-center text-sm text-gray-500">
                불러오는 중...
              </div>
            ) : strategiesQuery.error ? (
              <div className="rounded-xl border border-red-900/40 bg-red-950/20 py-8 text-center">
                <p className="text-sm text-red-400">모의 전략을 불러오지 못했습니다.</p>
                <p className="mt-1 text-xs text-gray-500">{strategiesQuery.error.message}</p>
              </div>
            ) : strategies.length === 0 ? (
              <div className="rounded-xl border border-dashed border-gray-800 px-4 py-10 text-center">
                <p className="text-sm text-gray-400">활성 모의 전략이 없습니다.</p>
                <p className="mt-2 text-xs text-gray-600">
                  단타 발굴에서 후보를 선택해 "모의로 굴려보기"로 시작하세요. (mock 합성 0)
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {strategies.map((s) => (
                  <PaperStrategyCard
                    key={s.strategyId}
                    strategy={s}
                    onStop={handleStop}
                    stopping={stoppingId === s.strategyId}
                    onShowSignals={handleShowSignals}
                  />
                ))}
              </div>
            )}
          </section>

          {/* 포지션 목록 */}
          <section>
            <div className="mb-2 flex items-center gap-2">
              <h2 className="text-sm font-semibold text-gray-300">모의 포지션</h2>
              <PaperBadge />
            </div>
            {positionsQuery.isLoading ? (
              <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-10 text-center text-sm text-gray-500">
                불러오는 중...
              </div>
            ) : positionsQuery.error ? (
              <div className="rounded-xl border border-red-900/40 bg-red-950/20 py-8 text-center">
                <p className="text-sm text-red-400">모의 포지션을 불러오지 못했습니다.</p>
                <p className="mt-1 text-xs text-gray-500">{positionsQuery.error.message}</p>
              </div>
            ) : positions.length === 0 ? (
              <div className="rounded-xl border border-dashed border-gray-800 px-4 py-10 text-center">
                <p className="text-sm text-gray-400">모의 포지션이 없습니다.</p>
                <p className="mt-2 text-xs text-gray-600">
                  전략이 시그널을 발사하면 가상 자본 기준 모의 포지션이 여기에 표시됩니다. 손익은
                  실 시세 기반 실제 시뮬 수치입니다(가상 ₩).
                </p>
                <p className="mt-1.5 text-xs text-gray-600">
                  포지션이 0이어도 고장이 아닐 수 있습니다 — 아래{' '}
                  <a href="#paper-signal-section" className="text-amber-400 hover:underline">
                    시그널 내역
                  </a>
                  에서 전략이 평가 중인지(조건 대기) 확인하세요. 시그널은 장중 KIS 실시세 캔들이
                  닫힐 때 평가됩니다.
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto rounded-xl border border-gray-800 bg-gray-900/40 p-3">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-xs text-gray-500">
                      <th className="pb-2 pr-3 text-left font-medium">종목</th>
                      <th className="pb-2 pr-3 text-right font-medium">수량</th>
                      <th className="pb-2 pr-3 text-right font-medium">평단(가상)</th>
                      <th className="pb-2 pr-3 text-right font-medium">회차</th>
                      <th className="pb-2 pr-3 text-right font-medium">실현손익(가상)</th>
                      <th className="pb-2 text-right font-medium">상태</th>
                    </tr>
                  </thead>
                  <tbody>
                    {positions.map((p) => (
                      <PaperPositionRow key={`${p.strategyId}-${p.code}`} position={p} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* 시그널 내역 — "전략이 지금 작동 중인가" 확인 창 (기존 path 재사용) */}
          <section id="paper-signal-section">
            <div className="mb-2 flex items-center gap-2">
              <PaperBadge />
              <p className="text-xs text-gray-500">
                모의 전략도 일반 전략과 같은 시그널 path로 평가됩니다. 전략 필터에서 모의 전략을
                선택해 평가·발사 여부를 확인하세요. (시그널 0건 = 조건 대기 중)
              </p>
            </div>
            <SignalListPanel
              key={signalStrategyId || '__all__'}
              userId={userId}
              prependSignals={signalQueue}
              defaultStrategyId={signalStrategyId}
              title="시그널 내역 (작동 확인)"
            />
          </section>
        </div>
      )}
    </div>
  )
}
