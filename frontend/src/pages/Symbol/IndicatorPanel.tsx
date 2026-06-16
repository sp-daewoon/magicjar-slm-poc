/**
 * IndicatorPanel — 80지표 선택 UI (StrategyBuilder 토글)
 *
 * spec §3-5 기능:
 * - 80지표 카탈로그를 카테고리별 섹션으로 표시
 * - 텍스트 검색 필터 (지표명·ID)
 * - 체크박스 토글 → 부모에게 selectedIds Set 전달
 * - 최대 5개 동시 선택 제한 (LWC 오버레이 가독성)
 *
 * 카테고리 분류 (indicator-catalog.md 정합):
 *   TREND·MOMENTUM·VOLUME·VOLATILITY·OSCILLATOR·OTHER
 *
 * ADR 0023: LLM import 0건
 */
import { useState, useMemo, useCallback } from 'react'
import {
  INDICATOR_CATALOG,
  type IndicatorCatalogItem,
} from '../../api/indicator'

const CATEGORY_LABEL: Record<IndicatorCatalogItem['category'], string> = {
  TREND: '추세',
  MOMENTUM: '모멘텀',
  VOLUME: '거래량',
  VOLATILITY: '변동성',
  OSCILLATOR: '오실레이터',
  OTHER: '기타',
}

const MAX_SELECTED = 5

interface IndicatorPanelProps {
  selectedIds: Set<string>
  onToggle: (id: string) => void
  onClose: () => void
}

export function IndicatorPanel({ selectedIds, onToggle, onClose }: IndicatorPanelProps) {
  const [search, setSearch] = useState('')
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(
    () => new Set(['TREND', 'MOMENTUM', 'VOLATILITY']),
  )

  // 검색 필터 적용
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return INDICATOR_CATALOG
    return INDICATOR_CATALOG.filter(
      (item) =>
        item.id.toLowerCase().includes(q) || item.name.toLowerCase().includes(q),
    )
  }, [search])

  // 카테고리별 그룹핑
  const grouped = useMemo(() => {
    const map = new Map<IndicatorCatalogItem['category'], IndicatorCatalogItem[]>()
    for (const item of filtered) {
      const list = map.get(item.category) ?? []
      list.push(item)
      map.set(item.category, list)
    }
    return map
  }, [filtered])

  const toggleCategory = useCallback((cat: string) => {
    setExpandedCategories((prev) => {
      const next = new Set(prev)
      if (next.has(cat)) next.delete(cat)
      else next.add(cat)
      return next
    })
  }, [])

  const handleToggle = useCallback(
    (id: string) => {
      const isSelected = selectedIds.has(id)
      if (!isSelected && selectedIds.size >= MAX_SELECTED) return // 최대 5개 제한
      onToggle(id)
    },
    [selectedIds, onToggle],
  )

  const categories: IndicatorCatalogItem['category'][] = [
    'TREND', 'MOMENTUM', 'VOLUME', 'VOLATILITY', 'OSCILLATOR', 'OTHER',
  ]

  return (
    <div className="flex flex-col h-full bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden">
      {/* 헤더 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-700 shrink-0">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-gray-100">지표 선택</h3>
          <span className="text-xs text-gray-500">
            {selectedIds.size}/{MAX_SELECTED}개 선택
          </span>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded p-1 text-gray-500 hover:text-gray-200 hover:bg-gray-800 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400"
          aria-label="지표 패널 닫기"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* 검색 */}
      <div className="px-3 py-2 border-b border-gray-800 shrink-0">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="지표명 또는 ID 검색 (rsi, 볼린저...)"
          className="w-full rounded-md bg-gray-800 px-3 py-1.5 text-xs text-gray-200 placeholder-gray-500 border border-gray-700 focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-400"
          aria-label="지표 검색"
        />
      </div>

      {/* 최대 개수 경고 */}
      {selectedIds.size >= MAX_SELECTED && (
        <div
          role="alert"
          className="mx-3 mt-2 rounded-md bg-yellow-900/30 border border-yellow-800/50 px-3 py-1.5 text-xs text-yellow-400 shrink-0"
        >
          최대 {MAX_SELECTED}개까지 동시 표시 가능합니다. 다른 지표를 해제하세요.
        </div>
      )}

      {/* 지표 목록 */}
      <div className="flex-1 overflow-y-auto px-2 py-2 space-y-1">
        {filtered.length === 0 ? (
          <p className="py-6 text-center text-xs text-gray-500">검색 결과가 없습니다.</p>
        ) : (
          categories.map((cat) => {
            const items = grouped.get(cat)
            if (!items || items.length === 0) return null
            const isExpanded = expandedCategories.has(cat)

            return (
              <div key={cat} className="rounded-lg border border-gray-800 overflow-hidden">
                {/* 카테고리 헤더 */}
                <button
                  type="button"
                  onClick={() => toggleCategory(cat)}
                  className="w-full flex items-center justify-between px-3 py-2 bg-gray-800/60 hover:bg-gray-800 text-xs font-medium text-gray-300 transition-colors focus:outline-none"
                  aria-expanded={isExpanded}
                >
                  <span>{CATEGORY_LABEL[cat]}</span>
                  <span className="flex items-center gap-1.5 text-gray-500">
                    <span>{items.length}개</span>
                    <svg
                      className={`h-3.5 w-3.5 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={2}
                      aria-hidden="true"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                    </svg>
                  </span>
                </button>

                {/* 지표 항목 */}
                {isExpanded && (
                  <ul className="divide-y divide-gray-800/50">
                    {items.map((item) => {
                      const isSelected = selectedIds.has(item.id)
                      const isDisabled = !isSelected && selectedIds.size >= MAX_SELECTED

                      return (
                        <li key={item.id}>
                          <label
                            className={`flex items-center gap-2.5 px-3 py-2 cursor-pointer transition-colors ${
                              isSelected
                                ? 'bg-blue-900/20 hover:bg-blue-900/30'
                                : isDisabled
                                  ? 'opacity-40 cursor-not-allowed'
                                  : 'hover:bg-gray-800/50'
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={isSelected}
                              disabled={isDisabled}
                              onChange={() => handleToggle(item.id)}
                              className="h-3.5 w-3.5 rounded border-gray-600 bg-gray-800 text-blue-500 focus:ring-blue-400 focus:ring-offset-0 shrink-0"
                            />
                            <span className="flex-1 min-w-0">
                              <span className="block text-xs text-gray-200 truncate">
                                {item.name}
                              </span>
                              <span className="block text-[10px] text-gray-500">
                                {item.id}
                                {item.multiOutput && (
                                  <span className="ml-1.5 text-blue-400">[복합]</span>
                                )}
                              </span>
                            </span>
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>
            )
          })
        )}
      </div>

      {/* 하단 액션 */}
      {selectedIds.size > 0 && (
        <div className="px-3 py-2 border-t border-gray-800 shrink-0 flex items-center justify-between">
          <span className="text-xs text-gray-400">
            {[...selectedIds].join(', ')}
          </span>
          <button
            type="button"
            onClick={() => [...selectedIds].forEach((id) => onToggle(id))}
            className="text-xs text-red-400 hover:text-red-300 transition-colors focus:outline-none"
          >
            모두 해제
          </button>
        </div>
      )}
    </div>
  )
}
