/**
 * StrategyListPage — 전략 목록 + 시그널 내역 페이지 (T-SIG Phase 3 — ADR 0066)
 *
 * 라우트: /strategy/list
 *
 * 구성:
 * - 상단: StrategyFilterBar + StrategyList (전략 카드 그리드)
 * - 하단: SignalListPanel (시그널 시계열)
 * - useStompSignal: 실시간 시그널 수신 → toast + SignalListPanel prepend
 *
 * userId: useOnboardingStore.registeredUserId
 * - 미등록(null) 시 온보딩 안내
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { StrategyFilterBar, type StrategyFilterOption } from '../../components/strategy/StrategyFilterBar'
import { StrategyList } from '../../components/strategy/StrategyList'
import { StrategyRunStatusSummary } from '../../components/strategy/StrategyRunStatusSummary'
import { SignalListPanel } from '../../components/strategy/SignalListPanel'
import { useStompSignal } from '../../api/hooks/useStompSignal'

export function StrategyListPage() {
  const [filterOption, setFilterOption] = useState<StrategyFilterOption>('ALL')
  const userId = useOnboardingStore((s) => s.registeredUserId)

  // 실시간 시그널 구독 (userId 있을 때만)
  // ADR 0089 Phase 2 — userId number; 0은 sentinel (disabled 상태에서만 통과)
  const { signalQueue } = useStompSignal({
    userId: userId ?? 0,
    enabled: userId !== null,
  })

  // ── userId 미등록 (온보딩 미완료) ──
  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <div className="rounded-full bg-blue-950 p-5">
          <svg
            className="h-10 w-10 text-blue-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
            aria-hidden
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z" />
          </svg>
        </div>
        <h1 className="text-2xl font-bold text-white">전략 목록</h1>
        <p className="text-sm text-gray-400">
          KIS 계좌 등록을 완료하면 전략과 시그널을 사용할 수 있습니다.
        </p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
      {/* 페이지 헤더 */}
      <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">전략 목록</h1>
          <p className="mt-1 text-sm text-gray-400">
            등록된 전략과 실시간 매매 시그널을 확인합니다.
          </p>
        </div>
        <Link
          to="/strategy/builder"
          className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          새 전략 만들기
        </Link>
      </div>

      <StrategyRunStatusSummary userId={userId} />

      {/* 전략 목록 섹션 */}
      <section aria-labelledby="strategy-section-heading" className="mb-10">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 id="strategy-section-heading" className="text-base font-semibold text-white">
            내 전략
          </h2>
          <StrategyFilterBar value={filterOption} onChange={setFilterOption} />
        </div>
        <StrategyList userId={userId} filter={filterOption} />
      </section>

      {/* 구분선 */}
      <hr className="border-gray-800" />

      {/* 시그널 내역 섹션 */}
      <div className="mt-10">
        <SignalListPanel
          userId={userId}
          prependSignals={signalQueue}
        />
      </div>
    </div>
  )
}
