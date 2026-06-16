/**
 * SymbolError — 종목 데이터 부재 시 에러 표시 컴포넌트
 *
 * spec §6-5 + DoD #12 정합:
 * - HTTP 503/404 → "종목 정보를 찾을 수 없습니다" 명시
 * - mock 합성 0 (사용자 메모리 feedback_no_mock_data.md 정합)
 * - [종목검색으로] 버튼 표시
 *
 * variant:
 * - 'not-found' (default): 404·503 또는 미등록 종목
 * - 'delisted': instrument.active=false (상장폐지/구코드) — 안내 톤
 */
import { useNavigate } from 'react-router-dom'

interface SymbolErrorProps {
  code: string
  message?: string
  variant?: 'not-found' | 'delisted'
  /** 상장폐지 일자 (variant='delisted'일 때 표시). ISO date 또는 null. */
  delistedAt?: string | null
}

export function SymbolError({ code, message, variant = 'not-found', delistedAt }: SymbolErrorProps) {
  const navigate = useNavigate()
  const isDelisted = variant === 'delisted'
  const heading = isDelisted ? '상장폐지된 종목입니다' : '종목 정보를 찾을 수 없습니다'

  return (
    <div className="flex min-h-96 items-center justify-center px-4">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm">
        {/* 아이콘 */}
        <div
          className={`flex h-16 w-16 items-center justify-center rounded-full ${
            isDelisted ? 'bg-red-900/30' : 'bg-gray-800'
          }`}
        >
          <svg
            className={`h-8 w-8 ${isDelisted ? 'text-red-400' : 'text-gray-500'}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
            aria-hidden="true"
          >
            {isDelisted ? (
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M18.364 18.364A9 9 0 0 0 5.636 5.636m12.728 12.728A9 9 0 0 1 5.636 5.636m12.728 12.728L5.636 5.636"
              />
            ) : (
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
              />
            )}
          </svg>
        </div>

        {/* 에러 메시지 */}
        <div className="flex flex-col gap-2">
          <h2 className="text-base font-semibold text-white">{heading}</h2>
          {code && (
            <p className="text-sm text-gray-500">
              종목 코드: <span className="font-mono text-gray-400">{code}</span>
            </p>
          )}
          {isDelisted && delistedAt && (
            <p className="text-xs text-gray-500">
              상장폐지일: <span className="text-gray-400">{delistedAt}</span>
            </p>
          )}
          {isDelisted && (
            <p className="mt-1 text-xs text-gray-500">
              실시간 가격·차트·호가는 제공되지 않습니다.
              <br />
              종목검색의 관심종목 필터에서 직접 정리할 수 있습니다.
            </p>
          )}
          {message && (
            <p className="text-xs text-gray-600">{message}</p>
          )}
        </div>

        {/* 종목검색으로 복귀 버튼 */}
        <button
          type="button"
          onClick={() => navigate('/instruments')}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          <svg
            className="h-4 w-4"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
            />
          </svg>
          종목검색으로
        </button>
      </div>
    </div>
  )
}
