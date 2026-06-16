/**
 * HoldingsTable — 보유 종목 테이블 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * 데이터: GET /api/account/positions (useAccountPositions — STOMP push primary + 30s polling fallback)
 * 컬럼: 종목코드·종목명·수량·평균가·현재가·평가금·손익·수익률 (8 컬럼)
 *
 * 종목 클릭 → /symbol/:code 이동 (T-S1 진입점 — ADR 0040 §2-2)
 * 모바일 우선 — 작은 화면에서는 가로 스크롤
 * mock 합성 0 LOCK — 빈 결과 "보유 종목이 없습니다" 한국어
 */
import { Link } from 'react-router-dom'
import { useAccountPositions } from '../../api/hooks/useAccountPositions'
import type { AccountType } from '../../api/account'
import { extractStatus } from './errorStatus'

/** 원화 포맷 — 천 단위 콤마 + "원" 접미 */
function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

/** 수익률 % — 소수 2자리 + 부호 */
function formatRate(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

/** 손익 색상 — 한국 표준 (상승=빨강, 하락=파랑) */
function pnlColor(value: number): string {
  if (value > 0) return 'text-red-400'
  if (value < 0) return 'text-blue-400'
  return 'text-gray-400'
}

interface HoldingsTableProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /** B-1 (2026-05-20): 실전/모의 계좌 선택. */
  accountType?: AccountType
}

export function HoldingsTable({
  userId,
  accountType = 'REAL',
}: HoldingsTableProps) {
  const { data, isLoading, isError, error, refetch, isFetching } = useAccountPositions({
    userId,
    accountType,
  })

  const holdings = data ?? []

  const isCredentialMissing = extractStatus(error) === 412

  return (
    <section
      className="rounded-2xl border border-gray-800 bg-gray-900/60 p-5 shadow-lg"
      aria-label="보유 종목 목록"
    >
      {/* 헤더 */}
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">
            보유 종목
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
            KIS 실시간 시세 · 실시간 push + 30초 fallback
            {!isLoading && !isError && (
              <span className="ml-1 text-gray-600">· 총 {holdings.length}종목</span>
            )}
          </p>
        </div>
        <button
          onClick={() => void refetch()}
          disabled={isFetching}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
          aria-label="보유 종목 새로고침"
        >
          {isFetching ? '갱신 중...' : '새로고침'}
        </button>
      </div>

      {/* 본문 */}
      {isLoading ? (
        <div className="space-y-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-12 animate-pulse rounded-lg bg-gray-800/60" />
          ))}
        </div>
      ) : isError && isCredentialMissing ? (
        <div className="rounded-xl border border-yellow-800 bg-yellow-900/20 p-4 text-center text-sm text-yellow-300">
          {accountType === 'MOCK' ? '모의' : '실전'}계좌 KIS 키가 등록되지 않았습니다.
          <p className="mt-1 text-xs text-yellow-500">
            우측 상단 설정(⚙️)에서 {accountType === 'MOCK' ? '모의(VPS)' : '실전(PROD)'} 키를 등록한 뒤 다시 시도해 주세요.
          </p>
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-4 text-center text-sm text-red-400">
          보유 종목을 불러오지 못했습니다.
          <button
            onClick={() => void refetch()}
            className="ml-2 text-red-300 underline hover:text-red-200"
          >
            다시 시도
          </button>
        </div>
      ) : holdings.length === 0 ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-12 text-center">
          <p className="text-sm text-gray-500">보유 종목이 없습니다.</p>
          <p className="mt-1 text-xs text-gray-600">
            매수 주문이 체결되면 종목이 여기에 표시됩니다.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs uppercase tracking-wide text-gray-500">
                <th className="px-3 py-2 font-medium">종목코드</th>
                <th className="px-3 py-2 font-medium">종목명</th>
                <th className="px-3 py-2 text-right font-medium">수량</th>
                <th className="px-3 py-2 text-right font-medium">평균가</th>
                <th className="px-3 py-2 text-right font-medium">현재가</th>
                <th className="px-3 py-2 text-right font-medium">평가금액</th>
                <th className="px-3 py-2 text-right font-medium">평가손익</th>
                <th className="px-3 py-2 text-right font-medium">수익률</th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((h) => (
                <tr
                  key={h.code}
                  className="border-b border-gray-800/60 transition-colors hover:bg-gray-800/40"
                >
                  <td className="px-3 py-3">
                    <Link
                      to={`/symbol/${h.code}`}
                      className="font-mono text-blue-400 hover:text-blue-300 hover:underline"
                    >
                      {h.code}
                    </Link>
                  </td>
                  <td className="px-3 py-3 text-gray-200">{h.name}</td>
                  <td className="px-3 py-3 text-right tabular-nums text-gray-200">
                    {h.quantity.toLocaleString('ko-KR')}주
                  </td>
                  <td className="px-3 py-3 text-right tabular-nums text-gray-300">
                    {formatKrw(h.avgPrice)}
                  </td>
                  <td className="px-3 py-3 text-right tabular-nums text-gray-200">
                    {formatKrw(h.currentPrice)}
                  </td>
                  <td className="px-3 py-3 text-right tabular-nums text-gray-200">
                    {formatKrw(h.evaluationAmount)}
                  </td>
                  <td
                    className={`px-3 py-3 text-right tabular-nums font-semibold ${pnlColor(h.profitLoss)}`}
                  >
                    {h.profitLoss > 0 ? '+' : ''}
                    {formatKrw(h.profitLoss)}
                  </td>
                  <td
                    className={`px-3 py-3 text-right tabular-nums font-semibold ${pnlColor(h.profitLossRate)}`}
                  >
                    {formatRate(h.profitLossRate)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
