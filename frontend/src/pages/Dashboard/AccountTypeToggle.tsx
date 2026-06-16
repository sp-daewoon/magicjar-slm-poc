/**
 * AccountTypeToggle — 실전/모의 계좌 토글 (B-1 Dashboard accountType toggle)
 *
 * Dashboard 헤더 우측에 배치. 클릭 시 부모의 `accountType` 상태를 변경하여
 * AccountSummaryCard·HoldingsTable·BalanceCard 가 동일한 type 의 KIS 응답을 조회.
 *
 * - 활성 토글: 실전=파랑, 모의=보라 (한국 표준 + 시각 분리)
 * - 키보드 접근: role="tablist" + aria-selected
 * - mock 합성 0 LOCK — 토글 자체는 UI 상태만 변경, KIS 호출은 hook 책임
 */
import type { AccountType } from '../../api/account'

interface AccountTypeToggleProps {
  value: AccountType
  onChange: (next: AccountType) => void
  /** 추가 className (위치 정렬용) */
  className?: string
}

const TYPES: Array<{ id: AccountType; label: string; activeClass: string }> = [
  {
    id: 'REAL',
    label: '실전',
    activeClass: 'bg-blue-600 text-white border-blue-500',
  },
  {
    id: 'MOCK',
    label: '모의',
    activeClass: 'bg-purple-600 text-white border-purple-500',
  },
]

export function AccountTypeToggle({
  value,
  onChange,
  className,
}: AccountTypeToggleProps) {
  return (
    <div
      role="tablist"
      aria-label="계좌 유형 선택"
      className={[
        'inline-flex items-center gap-1 rounded-lg border border-gray-800 bg-gray-900/60 p-1',
        className ?? '',
      ].join(' ')}
    >
      {TYPES.map((t) => {
        const active = value === t.id
        return (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={active}
            onClick={() => onChange(t.id)}
            className={[
              'rounded-md border px-3 py-1.5 text-xs font-semibold transition-colors',
              active
                ? t.activeClass
                : 'border-transparent text-gray-400 hover:bg-gray-800 hover:text-gray-200',
            ].join(' ')}
          >
            {t.label}
          </button>
        )
      })}
    </div>
  )
}
