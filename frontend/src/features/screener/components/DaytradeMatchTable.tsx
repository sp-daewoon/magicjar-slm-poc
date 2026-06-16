/**
 * DaytradeMatchTable — 단타 발굴 후보 랭킹 테이블 (TOP N)
 *
 * columns: rank · 종목명/코드 · 종합점수(bar) · 점수 구성(compact) · 펼침
 * row 펼침 → DaytradeScoreBreakdown 전체 모드(왜 후보인가 수치 분해) + reason + 액션.
 *             로컬 신호 해석 + 사용자 명시 AI 해석 진입점.
 * sortable column: rank(default 오름차순) · score(내림차순)
 *
 * ### owner 가드 (방향성 보존 — 매우 중요)
 * - 발굴 결과를 watchlist에 **자동 삽입하지 않는다**. 자동 삽입 버튼·일괄 등록 0 (WatchlistBulkRegisterButton 미사용).
 * - 종목을 관심종목에 넣고 싶으면 사용자가 row 펼침 → "관심종목 추가" 를 **명시적으로** 클릭해야 한다 (onAddWatchlist 콜백).
 * - 자동 주문 버튼 0 — 발굴은 후보 출발점이지 매매 결정이 아니다.
 *
 * ### 페이퍼 승격 다중선택 (ADR 0122 §8-1 LOCK)
 * - selectionMode=true 면 row 별 체크박스 노출 — 사용자가 종목을 다중선택한다.
 * - 선택은 페이퍼 승격(모의로 굴려보기)의 입력일 뿐 **자동 승격이 아니다**. 실제 promote 는
 *   발굴 화면의 명시적 "모의로 굴려보기" 버튼 → 확인 모달에서만 발생(자동 진입 0 — ADR 0122 §1-2).
 */
import { Fragment, useState } from 'react'
import { Link } from 'react-router-dom'
import type { DaytradeMatchDto } from '../api/daytradeDiscoverApi'
import { DaytradeScoreBreakdown } from './DaytradeScoreBreakdown'
import { AiExplainEntry } from '../../ai/components/AiExplainEntry'
import {
  buildDaytradeSignalAiContext,
  buildDaytradeSignalContext,
  buildDaytradeSignalNavigationState,
} from '../../../lib/daytrade/signalInterpretation'

type SortKey = 'rank' | 'score'

interface Props {
  matches: DaytradeMatchDto[]
  /** 사용자가 명시적으로 관심종목 추가 클릭 시 (자동 삽입 아님) — 미제공 시 버튼 미표시 */
  onAddWatchlist?: (code: string) => void
  /** 관심종목 추가 진행 중인 code (버튼 disable·라벨) */
  addingCode?: string | null
  /** 이미 관심종목에 등록된 code 집합 (중복 등록 회피 — read only) */
  watchedCodes?: Set<string>
  /** 페이퍼 승격용 다중선택 모드 — true 면 row 체크박스 노출 (ADR 0122 §8-1) */
  selectionMode?: boolean
  /** 선택된 code 집합 (selectionMode=true 일 때) */
  selectedCodes?: Set<string>
  /** 선택 토글 콜백 (selectionMode=true 일 때) */
  onToggleSelect?: (code: string) => void
  /** 사용자 명시 AI 해석용 userId. 미제공 시 AI 버튼 disabled. */
  userId?: number | null
}

