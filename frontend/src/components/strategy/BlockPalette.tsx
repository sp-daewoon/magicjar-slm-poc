/**
 * BlockPalette — 블록 팔레트 (좌측 사이드바)
 *
 * 5 탭:
 *   지표 — INDICATOR_CATALOG 80건 (카테고리 6종 섹션)
 *   즐겨찾기 — 전략 프리셋·사용자 저장 후보
 *   패턴 — PATTERN_CATALOG 57건 (single/double/triple 섹션, R1 active ★)
 *   캔들 — 캔들 직접 참조 6종
 *   연산자/상수 — 비교·논리·산술·상수·패턴 함수
 *
 * ADR 0055 B+C-lite 하이브리드 — 의존 추가 0건
 * 룰 5: LLM provider import 0건
 */
import { useState, useMemo } from 'react'
import { INDICATOR_CATALOG, type IndicatorCatalogItem } from '../../api/indicator'
import { PATTERN_CATALOG, type PatternCatalogItem } from '../../api/patterns'
import type { BlockNode } from '../../lib/dsl/blockTree'
import type { SmartMoneyStrategyPreset } from '../../lib/dsl/smartMoneyPresets'
import { StrategyFavoritePanel } from './StrategyFavoritePanel'

type PaletteTab = 'indicator' | 'favorite' | 'pattern' | 'candle' | 'operator'

interface BlockPaletteProps {
  /** 블록 선택 시 콜백 — 선택된 BlockNode 반환 */
  onSelect: (block: BlockNode) => void
  /** 전략 즐겨찾기 후보 */
  favorites?: SmartMoneyStrategyPreset[]
  /** 현재 트리를 즐겨찾기에 추가할 수 있는지 */
  hasFavoriteSource?: boolean
  /** 즐겨찾기 적용 */
  onFavoriteApply?: (preset: SmartMoneyStrategyPreset) => void
  /** 현재 전략을 즐겨찾기에 추가 */
  onFavoriteAddCurrent?: () => void
  /** 즐겨찾기 삭제 */
  onFavoriteRemove?: (id: string) => void
  /** 패널 폭(px) — usePaletteResize hook에서 주입. 미전달 시 224px(w-56) 기본값 */
  width?: number
}

/** 카테고리 한국어 매핑 */
const CATEGORY_LABELS: Record<string, string> = {
  TREND: '추세',
  MOMENTUM: '모멘텀',
  VOLUME: '거래량',
  VOLATILITY: '변동성',
  OSCILLATOR: '오실레이터',
  OTHER: '기타',
}

/** 패턴 그룹 한국어 매핑 */
const PATTERN_GROUP_LABELS: Record<string, string> = {
  single: '단일 캔들 (14)',
  double: '이중 캔들 (16)',
  triple: '삼중+ 캔들 (27)',
}

const CANDLE_FIELDS = [
  { field: 'close' as const, label: '종가 (Close)' },
  { field: 'prev_close' as const, label: '전일 종가 (Prev Close)' },
  { field: 'open' as const, label: '시가 (Open)' },
  { field: 'high' as const, label: '고가 (High)' },
  { field: 'low' as const, label: '저가 (Low)' },
  { field: 'volume' as const, label: '거래량 (Volume)' },
]

const OPERATOR_PRESETS: Array<{ label: string; desc: string; block: BlockNode }> = [
  {
    label: '비교: >',
    desc: '크다 (A > B)',
    block: { kind: 'comparison', op: '>' },
  },
  {
    label: '비교: <',
    desc: '작다 (A < B)',
    block: { kind: 'comparison', op: '<' },
  },
  {
    label: '비교: >=',
    desc: '크거나 같다',
    block: { kind: 'comparison', op: '>=' },
  },
  {
    label: '비교: <=',
    desc: '작거나 같다',
    block: { kind: 'comparison', op: '<=' },
  },
  {
    label: '교차 상향',
    desc: 'A crosses_above B',
    block: { kind: 'comparison', op: 'crosses_above' },
  },
  {
    label: '교차 하향',
    desc: 'A crosses_below B',
    block: { kind: 'comparison', op: 'crosses_below' },
  },
  {
    label: 'AND',
    desc: '두 조건 모두 충족',
    block: { kind: 'logical', op: 'AND' },
  },
  {
    label: 'OR',
    desc: '두 조건 중 하나 충족',
    block: { kind: 'logical', op: 'OR' },
  },
  {
    label: 'NOT',
    desc: '조건 부정',
    block: { kind: 'not' },
  },
  {
    label: '산술: *',
    desc: 'A * B',
    block: { kind: 'arithmetic', op: '*' },
  },
  {
    label: '산술: +',
    desc: 'A + B',
    block: { kind: 'arithmetic', op: '+' },
  },
  {
    label: '산술: -',
    desc: 'A - B',
    block: { kind: 'arithmetic', op: '-' },
  },
  {
    label: '상수 (숫자)',
    desc: '정수·실수 값 입력',
    block: { kind: 'literal', valueType: 'integer', value: '0' },
  },
]

