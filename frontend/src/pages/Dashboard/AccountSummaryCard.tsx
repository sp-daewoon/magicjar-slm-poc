/**
 * AccountSummaryCard — Dashboard 헤더 4 셀 카드 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * 데이터: GET /api/account/summary (useAccountSummary — STOMP push primary + 30s polling fallback)
 * 표시:
 *   - 총 평가금액 (자산)
 *   - 예수금 D+2 (실가용)
 *   - 평가손익 합계 + 수익률 (%)
 *   - 보유 종목 수
 *
 * 우상단 새로고침 버튼 (TanStack Query refetch).
 * mock 합성 0 LOCK — 실패 시 "데이터 없음" 한국어 (feedback_no_mock_data).
 */
import { useAccountSummary } from '../../api/hooks/useAccountSummary'
import type { AccountType } from '../../api/account'
import { extractStatus } from './errorStatus'

/** 원화 포맷 — 천 단위 콤마 + "원" 접미 */
function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

/** 수익률 % 포맷 — 소수 2자리 + +/- 부호 */
function formatRate(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

/** KST 시각 포맷 (HH:mm:ss) */
function formatKstTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString('ko-KR', {
      timeZone: 'Asia/Seoul',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return iso
  }
}

interface AccountSummaryCardProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /** B-1 (2026-05-20): 실전/모의 계좌 선택. queryKey 분리로 캐시 양쪽 보존. */
  accountType?: AccountType
}

export function AccountSummaryCard({
  userId,
  accountType = 'REAL',
}: AccountSummaryCardProps) {
  const { data, isLoading, isError, error, refetch, isFetching } = useAccountSummary({
    userId,
    accountType,
  })

  // B-1: 412 PRECONDITION_FAILED — MOCK 키 미등록 등 자격증명 부재
  // axios error.response.status === 412 일 때 graceful 메시지
  const isCredentialMissing = extractStatus(error) === 412

  return (
    <section
      className="rounded-2xl border border-gray-800 bg-gray-900/60 p-5 shadow-lg"
      aria-label="계좌 요약"
    >
      {/* 헤더: 제목 + 새로고침 */}
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">
            계좌 요약
            <span
              className={[
                'ml-2 rounded px-1.5 py-0.5 text-[10px] font-bold',
                accountType === 'REAL'
                  ? 'bg-blue-900/60 text-blue-300'
                  : 'bg-purple-900/60 text-purple-300',
              ].join(' ')}
            >
              {accountType === 'REAL' ? '실전' : '모의'}
            </span>
          </h2>
          <p className="mt-0.5 text-xs text-gray-500">
            KIS 실시간 · 실시간 push + 30초 fallback
            {data && (
              <span className="ml-1 text-gray-600">
                · 최근 갱신 {formatKstTime(data.fetchedAt)} KST
              </span>
            )}
          </p>
        </div>
        <button
          onClick={() => void refetch()}
          disabled={isFetching}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
          aria-label="계좌 요약 새로고침"
        >
          {isFetching ? '갱신 중...' : '새로고침'}
        </button>
      </div>

      {/* 4 셀 그리드 */}
      {isLoading ? (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-20 animate-pulse rounded-xl bg-gray-800/60" />
          ))}
        </div>
      ) : isError && isCredentialMissing ? (
        // 412: KIS 자격증명 부재 — MOCK/REAL 키 미등록 시
        <div className="rounded-xl border border-yellow-800 bg-yellow-900/20 p-4 text-center text-sm text-yellow-300">
          {accountType === 'MOCK' ? '모의' : '실전'}계좌 KIS 키가 등록되지 않았습니다.
          <p className="mt-1 text-xs text-yellow-500">
            우측 상단 설정(⚙️)에서 {accountType === 'MOCK' ? '모의(VPS)' : '실전(PROD)'} 키를 등록한 뒤 다시 시도해 주세요.
          </p>
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-4 text-center text-sm text-red-400">
          계좌 요약을 불러오지 못했습니다.
          <button
            onClick={() => void refetch()}
            className="ml-2 text-red-300 underline hover:text-red-200"
          >
            다시 시도
          </button>
          <p className="mt-1 text-xs text-red-500">
            KIS upstream·네트워크 상태를 확인해 주세요.
          </p>
        </div>
      ) : !data ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-8 text-center text-sm text-gray-500">
          데이터 없음
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {/* 총 평가금액 */}
          <div className="rounded-xl border border-gray-800 bg-gray-900/40 p-3">
            <p className="text-xs text-gray-500">총 평가금액</p>
            <p className="mt-1 text-lg font-bold tabular-nums text-white">
              {formatKrw(data.totalEvaluation)}
            </p>
          </div>

          {/* 예수금 D+2 */}
          <div className="rounded-xl border border-gray-800 bg-gray-900/40 p-3">
            <p className="text-xs text-gray-500">예수금 (D+2 실가용)</p>
            <p className="mt-1 text-lg font-bold tabular-nums text-white">
              {formatKrw(data.cashD2)}
            </p>
          </div>

          {/* 평가손익 + 수익률 */}
          <div
            className={[
              'rounded-xl border p-3',
              data.totalProfitLoss > 0
                ? 'border-red-900/60 bg-red-950/30'
                : data.totalProfitLoss < 0
                  ? 'border-blue-900/60 bg-blue-950/30'
                  : 'border-gray-800 bg-gray-900/40',
            ].join(' ')}
          >
            <p className="text-xs text-gray-500">평가손익</p>
            <p
              className={[
                'mt-1 text-lg font-bold tabular-nums',
                data.totalProfitLoss > 0
                  ? 'text-red-400'
                  : data.totalProfitLoss < 0
                    ? 'text-blue-400'
                    : 'text-white',
              ].join(' ')}
            >
              {data.totalProfitLoss > 0 ? '+' : ''}
              {formatKrw(data.totalProfitLoss)}
            </p>
            <p
              className={[
                'mt-0.5 text-xs font-semibold tabular-nums',
                data.profitLossRate > 0
                  ? 'text-red-400'
                  : data.profitLossRate < 0
                    ? 'text-blue-400'
                    : 'text-gray-500',
              ].join(' ')}
            >
              {formatRate(data.profitLossRate)}
            </p>
          </div>

          {/* 보유 종목 수 */}
          <div className="rounded-xl border border-gray-800 bg-gray-900/40 p-3">
            <p className="text-xs text-gray-500">보유 종목</p>
            <p className="mt-1 text-lg font-bold tabular-nums text-white">
              {data.holdingsCount}
              <span className="ml-1 text-xs font-normal text-gray-500">종목</span>
            </p>
          </div>
        </div>
      )}
    </section>
  )
}
