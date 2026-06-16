/**
 * 관심종목 메인 페이지
 *
 * ADR 0040 §2-4 신규 컴포넌트 위치: frontend/src/pages/Watchlist/
 * spec §3-2 A 정합:
 * - useWatchlist() 호출 → list 데이터
 * - 빈 list → <WatchlistEmpty /> / 채워진 list → <WatchlistRow /> 매핑
 * - 상단에 <WatchlistAddForm />
 * - 페이지 헤더: "관심종목" + 등록 종목 수 표시
 *
 * 에러 처리:
 * - REST 실패: 해당 섹션만 오류 표시 (페이지 전체 중단 금지)
 * - 로딩: 스켈레톤 표시
 */
import { useRef } from 'react'
import { useWatchlist } from '../../api/hooks/useWatchlist'
import { WatchlistAddForm } from './WatchlistAddForm'
import { WatchlistEmpty } from './WatchlistEmpty'
import { WatchlistRow } from './WatchlistRow'

/** 목록 스켈레톤 — 로딩 중 표시용 */
function WatchlistSkeleton() {
  return (
    <div className="space-y-2" aria-busy="true" aria-label="관심종목 로딩 중">
      {[...Array(3)].map((_, i) => (
        <div
          key={i}
          className="h-14 animate-pulse rounded-xl border border-gray-800 bg-gray-900"
        />
      ))}
    </div>
  )
}

export function WatchlistPage() {
  const { data: entries, isLoading, isError, error } = useWatchlist()
  const inputRef = useRef<HTMLInputElement | null>(null)

  /** 빈 상태 CTA 클릭 시 입력창 포커스 */
  function handleRegisterClick() {
    inputRef.current?.focus()
  }

  const entryCount = entries?.length ?? 0

  return (
    <div className="px-4 py-8">
      <div className="mx-auto max-w-3xl">
        {/* 페이지 헤더 */}
        <div className="mb-6 flex items-baseline justify-between">
          <h1 className="text-2xl font-bold text-white">관심종목</h1>
          {!isLoading && !isError && (
            <span className="text-sm text-gray-400">
              총 <span className="font-semibold text-gray-200">{entryCount}</span>종목
            </span>
          )}
        </div>

        {/* 등록 폼 */}
        <div className="mb-6 rounded-2xl border border-gray-800 bg-gray-900 p-4">
          <WatchlistAddForm inputRef={inputRef} />
        </div>

        {/* 목록 영역 */}
        {isLoading ? (
          <WatchlistSkeleton />
        ) : isError ? (
          <div
            className="rounded-xl border border-red-800/50 bg-red-900/20 px-4 py-3 text-sm text-red-400"
            role="alert"
          >
            관심종목을 불러오지 못했습니다.{' '}
            {error instanceof Error ? error.message : '잠시 후 다시 시도해 주세요.'}
          </div>
        ) : entryCount === 0 ? (
          <WatchlistEmpty onRegisterClick={handleRegisterClick} />
        ) : (
          <div className="space-y-2">
            {(entries ?? []).map((entry) => (
              <WatchlistRow key={entry.id} entry={entry} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