export function DaytradeMatchTable({
  matches,
  onAddWatchlist,
  addingCode = null,
  watchedCodes,
  selectionMode = false,
  selectedCodes,
  onToggleSelect,
  userId = null,
}: Props) {
  const [sortKey, setSortKey] = useState<SortKey>('rank')
  const [sortAsc, setSortAsc] = useState(true)
  const [expanded, setExpanded] = useState<string | null>(null)

  if (matches.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-gray-500">
        발굴된 후보가 없습니다.
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

  const SortIcon = ({ col }: { col: SortKey }) => {
    if (sortKey !== col) return <span className="ml-1 text-gray-600">↕</span>
    return <span className="ml-1 text-blue-400">{sortAsc ? '↑' : '↓'}</span>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-800 text-xs text-gray-500">
            {selectionMode && <th className="pb-2 pr-2 text-center font-medium" aria-label="선택" />}
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
              종합점수
              <SortIcon col="score" />
            </th>
            <th className="pb-2 pr-3 text-left font-medium">구성</th>
            <th className="pb-2 text-right font-medium" />
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800/50">
          {sorted.map((match) => {
            const isExpanded = expanded === match.instrumentCode
            const isWatched = watchedCodes?.has(match.instrumentCode) ?? false
            const isAdding = addingCode === match.instrumentCode
            const isSelected = selectedCodes?.has(match.instrumentCode) ?? false
            const scorePct = match.score * 100
            const signalContext = buildDaytradeSignalContext(match)
            return (
              <Fragment key={match.instrumentCode}>
                <tr
                  className={`cursor-pointer transition-colors hover:bg-gray-800/40 ${
                    isExpanded ? 'bg-gray-800/30' : ''
                  } ${isSelected ? 'bg-amber-950/20' : ''}`}
                  onClick={() =>
                    setExpanded(isExpanded ? null : match.instrumentCode)
                  }
                >
                  {selectionMode && (
                    <td className="py-2 pr-2 text-center">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onClick={(e) => e.stopPropagation()}
                        onChange={() => onToggleSelect?.(match.instrumentCode)}
                        aria-label={`${match.instrumentName} 모의 승격 선택`}
                        className="cursor-pointer accent-amber-500"
                      />
                    </td>
                  )}
                  <td className="py-2 pr-3 text-center tabular-nums text-gray-400">
                    {match.rank}
                  </td>
                  <td className="py-2 pr-3">
                    <div className="font-medium text-white">{match.instrumentName}</div>
                    <div className="text-xs text-gray-500">{match.instrumentCode}</div>
                  </td>
                  <td className="py-2 pr-3 text-right">
                    <div className="tabular-nums text-emerald-400">
                      {scorePct.toFixed(1)}
                    </div>
                    <div className="mt-1 h-1 w-16 overflow-hidden rounded-full bg-gray-800">
                      <div
                        className="h-full rounded-full bg-emerald-500"
                        style={{ width: `${Math.min(100, scorePct)}%` }}
                      />
                    </div>
                  </td>
                  <td className="py-2 pr-3" style={{ width: 56 }}>
                    <DaytradeScoreBreakdown
                      scoreComponents={match.scoreComponents}
                      compact
                    />
                  </td>
                  <td className="py-2 text-right text-xs text-gray-500">
                    {isExpanded ? '▲' : '▼'}
                  </td>
                </tr>
                {isExpanded && (
                  <tr className="bg-gray-900/60">
                    <td colSpan={selectionMode ? 6 : 5} className="px-3 py-4">
                      <div className="grid gap-4 md:grid-cols-2">
                        {/* 점수 분해 — 왜 후보인가 */}
                        <div>
                          <p className="mb-2 text-xs font-medium text-gray-400">
                            점수 구성 (종합 {scorePct.toFixed(1)}점)
                          </p>
                          <DaytradeScoreBreakdown
                            scoreComponents={match.scoreComponents}
                          />
                        </div>
                        {/* 시그널 사유 + 액션 */}
                        <div className="space-y-3">
                          <div>
                            <p className="mb-1 text-xs font-medium text-gray-400">
                              신호 해석
                            </p>
                            <p className="text-xs leading-relaxed text-gray-300">
                              {signalContext.summary}
                            </p>
                            <ul className="mt-2 space-y-1 text-[11px] leading-relaxed text-gray-500">
                              {signalContext.reasonComments.slice(0, 3).map((comment) => (
                                <li key={comment}>· {comment}</li>
                              ))}
                              {signalContext.scoreComments.slice(0, 2).map((comment) => (
                                <li key={comment}>· {comment}</li>
                              ))}
                            </ul>
                          </div>
                          {match.reason && (
                            <div>
                              <details>
                                <summary className="cursor-pointer text-xs font-medium text-gray-400 hover:text-gray-300">
                                  시그널 원문 사유
                                </summary>
                                <p className="mt-1 break-words font-mono text-[11px] leading-relaxed text-gray-500">
                                  {match.reason}
                                </p>
                              </details>
                            </div>
                          )}
                          <div className="flex flex-wrap items-center gap-2">
                            <Link
                              to={`/symbol/${match.instrumentCode}`}
                              state={buildDaytradeSignalNavigationState(match)}
                              onClick={(e) => e.stopPropagation()}
                              className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs text-gray-200 hover:bg-gray-700"
                            >
                              종목 상세
                            </Link>
                            {/* 명시적 관심종목 추가 — 자동 삽입 아님 (owner 가드) */}
                            {onAddWatchlist &&
                              (isWatched ? (
                                <span className="rounded-lg bg-emerald-900/40 px-3 py-1.5 text-xs text-emerald-300">
                                  관심종목 등록됨
                                </span>
                              ) : (
                                <button
                                  type="button"
                                  disabled={isAdding}
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    onAddWatchlist(match.instrumentCode)
                                  }}
                                  className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
                                >
                                  {isAdding ? '추가 중...' : '관심종목 추가'}
                                </button>
                              ))}
                          </div>
                          <div className="rounded-lg border border-purple-950/60 bg-purple-950/10 p-2">
                            <AiExplainEntry
                              type="SIGNAL"
                              userId={userId}
                              context={buildDaytradeSignalAiContext(signalContext)}
                              disabledMessage="사용자 설정 완료 후 단타 신호를 AI로 해석할 수 있습니다."
                            />
                          </div>
                          <p className="text-[11px] text-gray-600">
                            발굴은 후보 출발점입니다. 자동 매매·자동 등록은 하지 않습니다.
                          </p>
                        </div>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
