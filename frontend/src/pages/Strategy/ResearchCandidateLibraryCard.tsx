import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { useModalBehavior } from '../../hooks/useModalBehavior'
import { strategyApi, type StrategyDto } from '../../api/strategy'
import {
  strategyResearchApi,
  strategyResearchErrorMessage,
  type StrategyResearchCandidateDefinitionDto,
} from '../../api/strategyResearch'

interface Props {
  /** 등록된 사용자 ID (숫자). null이면 내 전략 추가 기능 비활성. */
  registeredUserId: number | null
}

/**
 * 리서치 후보 전략 라이브러리 (research-candidates-db / V74).
 *
 * 사용자 보고(2026-06-08): 18종 builtin + 내 후보를 리서치 화면에 인라인 전부 노출 →
 * 화면 전체를 가림. → 컴팩트 진입 버튼/요약만 인라인, 전체 목록·관리는 모달로 이동.
 *
 * - DB(strategy_research_candidate)에 등록된 후보 목록을 모달에서 렌더(리서치 실행이 사용하는 후보).
 * - "내 전략을 후보로 추가" 진입점: 전략 선택 드롭다운 + 추가 버튼(모달 내).
 * - 후보 삭제(builtin 포함, 모달 내). 리서치 실행 동작 자체는 무변경.
 * - 정렬: 내 전략(builtIn=false) 최상단(최신순) → builtin(sortOrder 순).
 */
export default function ResearchCandidateLibraryCard({ registeredUserId }: Props) {
  const [open, setOpen] = useState(false)

  const candidatesQuery = useQuery({
    queryKey: ['strategy-research', 'candidates'],
    queryFn: () => strategyResearchApi.getCandidates(false),
  })

  const candidates = candidatesQuery.data ?? []
  const builtinCount = useMemo(() => candidates.filter((c) => c.builtIn).length, [candidates])
  const userCount = candidates.length - builtinCount

  return (
    <section
      className="mb-4 rounded-lg border border-gray-800 bg-gray-900 p-4"
      aria-labelledby="research-candidate-library-heading"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 id="research-candidate-library-heading" className="text-sm font-semibold text-gray-200">
            후보 전략 라이브러리
          </h2>
          <p className="mt-0.5 text-xs text-gray-500">
            리서치 실행이 평가하는 후보 전략 목록(DB 관리)
            {' · '}
            <span className="text-indigo-300">내 전략 {userCount}개</span>
            {' · '}
            <span className="text-gray-400">기본 {builtinCount}개</span>
          </p>
        </div>
        <button
          type="button"
          className="flex-shrink-0 rounded border border-gray-700 bg-gray-950 px-3 py-1.5 text-xs font-medium text-gray-200 hover:bg-gray-800"
          onClick={() => setOpen(true)}
          data-testid="research-candidate-library-open"
        >
          후보 라이브러리 관리
        </button>
      </div>

      {open && (
        <ResearchCandidateLibraryModal
          registeredUserId={registeredUserId}
          onClose={() => setOpen(false)}
        />
      )}
    </section>
  )
}

function XIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

interface ModalProps {
  registeredUserId: number | null
  onClose: () => void
}

/**
 * 후보 라이브러리 관리 모달.
 * - 전체 후보 목록 + 추가/삭제 관리 기능 전부 포함.
 * - 목록이 길어 모달 max-height + 내부 스크롤(프리셋 설정 6개 초과 스크롤 선례 정합).
 * - useModalBehavior: ESC 닫기 + body scroll lock.
 */
