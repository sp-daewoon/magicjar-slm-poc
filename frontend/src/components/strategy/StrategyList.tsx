/**
 * StrategyList — 전략 카드 그리드 + 무한 스크롤 (T-SIG Phase 3 — ADR 0066)
 *
 * - useSigStrategies(size=20) 무한 쿼리
 * - 빈 목록: "전략을 등록하고 시그널을 받으세요" 안내
 * - Intersection Observer 기반 자동 더 불러오기
 */
import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { useSigStrategies } from '../../api/hooks/useSigStrategy'
import { type StrategyStatus } from '../../api/strategy'
import { type StrategyFilterOption } from './StrategyFilterBar'
import { StrategyCard } from './StrategyCard'

const FILTER_EMPTY_LABEL: Record<StrategyStatus, string> = {
  ACTIVE: '자동매매중',
  BUY_BLOCKED: '매수차단',
  PAUSED: '중지',
  DISABLED: '보호중지',
  DELETED: '삭제됨',
}

interface StrategyListProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  filter: StrategyFilterOption
}

export function StrategyList({ userId, filter }: StrategyListProps) {
  const {
    data,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useSigStrategies(userId, 20)

  // Intersection Observer — 뷰포트 끝 도달 시 다음 페이지 로드
  const sentinelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasNextPage && !isFetchingNextPage) {
          void fetchNextPage()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [fetchNextPage, hasNextPage, isFetchingNextPage])

  // ── 로딩 스켈레톤 ──
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            className="h-44 animate-pulse rounded-xl bg-gray-800/60"
          />
        ))}
      </div>
    )
  }

  // ── 에러 ──
  if (isError) {
    return (
      <div className="rounded-lg border border-red-900 bg-red-950/30 p-6 text-center">
        <p className="text-sm text-red-400">전략 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
      </div>
    )
  }

  // ── 전체 전략 + 필터 적용 ──
  const allStrategies =
    data?.pages.flatMap((p) => p.content) ?? []

  const filtered =
    filter === 'ALL'
      ? allStrategies.filter((s) => s.status !== 'DELETED')
      : allStrategies.filter((s) => s.status === (filter as StrategyStatus))

  // ── 빈 목록 ──
  if (filtered.length === 0) {
    return (
      <div className="flex flex-col items-center gap-4 rounded-xl border border-dashed border-gray-700 bg-gray-900/40 py-16 text-center">
        <p className="text-sm text-gray-400">
          {filter === 'ALL'
            ? '등록된 전략이 없습니다.'
            : `${FILTER_EMPTY_LABEL[filter as StrategyStatus]} 상태의 전략이 없습니다.`}
        </p>
        <p className="text-xs text-gray-500">전략을 등록하고 실시간 시그널을 받아보세요.</p>
        <Link
          to="/strategy/builder"
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          전략 빌더 열기
        </Link>
      </div>
    )
  }

  return (
    <div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((s) => (
          <StrategyCard key={s.id} strategy={s} />
        ))}
      </div>

      {/* 무한 스크롤 sentinel */}
      <div ref={sentinelRef} className="h-4" />
      {isFetchingNextPage && (
        <p className="mt-4 text-center text-xs text-gray-500">불러오는 중...</p>
      )}
    </div>
  )
}
