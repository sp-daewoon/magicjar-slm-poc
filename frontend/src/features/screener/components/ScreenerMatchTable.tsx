/**
 * ScreenerMatchTable — 매칭 종목 랭킹 테이블
 *
 * columns: rank · 종목명/코드 · 점수(breakdown) · 시그널 reason
 * 체크박스 선택 → onSelectionChange 콜백 → WatchlistBulkRegisterButton
 * sortable column: rank(default) · score
 *
 * ADR 0071 §9-4 + spec §2-4 정합
 */
import { useState } from 'react'
import type { ScreenerMatchDto } from '../api/screenerApi'
import { ScreenerScoreBreakdown } from './ScreenerScoreBreakdown'

type SortKey = 'rank' | 'score'

interface Props {
  matches: ScreenerMatchDto[]
  selectedCodes: Set<string>
  onSelectionChange: (codes: Set<string>) => void
}

export function ScreenerMatchTable({ matches, selectedCodes, onSelectionChange }: Props) {
  const [sortKey, setSortKey] = useState<SortKey>('rank')
  const [sortAsc, setSortAsc] = useState(true)

  if (matches.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-gray-500">
        매칭된 종목이 없습니다.
      </div>
    )
  }

  const sorted = [...matches].sort((a, b) => {
    const aVal = sortKey === 'rank' ? a.rank : a.score
    const bVal = sortKey === 'rank' ? b.rank : b.score
    return sortAsc ? aVal - bVal : bVal - aVal
  })

  const handleHeaderClick = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc((prev) => !prev)
    } else {
      setSortKey(key)
      setSortAsc(key === 'rank') // rank: 오름차순, score: 내림차순
    }
  }

  const handleToggleAll = () => {
    if (selectedCodes.size === matches.length) {
      onSelectionChange(new Set())
    } else {
      onSelectionChange(new Set(matches.map((m) => m.instrumentCode)))
    }
  }

  const handleToggle = (code: string) => {
    const next = new Set(selectedCodes)
    if (next.has(code)) {
      next.delete(code)
    } else {
      next.add(code)
    }
    onSelectionChange(next)
  }

  const allChecked = selectedCodes.size === matches.length && matches.length > 0
  const someChecked = selectedCodes.size > 0 && selectedCodes.size < matches.length

  const SortIcon = ({ col }: { col: SortKey }) => {
    if (sortKey !== col) return <span className="ml-1 text-gray-600">↕</span>
    return <span className="ml-1 text-blue-400">{sortAsc ? '↑' : '↓'}</span>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-800 text-xs text-gray-500">
            <th className="pb-2 pr-2 text-left">
              <input
                type="checkbox"
                checked={allChecked}
                ref={(el) => {
                  if (el) el.indeterminate = someChecked
                }}
                onChange={handleToggleAll}
                aria-label="전체 선택"
                className="cursor-pointer accent-emerald-500"
              />
            </th>
            <th
              className="cursor-pointer pb-2 pr-3 text-left font-medium hover:text-gray-300"
              onClick={() => handleHeaderClick('rank')}
            >
              순위
              <SortIcon col="rank" />
            </th>
            <th className="pb-2 pr-3 text-left font-medium">종목</th>
            <th
              className="cursor-pointer pb-2 pr-3 text-right font-medium hover:text-gray-300"
              onClick={() => handleHeaderClick('score')}
            >
              점수
              <SortIcon col="score" />
            </th>
            <th className="pb-2 text-left font-medium">구성</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800/50">
          {sorted.map((match) => {
            const isChecked = selectedCodes.has(match.instrumentCode)
            return (
              <tr
                key={match.instrumentCode}
                className={`cursor-pointer transition-colors hover:bg-gray-800/40 ${
                  isChecked ? 'bg-emerald-900/10' : ''
                }`}
                onClick={() => handleToggle(match.instrumentCode)}
              >
                <td className="py-2 pr-2">
                  <input
                    type="checkbox"
                    checked={isChecked}
                    onChange={() => handleToggle(match.instrumentCode)}
                    onClick={(e) => e.stopPropagation()}
                    aria-label={`${match.instrumentName} 선택`}
                    className="cursor-pointer accent-emerald-500"
                  />
                </td>
                <td className="py-2 pr-3 text-center tabular-nums text-gray-400">
                  {match.rank}
                </td>
                <td className="py-2 pr-3">
                  <div className="font-medium text-white">{match.instrumentName}</div>
                  <div className="text-xs text-gray-500">{match.instrumentCode}</div>
                  {match.reason && (
                    <div
                      className="mt-0.5 truncate text-xs text-gray-600"
                      title={match.reason}
                      style={{ maxWidth: 220 }}
                    >
                      {match.reason}
                    </div>
                  )}
                </td>
                <td className="py-2 pr-3 text-right tabular-nums text-emerald-400">
                  {(match.score * 100).toFixed(1)}
                </td>
                <td className="py-2" style={{ width: 56 }}>
                  <ScreenerScoreBreakdown
                    scoreComponents={match.scoreComponents}
                    compact
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
