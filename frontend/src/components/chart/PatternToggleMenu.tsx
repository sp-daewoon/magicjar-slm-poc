/**
 * PatternToggleMenu — 57 패턴 카탈로그 picker 컴포넌트
 *
 * PR-38 spec R3 §4 LOCK:
 *   - 57 catalog 분류: Single 14 / Double 16 / Triple 27
 *   - 사용자 토글 id 집합을 부모에게 onSelectionChange로 전달
 *   - localStorage에 사용자 선호 저장
 *   - R1 active 10건 우선 노출 (섹션 상단 표시)
 *   - R2 stub 47건은 dim/disabled 표시 + "(준비 중)" 안내
 *   - 0건 선택 시 → 패턴 overlay 비활성 (usePatterns enabled=false)
 *
 * 룰 5 (W2): LLM provider import 0건
 */
import { useCallback, useState } from 'react'
import { PATTERN_CATALOG, PATTERN_R1_ACTIVE_IDS, type PatternCatalogItem } from '../../api/patterns'

// ──────────────────────────────────────────────
// localStorage 키
// ──────────────────────────────────────────────

const STORAGE_KEY = 'magicjar.pattern.selected'

function loadFromStorage(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw) as string[]
    // R1 active 패턴만 유효 — R2 stub ID가 저장돼 있어도 무시 (active 전용 필터)
    return new Set(arr.filter((id) => PATTERN_CATALOG.some((p) => p.id === id && p.active)))
  } catch {
    return new Set()
  }
}

function saveToStorage(ids: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]))
  } catch {
    // quota exceeded 등 graceful 무시
  }
}

// ──────────────────────────────────────────────
// 카테고리 분류 레이블
// ──────────────────────────────────────────────

const GROUP_LABELS: Record<string, string> = {
  single: '단일봉 (Single)',
  double: '이중봉 (Double)',
  triple: '삼중봉+ (Triple)',
}

// ──────────────────────────────────────────────
// 단일 토글 항목
// ──────────────────────────────────────────────

interface PatternItemProps {
  item: PatternCatalogItem
  checked: boolean
  onToggle: (id: string) => void
}

function PatternItem({ item, checked, onToggle }: PatternItemProps) {
  const isActive = item.active

  const directionBadge =
    item.defaultDirection === 'BULL' ? (
      <span className="text-[9px] font-bold text-emerald-400">상승</span>
    ) : item.defaultDirection === 'BEAR' ? (
      <span className="text-[9px] font-bold text-red-400">하락</span>
    ) : (
      <span className="text-[9px] text-gray-600">중립</span>
    )

  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      aria-disabled={!isActive}
      onClick={() => isActive && onToggle(item.id)}
      className={[
        'flex w-full items-center gap-2 rounded px-2 py-1 text-left text-xs transition-colors',
        'focus:outline-none focus:ring-1 focus:ring-blue-400',
        isActive
          ? checked
            ? 'bg-blue-900/30 text-gray-200 hover:bg-blue-900/50'
            : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200'
          : 'cursor-not-allowed text-gray-700',
      ].join(' ')}
    >
      {/* 체크박스 아이콘 */}
      <span
        className={[
          'flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded border',
          isActive
            ? checked
              ? 'border-blue-400 bg-blue-500'
              : 'border-gray-600 bg-transparent'
            : 'border-gray-800 bg-transparent',
        ].join(' ')}
        aria-hidden="true"
      >
        {checked && isActive && (
          <svg className="h-2.5 w-2.5 text-white" fill="currentColor" viewBox="0 0 12 12">
            <path d="M10.28 2.28a.75.75 0 0 0-1.06 0L4.5 7 2.78 5.28a.75.75 0 0 0-1.06 1.06l2.25 2.25a.75.75 0 0 0 1.06 0l5.25-5.25a.75.75 0 0 0 0-1.06Z" />
          </svg>
        )}
      </span>

      {/* 패턴 이름 */}
      <span className="flex-1 truncate">
        {item.nameKo}
        {!isActive && (
          <span className="ml-1 text-[9px] text-gray-700">(준비 중)</span>
        )}
      </span>

      {/* 방향 배지 */}
      {directionBadge}

      {/* R1 active 배지 */}
      {isActive && (
        <span className="rounded bg-emerald-900/40 px-1 py-0.5 text-[8px] font-semibold text-emerald-400">
          R1
        </span>
      )}
    </button>
  )
}

// ──────────────────────────────────────────────
// PatternToggleMenu
// ──────────────────────────────────────────────

export interface PatternToggleMenuProps {
  /** 현재 선택된 패턴 id Set */
  selectedIds: ReadonlySet<string>
  /** 토글 시 콜백 */
  onSelectionChange: (ids: Set<string>) => void
  /** 패널 닫기 콜백 */
  onClose: () => void
}

/**
 * 57 패턴 카탈로그 picker 패널.
 *
 * - Single / Double / Triple 3개 섹션으로 분류
 * - R1 active 10건 체크 가능, R2 stub 47건 dim/disabled
 * - localStorage 자동 저장/복원
 */
