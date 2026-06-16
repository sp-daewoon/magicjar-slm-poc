/**
 * 관심종목 빈 상태 컴포넌트
 *
 * ADR 0040 §2-2: 빈 상태 CTA "관심종목 등록하기" 의무
 */

interface WatchlistEmptyProps {
  /** 입력창 포커스 콜백 — CTA 버튼 클릭 시 호출 */
  onRegisterClick: () => void
}

export function WatchlistEmpty({ onRegisterClick }: WatchlistEmptyProps) {
  return (
    <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-gray-700 bg-gray-900/50 px-8 py-16 text-center">
      {/* 빈 상태 아이콘 */}
      <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-gray-800">
        <svg
          className="h-8 w-8 text-gray-500"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.5}
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25H12"
          />
        </svg>
      </div>

      <h3 className="mb-2 text-lg font-semibold text-gray-200">관심종목이 없습니다</h3>
      <p className="mb-6 text-sm text-gray-500">
        종목 코드 6자리를 입력해 관심종목을 등록해 보세요.
      </p>

      {/* ADR 0040 §2-2 CTA 의무 */}
      <button
        type="button"
        onClick={onRegisterClick}
        className="rounded-xl bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
      >
        관심종목 등록하기
      </button>
    </div>
  )
}
