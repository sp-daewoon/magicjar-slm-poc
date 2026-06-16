/**
 * BalanceCard — 잔고 상세 카드 (T-ACCT Phase 4 — ADR 0081 + spec §5-3 옵션)
 *
 * 데이터: GET /api/account/balance (useAccountBalance — STOMP push primary + 30s polling fallback)
 * KIS inquire-balance output2 11 필드 노출:
 *   - 예수금: 총금액·D+1·D+2 (3 라인)
 *   - 자산: 총 평가금액·순자산금액 (2 라인)
 *   - 매입·평가: 매입합계·평가합계·평가손익 (3 라인)
 *   - 자산증감: 전일 대비 증감액·증감률 (옵션)
 *
 * 본 카드는 AccountSummaryCard보다 상세 — 잔고 디테일 확인용.
 * Dashboard 하단 또는 별도 페이지에서 사용 가능.
 */
import { useAccountBalance } from '../../api/hooks/useAccountBalance'
import type { AccountType } from '../../api/account'
import { extractStatus } from './errorStatus'

/** 원화 포맷 */
function formatKrw(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원`
}

/** 수익률 % */
function formatRate(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function pnlColor(value: number): string {
  if (value > 0) return 'text-red-400'
  if (value < 0) return 'text-blue-400'
  return 'text-gray-300'
}

interface BalanceCardProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /** B-1 (2026-05-20): 실전/모의 계좌 선택. */
  accountType?: AccountType
}

/** 잔고 라인 한 줄 */
function BalanceRow({
  label,
  value,
  emphasize = false,
  color,
}: {
  label: string
  value: string
  emphasize?: boolean
  color?: string
}) {
  return (
    <div className="flex items-center justify-between border-b border-gray-800/60 py-2 last:border-b-0">
      <span className="text-xs text-gray-500">{label}</span>
      <span
        className={[
          'tabular-nums',
          emphasize ? 'text-sm font-bold' : 'text-sm',
          color ?? 'text-gray-200',
        ].join(' ')}
      >
        {value}
      </span>
    </div>
  )
}

export function BalanceCard({ userId, accountType = 'REAL' }: BalanceCardProps) {
  const { data, isLoading, isError, error, refetch, isFetching } = useAccountBalance({
    userId,
    accountType,
  })

  const isCredentialMissing = extractStatus(error) === 412

  return (
    <section
      className="rounded-2xl border border-gray-800 bg-gray-900/60 p-5 shadow-lg"
      aria-label="잔고 상세"
    >
      {/* 헤더 */}
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">
            잔고 상세
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
            KIS inquire-balance output2 — 실시간 push + 30초 fallback
          </p>
        </div>
        <button
          onClick={() => void refetch()}
          disabled={isFetching}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
          aria-label="잔고 새로고침"
        >
          {isFetching ? '갱신 중...' : '새로고침'}
        </button>
      </div>

      {/* 본문 */}
      {isLoading ? (
        <div className="space-y-2">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-8 animate-pulse rounded bg-gray-800/40" />
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
          잔고를 불러오지 못했습니다.
          <button
            onClick={() => void refetch()}
            className="ml-2 text-red-300 underline hover:text-red-200"
          >
            다시 시도
          </button>
        </div>
      ) : !data ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-8 text-center text-sm text-gray-500">
          데이터 없음
        </div>
      ) : (
        <div className="grid gap-6 md:grid-cols-3">
          {/* 예수금 그룹 */}
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-600">
              예수금
            </h3>
            <BalanceRow label="총 예수금" value={formatKrw(data.cashBalance)} />
            <BalanceRow label="D+1 예수금" value={formatKrw(data.cashD1)} />
            <BalanceRow
              label="D+2 예수금 (실가용)"
              value={formatKrw(data.cashD2)}
              emphasize
            />
          </div>

          {/* 자산 그룹 */}
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-600">
              자산
            </h3>
            <BalanceRow
              label="총 평가금액"
              value={formatKrw(data.totalEvaluation)}
              emphasize
            />
            <BalanceRow label="순자산금액" value={formatKrw(data.netAssetValue)} />
            {data.assetIncreaseDecrease !== null &&
              data.assetIncreaseDecrease !== undefined && (
                <BalanceRow
                  label="전일 대비"
                  value={`${data.assetIncreaseDecrease > 0 ? '+' : ''}${formatKrw(data.assetIncreaseDecrease)}`}
                  color={pnlColor(data.assetIncreaseDecrease)}
                />
              )}
            {data.assetIncreaseDecreaseRate !== null &&
              data.assetIncreaseDecreaseRate !== undefined && (
                <BalanceRow
                  label="자산 증감률"
                  value={formatRate(data.assetIncreaseDecreaseRate)}
                  color={pnlColor(data.assetIncreaseDecreaseRate)}
                />
              )}
          </div>

          {/* 매입·평가 그룹 */}
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-600">
              매입·평가
            </h3>
            <BalanceRow
              label="매입금액 합계"
              value={formatKrw(data.totalPurchaseAmount)}
            />
            <BalanceRow
              label="평가금액 합계"
              value={formatKrw(data.totalHoldingEvaluation)}
            />
            <BalanceRow
              label="평가손익 합계"
              value={`${data.totalProfitLoss > 0 ? '+' : ''}${formatKrw(data.totalProfitLoss)}`}
              emphasize
              color={pnlColor(data.totalProfitLoss)}
            />
          </div>
        </div>
      )}
    </section>
  )
}