export function PatternToggleMenu({ selectedIds, onSelectionChange, onClose }: PatternToggleMenuProps) {
  const [openGroups, setOpenGroups] = useState<Set<string>>(new Set(['single', 'double', 'triple']))

  const handleToggle = useCallback(
    (id: string) => {
      const next = new Set(selectedIds)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      saveToStorage(next)
      onSelectionChange(next)
    },
    [selectedIds, onSelectionChange],
  )

  const handleGroupToggle = useCallback((group: string) => {
    setOpenGroups((prev) => {
      const next = new Set(prev)
      if (next.has(group)) {
        next.delete(group)
      } else {
        next.add(group)
      }
      return next
    })
  }, [])

  // 전체 선택/해제 (R1 active만)
  const handleSelectAllActive = useCallback(() => {
    const allActive = new Set(PATTERN_CATALOG.filter((p) => p.active).map((p) => p.id))
    const allSelected = [...allActive].every((id) => selectedIds.has(id))
    const next = allSelected ? new Set<string>() : allActive
    saveToStorage(next)
    onSelectionChange(next)
  }, [selectedIds, onSelectionChange])

  const activeCount = PATTERN_CATALOG.filter((p) => p.active).length
  const selectedActiveCount = [...selectedIds].filter((id) => PATTERN_R1_ACTIVE_IDS.has(id)).length
  const allActiveSelected = selectedActiveCount === activeCount && activeCount > 0

  // group별 아이템 분리
  const groups: Array<{ key: string; items: PatternCatalogItem[] }> = [
    { key: 'single', items: PATTERN_CATALOG.filter((p) => p.group === 'single') },
    { key: 'double', items: PATTERN_CATALOG.filter((p) => p.group === 'double') },
    { key: 'triple', items: PATTERN_CATALOG.filter((p) => p.group === 'triple') },
  ]

  return (
    <div
      className="flex h-full flex-col overflow-hidden rounded-lg border border-gray-700 bg-gray-900 text-gray-300"
      role="dialog"
      aria-label="패턴 선택 패널"
    >
      {/* 헤더 */}
      <div className="flex items-center justify-between border-b border-gray-800 px-3 py-2">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-gray-200">패턴 선택</span>
          {selectedIds.size > 0 && (
            <span className="rounded-full bg-blue-600 px-1.5 py-0.5 text-[10px] font-bold text-white leading-none">
              {selectedIds.size}
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded p-0.5 text-gray-500 hover:text-gray-200 transition-colors focus:outline-none focus:ring-1 focus:ring-blue-400"
          aria-label="패턴 선택 패널 닫기"
        >
          <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* 전체 선택/해제 */}
      <div className="border-b border-gray-800 px-3 py-1.5">
        <button
          type="button"
          onClick={handleSelectAllActive}
          className="text-[10px] text-blue-400 hover:text-blue-300 transition-colors focus:outline-none"
        >
          {allActiveSelected ? '전체 해제' : `전체 선택 (R1 ${activeCount}건)`}
        </button>
        <span className="ml-2 text-[10px] text-gray-600">
          R2 stub {PATTERN_CATALOG.length - activeCount}건 준비 중
        </span>
      </div>

      {/* 스크롤 패널 */}
      <div className="flex-1 overflow-y-auto px-1.5 py-1">
        {groups.map(({ key, items }) => (
          <div key={key} className="mb-1">
            {/* 그룹 헤더 */}
            <button
              type="button"
              onClick={() => handleGroupToggle(key)}
              className="flex w-full items-center gap-1.5 rounded px-1.5 py-1 text-left text-[10px] font-semibold text-gray-500 hover:text-gray-300 transition-colors focus:outline-none"
              aria-expanded={openGroups.has(key)}
            >
              <svg
                className={`h-3 w-3 shrink-0 transition-transform ${openGroups.has(key) ? 'rotate-90' : ''}`}
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
                aria-hidden="true"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="m9 18 6-6-6-6" />
              </svg>
              {GROUP_LABELS[key]}
              <span className="ml-auto text-[9px] text-gray-700">
                {items.filter((p) => p.active).length}건 활성
              </span>
            </button>

            {/* 그룹 아이템 */}
            {openGroups.has(key) && (
              <div className="ml-2 space-y-0.5">
                {items.map((item) => (
                  <PatternItem
                    key={item.id}
                    item={item}
                    checked={selectedIds.has(item.id)}
                    onToggle={handleToggle}
                  />
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* 푸터 안내 */}
      <div className="border-t border-gray-800 px-3 py-1.5">
        <p className="text-[9px] text-gray-700">
          마커는 표시만 합니다. 매매 결정권 없음.
        </p>
      </div>
    </div>
  )
}

// ──────────────────────────────────────────────
// 초기 선택 상태 로딩 hook
// ──────────────────────────────────────────────

/**
 * localStorage에서 초기 패턴 선택 상태를 로드하는 hook.
 * PatternToggleMenu와 함께 사용.
 */
export function usePatternSelection(initialIds?: ReadonlySet<string>) {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => {
    if (initialIds) return new Set(initialIds)
    return loadFromStorage()
  })

  const handleChange = useCallback((ids: Set<string>) => {
    setSelectedIds(ids)
  }, [])

  return { selectedIds, onSelectionChange: handleChange }
}