const NAV_LINK_BASE =
  'px-3 py-1.5 text-sm font-medium rounded-lg transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950'
const NAV_LINK_ACTIVE = 'bg-blue-600 text-white'
const NAV_LINK_INACTIVE = 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200'

const ITEM_BTN =
  'w-full text-left px-3 py-2 rounded-lg text-sm hover:bg-gray-800 transition-colors focus:outline-none focus:ring-1 focus:ring-blue-400 flex items-center justify-between gap-2'

export function BlockPalette({
  onSelect,
  favorites = [],
  hasFavoriteSource = false,
  onFavoriteApply,
  onFavoriteAddCurrent,
  onFavoriteRemove,
  width = 224,
}: BlockPaletteProps) {
  const [tab, setTab] = useState<PaletteTab>('indicator')
  const [query, setQuery] = useState('')

  // 검색 필터링 (지표·패턴 탭)
  const filteredIndicators = useMemo(() => {
    if (!query.trim()) return INDICATOR_CATALOG
    const q = query.trim().toLowerCase()
    return INDICATOR_CATALOG.filter(
      (i) => i.id.toLowerCase().includes(q) || i.name.toLowerCase().includes(q),
    )
  }, [query])

  const filteredPatterns = useMemo(() => {
    if (!query.trim()) return PATTERN_CATALOG
    const q = query.trim().toLowerCase()
    return PATTERN_CATALOG.filter(
      (p) =>
        p.id.toLowerCase().includes(q) ||
        p.nameKo.toLowerCase().includes(q) ||
        p.nameEn.toLowerCase().includes(q),
    )
  }, [query])

  const handleSelectIndicator = (item: IndicatorCatalogItem) => {
    onSelect({
      kind: 'indicator',
      id: item.id,
      params: Object.fromEntries(
        Object.entries(item.defaultParams).map(([k, v]) => [k, String(v)]),
      ),
      outputKey: item.multiOutput ? item.outputKeys[0] : undefined,
    })
  }

  const handleSelectPattern = (item: PatternCatalogItem) => {
    onSelect({
      kind: 'pattern',
      patternId: item.id,
      func: 'pattern_detected',
    })
  }

  // 카테고리별 그룹
  const indicatorsByCategory = useMemo(() => {
    const map: Record<string, IndicatorCatalogItem[]> = {}
    for (const item of filteredIndicators) {
      if (!map[item.category]) map[item.category] = []
      map[item.category].push(item)
    }
    return map
  }, [filteredIndicators])

  const patternsByGroup = useMemo(() => {
    const map: Record<string, PatternCatalogItem[]> = {}
    for (const item of filteredPatterns) {
      if (!map[item.group]) map[item.group] = []
      map[item.group].push(item)
    }
    return map
  }, [filteredPatterns])

  return (
    <aside
      className="flex h-full flex-shrink-0 flex-col border-r border-gray-800 bg-gray-950 overflow-hidden"
      style={{ width: `${width}px`, minWidth: '200px', maxWidth: '480px' }}
      aria-label="블록 팔레트"
    >
      {/* 탭 */}
      <nav className="flex flex-wrap gap-1 border-b border-gray-800 p-2" aria-label="팔레트 탭">
        {(['indicator', 'favorite', 'pattern', 'candle', 'operator'] as PaletteTab[]).map((t) => (
          <button
            key={t}
            className={[NAV_LINK_BASE, tab === t ? NAV_LINK_ACTIVE : NAV_LINK_INACTIVE].join(' ')}
            onClick={() => {
              setTab(t)
              setQuery('')
            }}
            aria-selected={tab === t}
            aria-label={
              t === 'indicator'
                ? '지표'
                : t === 'favorite'
                  ? '즐겨찾기'
                  : t === 'pattern'
                    ? '패턴'
                    : t === 'candle'
                      ? '캔들'
                      : '연산자/상수'
            }
          >
            {t === 'indicator'
              ? '지표'
              : t === 'favorite'
                ? '즐겨'
                : t === 'pattern'
                  ? '패턴'
                  : t === 'candle'
                    ? '캔들'
                    : '연산자'}
          </button>
        ))}
      </nav>

      {/* 검색 (지표·패턴 탭) */}
      {(tab === 'indicator' || tab === 'pattern') && (
        <div className="p-2">
          <input
            type="search"
            placeholder={tab === 'indicator' ? 'RSI, 볼린저...' : '해머, 도지...'}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="w-full rounded-lg border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200 placeholder-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            aria-label="블록 검색"
          />
        </div>
      )}

      {/* 내용 */}
      <div className="flex-1 overflow-y-auto py-1">
        {/* 즐겨찾기 탭 */}
        {tab === 'favorite' && (
          <StrategyFavoritePanel
            favorites={favorites}
            hasEntry={hasFavoriteSource}
            onApply={(preset) => onFavoriteApply?.(preset)}
            onAddCurrent={() => onFavoriteAddCurrent?.()}
            onRemove={(id) => onFavoriteRemove?.(id)}
          />
        )}

        {/* 지표 탭 */}
        {tab === 'indicator' && (
          <>
            {filteredIndicators.length === 0 && (
              <p className="px-3 py-4 text-center text-xs text-gray-500">검색 결과 없음</p>
            )}
            {Object.entries(indicatorsByCategory).map(([cat, items]) => (
              <div key={cat}>
                <div className="sticky top-0 bg-gray-950 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
                  {CATEGORY_LABELS[cat] ?? cat}
                </div>
                {items.map((item) => (
                  <button
                    key={item.id}
                    className={ITEM_BTN}
                    onClick={() => handleSelectIndicator(item)}
                    title={item.name}
                  >
                    <span className="truncate text-gray-200">{item.name}</span>
                    <span className="shrink-0 rounded bg-gray-800 px-1 py-0.5 font-mono text-xs text-gray-400">
                      {item.id}
                    </span>
                  </button>
                ))}
              </div>
            ))}
          </>
        )}

        {/* 패턴 탭 */}
        {tab === 'pattern' && (
          <>
            {filteredPatterns.length === 0 && (
              <p className="px-3 py-4 text-center text-xs text-gray-500">검색 결과 없음</p>
            )}
            {(['single', 'double', 'triple'] as const).map((grp) => {
              const items = patternsByGroup[grp]
              if (!items?.length) return null
              return (
                <div key={grp}>
                  <div className="sticky top-0 bg-gray-950 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
                    {PATTERN_GROUP_LABELS[grp]}
                  </div>
                  {items.map((item) => (
                    <button
                      key={item.id}
                      className={[
                        ITEM_BTN,
                        !item.active ? 'opacity-50' : '',
                      ].join(' ')}
                      onClick={() => handleSelectPattern(item)}
                      disabled={false}
                      title={item.nameEn}
                    >
                      <span className="truncate text-gray-200">
                        {item.active && <span className="mr-1 text-yellow-400">★</span>}
                        {item.nameKo}
                      </span>
                      {!item.active && (
                        <span className="shrink-0 rounded bg-gray-800 px-1 py-0.5 text-xs text-gray-500">
                          stub
                        </span>
                      )}
                    </button>
                  ))}
                </div>
              )
            })}
          </>
        )}

        {/* 캔들 탭 */}
        {tab === 'candle' && (
          <div>
            <div className="sticky top-0 bg-gray-950 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
              캔들 직접 참조
            </div>
            {CANDLE_FIELDS.map(({ field, label }) => (
              <button
                key={field}
                className={ITEM_BTN}
                onClick={() => onSelect({ kind: 'candle', field })}
              >
                <span className="text-gray-200">{label}</span>
                <span className="shrink-0 rounded bg-gray-800 px-1 py-0.5 font-mono text-xs text-gray-400">
                  {field}
                </span>
              </button>
            ))}
          </div>
        )}

        {/* 연산자/상수 탭 */}
        {tab === 'operator' && (
          <div>
            <div className="sticky top-0 bg-gray-950 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
              연산자·논리·상수
            </div>
            {OPERATOR_PRESETS.map((preset) => (
              <button
                key={preset.label}
                className={ITEM_BTN}
                onClick={() => onSelect(preset.block)}
                title={preset.desc}
              >
                <span className="font-mono text-blue-300">{preset.label}</span>
                <span className="truncate text-right text-xs text-gray-500">{preset.desc}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </aside>
  )
}