function ResearchCandidateLibraryModal({ registeredUserId, onClose }: ModalProps) {
  const queryClient = useQueryClient()
  const [selectedStrategyId, setSelectedStrategyId] = useState<string>('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionNotice, setActionNotice] = useState<string | null>(null)

  useModalBehavior({ open: true, onClose })

  const candidatesQuery = useQuery({
    queryKey: ['strategy-research', 'candidates'],
    queryFn: () => strategyResearchApi.getCandidates(false),
  })

  const strategiesQuery = useQuery({
    queryKey: ['strategy-research', 'my-strategies', registeredUserId],
    queryFn: () => strategyApi.list(registeredUserId ?? undefined),
    enabled: registeredUserId != null,
  })

  const addMutation = useMutation({
    mutationFn: (strategyId: number) =>
      strategyResearchApi.addCandidateFromStrategy({
        userId: String(registeredUserId),
        strategyId,
      }),
    onSuccess: (created) => {
      setActionError(null)
      setActionNotice(`후보로 추가되었습니다: ${created.title}`)
      setSelectedStrategyId('')
      void queryClient.invalidateQueries({ queryKey: ['strategy-research', 'candidates'] })
    },
    onError: (error) => {
      setActionNotice(null)
      setActionError(strategyResearchErrorMessage(error))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => strategyResearchApi.deleteCandidate(id),
    onSuccess: () => {
      setActionError(null)
      setActionNotice('후보를 삭제했습니다.')
      void queryClient.invalidateQueries({ queryKey: ['strategy-research', 'candidates'] })
    },
    onError: (error) => {
      setActionNotice(null)
      setActionError(strategyResearchErrorMessage(error))
    },
  })

  const candidates = candidatesQuery.data ?? []

  // 정렬: 내 전략(builtIn=false) 최상단(최신 updatedAt 우선) → builtin(sortOrder 오름차순).
  const sortedCandidates = useMemo(() => {
    const userCandidates = candidates
      .filter((c) => !c.builtIn)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    const builtinCandidates = candidates
      .filter((c) => c.builtIn)
      .sort((a, b) => a.sortOrder - b.sortOrder)
    return { userCandidates, builtinCandidates }
  }, [candidates])

  const handleAdd = () => {
    const strategyId = Number(selectedStrategyId)
    if (!Number.isFinite(strategyId) || strategyId <= 0) {
      setActionNotice(null)
      setActionError('후보로 추가할 전략을 선택하세요.')
      return
    }
    addMutation.mutate(strategyId)
  }

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) onClose()
  }

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-4"
      onClick={handleOverlayClick}
      role="dialog"
      aria-modal="true"
      aria-label="후보 전략 라이브러리 관리"
    >
      <div
        className="flex w-full max-w-2xl flex-col rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
        style={{ maxHeight: '88vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <header className="flex flex-shrink-0 items-center justify-between gap-3 border-b border-gray-800 px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-white">후보 전략 라이브러리</h2>
            <p className="mt-0.5 truncate text-xs text-gray-500">
              리서치 실행이 평가하는 후보 전략 목록(DB 관리)
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="flex-shrink-0 rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            <XIcon />
          </button>
        </header>

        {/* 내 전략 추가 + 알림 (고정) */}
        <div className="flex-shrink-0 border-b border-gray-800 px-5 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="research-candidate-strategy-select" className="text-xs text-gray-400">
              내 전략을 후보로 추가
            </label>
            <select
              id="research-candidate-strategy-select"
              className="min-w-[14rem] rounded border border-gray-700 bg-gray-950 px-2 py-1 text-xs text-gray-200 disabled:opacity-50"
              value={selectedStrategyId}
              onChange={(e) => setSelectedStrategyId(e.target.value)}
              disabled={registeredUserId == null || strategiesQuery.isLoading}
            >
              <option value="">전략 선택…</option>
              {(strategiesQuery.data ?? []).map((s: StrategyDto) => (
                <option key={s.id} value={s.id}>
                  {s.nameKo || s.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="rounded bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
              onClick={handleAdd}
              disabled={registeredUserId == null || !selectedStrategyId || addMutation.isPending}
            >
              {addMutation.isPending ? '추가 중…' : '후보로 추가'}
            </button>
          </div>

          {registeredUserId == null && (
            <p className="mt-2 text-xs text-amber-300">사용자 등록 후 내 전략을 후보로 추가할 수 있습니다.</p>
          )}
          {actionError && (
            <div className="mt-2 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-xs text-red-200">
              {actionError}
            </div>
          )}
          {actionNotice && (
            <div className="mt-2 rounded border border-emerald-900 bg-emerald-950/30 px-3 py-2 text-xs text-emerald-200">
              {actionNotice}
            </div>
          )}
        </div>

        {/* 후보 목록 (내부 스크롤) */}
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-5 py-4">
          {candidatesQuery.isLoading && <p className="text-xs text-gray-500">후보를 불러오는 중…</p>}
          {!candidatesQuery.isLoading && candidates.length === 0 && (
            <p className="text-xs text-gray-500">등록된 후보 전략이 없습니다.</p>
          )}

          {!candidatesQuery.isLoading && candidates.length > 0 && (
            <>
              <CandidateGroup
                title="내 후보"
                count={sortedCandidates.userCandidates.length}
                emptyText="후보로 추가한 내 전략이 없습니다."
                candidates={sortedCandidates.userCandidates}
                onDelete={(id) => deleteMutation.mutate(id)}
                deletePending={deleteMutation.isPending}
              />
              <CandidateGroup
                title="기본 제공"
                count={sortedCandidates.builtinCandidates.length}
                emptyText="기본 제공 후보가 없습니다."
                candidates={sortedCandidates.builtinCandidates}
                onDelete={(id) => deleteMutation.mutate(id)}
                deletePending={deleteMutation.isPending}
              />
            </>
          )}
        </div>

        {/* 푸터 */}
        <footer className="flex flex-shrink-0 items-center justify-end border-t border-gray-800 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm text-gray-400 hover:bg-gray-800 hover:text-gray-200"
          >
            닫기
          </button>
        </footer>
      </div>
    </div>
  )
}

interface CandidateGroupProps {
  title: string
  count: number
  emptyText: string
  candidates: StrategyResearchCandidateDefinitionDto[]
  onDelete: (id: number) => void
  deletePending: boolean
}

function CandidateGroup({ title, count, emptyText, candidates, onDelete, deletePending }: CandidateGroupProps) {
  return (
    <div>
      <h3 className="mb-1.5 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
        {title}
        <span className="rounded-full bg-gray-800 px-1.5 py-0.5 text-[10px] font-medium text-gray-400">
          {count}
        </span>
      </h3>
      {candidates.length === 0 ? (
        <p className="text-xs text-gray-600">{emptyText}</p>
      ) : (
        <div className="space-y-2">
          {candidates.map((candidate) => (
            <div
              key={candidate.id}
              className="rounded border border-gray-800 bg-gray-950/60 px-3 py-2"
              data-testid="research-candidate-row"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-gray-100">{candidate.title}</span>
                  <span
                    className={`rounded px-1.5 py-0.5 text-[10px] ${
                      candidate.builtIn ? 'bg-gray-800 text-gray-400' : 'bg-indigo-950 text-indigo-300'
                    }`}
                  >
                    {candidate.builtIn ? '기본' : '내 전략'}
                  </span>
                </div>
                <button
                  type="button"
                  className="rounded border border-red-900 px-2 py-0.5 text-[11px] text-red-300 hover:bg-red-950/40 disabled:opacity-50"
                  onClick={() => onDelete(candidate.id)}
                  disabled={deletePending}
                  aria-label={`후보 삭제: ${candidate.title}`}
                >
                  후보 삭제
                </button>
              </div>
              <p className="mt-1 text-xs text-gray-500">{candidate.marketFit}</p>
              <code className="mt-1 block break-all text-[11px] text-sky-300">{candidate.entryDsl}</code>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
