/**
 * DisclosurePanel — 종목 상세 sidebar 공시 패널
 *
 * ADR 0075 §8-5 Phase 3 산출물 + spec §2-3 핵심 결정 LOCK:
 *   - useSymbolDisclosures hook (viewport 진입 시 refetchInterval 60s)
 *   - STOMP 0 LOCK (§6 사용자 결정 6-1)
 *   - 정정공시 빨간 배지 강조 (isCorrection=true)
 *   - onlyCorrection 필터 토글 버튼
 *   - 빈 상태: "최근 7일간 공시가 없습니다" (feedback_no_mock_data 정합)
 *   - AI provider import 0 (§11-2)
 *   - DART URL 새 탭 open (target="_blank" rel="noopener noreferrer")
 *
 * dogfooding hotfix: useInfiniteQuery 기반 누적 append + 스크롤.
 *   - "더 보기" → fetchNextPage() — 기존 items 보존
 *   - 외곽 컨테이너 max-h-[600px] overflow-y-auto 스크롤
 *   - hasNextPage=false 시 "더 보기" 버튼 숨김
 *   - isFetchingNextPage 시 로딩 표시
 *   - onlyCorrection 필터 토글 시 query 재시작 (queryKey 변경)
 */
import { useState } from 'react'
import { useInViewport } from '../../../hooks/useInViewport'
import { useSymbolDisclosures } from '../hooks/useSymbolDisclosures'
import { DisclosureItemRow } from './DisclosureItem'

interface Props {
  code: string
}

export function DisclosurePanel({ code }: Props) {
  const [onlyCorrection, setOnlyCorrection] = useState(false)
  const { ref, isVisible, hasEntered } = useInViewport<HTMLElement>()

  const { data, isLoading, isError, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useSymbolDisclosures(
      code,
      {
        size: 20,
        onlyCorrection,
      },
      { enabled: isVisible },
    )

  // onlyCorrection 필터 토글 — queryKey 변경으로 useInfiniteQuery가 자동 재시작
  function handleToggleOnlyCorrection() {
    setOnlyCorrection((prev) => !prev)
  }

  if (!hasEntered) {
    return (
      <section
        ref={ref}
        aria-label="공시"
        className="min-h-[180px] rounded-lg border border-gray-800 bg-gray-900 p-4"
      >
        <h3 className="mb-3 text-sm font-semibold text-gray-300">공시</h3>
      </section>
    )
  }

  // ── 로딩 스켈레톤 (최초 로드)
  if (isLoading) {
    return (
      <section ref={ref} aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">공시</h3>
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="space-y-1 rounded bg-gray-800 p-3 animate-pulse">
              <div className="h-3 w-4/5 rounded bg-gray-700" />
              <div className="h-2 w-2/5 rounded bg-gray-700" />
            </div>
          ))}
        </div>
      </section>
    )
  }

  // ── REST 실패
  if (isError) {
    return (
      <section ref={ref} aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">공시</h3>
        <p className="text-xs text-red-400">공시를 불러오지 못했습니다.</p>
      </section>
    )
  }

  // 전체 pages 누적 flat
  const items = data?.pages.flatMap((p) => p.items) ?? []
  const totalElements = data?.pages[0]?.totalElements ?? 0

  return (
    <section ref={ref} aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
      {/* 헤더 + onlyCorrection 필터 */}
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-gray-300">공시</h3>
          {totalElements > 0 && (
            <span className="text-xs text-gray-500">{totalElements}건</span>
          )}
        </div>

        {/* 정정공시만 보기 토글 */}
        <button
          type="button"
          onClick={handleToggleOnlyCorrection}
          aria-pressed={onlyCorrection}
          className={`rounded px-2 py-1 text-xs transition-colors ${
            onlyCorrection
              ? 'bg-red-500/20 text-red-400 border border-red-500/40'
              : 'border border-gray-700 text-gray-500 hover:text-gray-300'
          }`}
        >
          정정공시만
        </button>
      </div>

      {/* 빈 상태 (ADR 0075 §11-3 + feedback_no_mock_data 정합) */}
      {items.length === 0 ? (
        <p className="py-4 text-center text-xs text-gray-500">
          {onlyCorrection ? '최근 7일간 정정공시가 없습니다.' : '최근 7일간 공시가 없습니다.'}
        </p>
      ) : (
        <>
          {/* 누적 목록 — 스크롤 컨테이너 */}
          <div className="max-h-[600px] overflow-y-auto pr-1">
            <ul>
              {items.map((item) => (
                <DisclosureItemRow key={item.id} item={item} />
              ))}
            </ul>

            {/* 다음 페이지 로딩 중 스피너 */}
            {isFetchingNextPage && (
              <div className="py-3 text-center">
                <span className="text-xs text-gray-500">불러오는 중...</span>
              </div>
            )}
          </div>

          {/* 더 보기 — hasNextPage 있을 때만 표시 */}
          {hasNextPage && !isFetchingNextPage && (
            <button
              type="button"
              onClick={() => fetchNextPage()}
              className="mt-3 w-full rounded border border-gray-700 py-1.5 text-xs text-gray-400 hover:border-gray-500 hover:text-gray-200 transition-colors"
            >
              더 보기
            </button>
          )}
        </>
      )}
    </section>
  )
}
