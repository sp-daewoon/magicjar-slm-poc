/**
 * StrategyBuilderPage — Strategy Builder 통합 페이지
 *
 * 레이아웃 (§7 시각화 컨셉 정합):
 *   좌: BlockPalette (w-56)
 *   중: StrategyBuilderCanvas (flex-1)
 *   우: ComplexityCard + EvaluationResultCard 결과 영역 (w-72)
 *
 * 플로우:
 *   1. 팔레트에서 블록 선택 → 현재 탭(entry/exit) 트리에 추가
 *   2. "검증" → POST /api/strategy/validate → ComplexityCard 표시
 *   3. "저장" → StrategySaveModal → POST /api/strategies → strategyId 획득
 *   4. "평가" → POST /api/strategy/{id}/evaluate → EvaluationResultCard 표시
 *   5. "관심종목 추가" → POST /api/watchlist
 *   6. "이 전략으로 종목 찾기" → ScreenerResultModal → T-SC Phase 3 (ADR 0071 §8 H-1)
 *
 * ADR 0040 §1 라우팅 /strategy/builder 진입점
 * DoD #26 반응형 — 768px 이하 안내 메시지 (mobile 후속 PR)
 * 룰 5: LLM provider import 0건
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { usePaletteResize } from '../../hooks/usePaletteResize'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { BlockPalette } from '../../components/strategy/BlockPalette'
import { StrategyBuilderCanvas } from '../../components/strategy/StrategyBuilderCanvas'
import { ComplexityCard } from '../../components/strategy/ComplexityCard'
import { EvaluationResultCard, EvaluationEmptyCard } from '../../components/strategy/EvaluationResultCard'
import { StrategySaveModal } from '../../components/strategy/StrategySaveModal'
import { NaturalLanguageInput } from '../../components/strategy/NaturalLanguageInput'
import { StrategyAiGenerateModal } from '../../components/strategy/StrategyAiGenerateModal'
import { DslTextEditor } from '../../components/strategy/DslTextEditor'
import type { StrategyBuilderAiGenerateItem } from '../../api/strategyBuilderAiGenerate'
import { cloneNode, type BlockNode, type BlockTree } from '../../lib/dsl/blockTree'
import { serializeBlockTree } from '../../lib/dsl/serializer'
import {
  clonePresetTree,
  SMART_MONEY_STRATEGY_PRESETS,
  type SmartMoneyStrategyPreset,
} from '../../lib/dsl/smartMoneyPresets'
import { MINERVINI_STRATEGY_FAVORITES } from '../../lib/dsl/minerviniFavorites'
import { kisYamlExport, kisYamlImport } from '../../lib/dsl/yaml'
import { useStrategyValidate, useStrategyEvaluate } from '../../api/hooks/useStrategy'
import { useSigStrategy } from '../../api/hooks/useSigStrategy'
import type { StrategyComplexityDto, EvaluateResponse } from '../../api/strategy'
import { symbolApi, type CandleInterval } from '../../api/symbol'
import { parseDslText, parseDslTextToTree } from '../../lib/dsl/parser'
import {
  consumeStrategyResearchDraft,
  type StrategyResearchBuilderDraft,
} from '../../lib/strategyResearch/builderDraft'
import { ScreenerResultModal } from '../../features/screener/components/ScreenerResultModal'
import { useOnboardingStore } from '../../stores/onboarding'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'

// 평가 폼 상태
interface EvaluateForm {
  code: string
  interval: string
}

const EVAL_INTERVAL_OPTIONS = [
  { value: '1m', label: '1m' },
  { value: '3m', label: '3m' },
  { value: '5m', label: '5m' },
  { value: '15m', label: '15m' },
  { value: '30m', label: '30m' },
  { value: '90m', label: '90m' },
  { value: '1h', label: '1h' },
  { value: '4h', label: '4h' },
  { value: '12h', label: '12h (정규장 전체)' },
  { value: '1d', label: '1d' },
  { value: '1wk', label: '1wk' },
  { value: '1mo', label: '1mo' },
  { value: '1y', label: '1y' },
]

const STRATEGY_FAVORITES_STORAGE_KEY = 'magicjar.strategyBuilder.customFavorites.v1'
const STRATEGY_HIDDEN_DEFAULT_FAVORITES_STORAGE_KEY = 'magicjar.strategyBuilder.hiddenDefaultFavorites.v1'

function loadCustomFavorites(): SmartMoneyStrategyPreset[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STRATEGY_FAVORITES_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is SmartMoneyStrategyPreset => {
      if (!item || typeof item !== 'object') return false
      const candidate = item as Partial<SmartMoneyStrategyPreset>
      return (
        typeof candidate.id === 'string' &&
        typeof candidate.label === 'string' &&
        typeof candidate.description === 'string' &&
        typeof candidate.suggestedInterval === 'string' &&
        !!candidate.tree
      )
    })
  } catch {
    return []
  }
}

function saveCustomFavorites(favorites: SmartMoneyStrategyPreset[]) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(STRATEGY_FAVORITES_STORAGE_KEY, JSON.stringify(favorites))
}

function loadHiddenDefaultFavoriteIds(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STRATEGY_HIDDEN_DEFAULT_FAVORITES_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed.map((id) => String(id)).filter(Boolean) : []
  } catch {
    return []
  }
}

function saveHiddenDefaultFavoriteIds(ids: string[]) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(STRATEGY_HIDDEN_DEFAULT_FAVORITES_STORAGE_KEY, JSON.stringify(ids))
}

function insertBlockIntoFirstEmptySlot(root: BlockNode, block: BlockNode): BlockNode | null {
  const freshBlock = () => cloneNode(block)

  switch (root.kind) {
    case 'comparison': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'logical': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'arithmetic': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'not': {
      if (!root.operand) return { ...root, operand: freshBlock() }
      const updatedOperand = insertBlockIntoFirstEmptySlot(root.operand, block)
      return updatedOperand ? { ...root, operand: updatedOperand } : null
    }
    default:
      return null
  }
}

export function StrategyBuilderPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const routeStrategyId = searchParams.get('strategyId') ?? undefined
  const loadedStrategyIdRef = useRef<string | null>(null)
  const [tree, setTree] = useState<BlockTree>({})
  const [strategyId, setStrategyId] = useState<string | undefined>()
  const [complexity, setComplexity] = useState<StrategyComplexityDto | null>(null)
  const [evalResult, setEvalResult] = useState<EvaluateResponse | null>(null)
  const [evalForm, setEvalForm] = useState<EvaluateForm>({ code: '005930', interval: '1d' })
  const [evalPrepMessage, setEvalPrepMessage] = useState<string | null>(null)
  const [isPreparingEvaluation, setIsPreparingEvaluation] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const [toastType, setToastType] = useState<'success' | 'error'>('success')
  const [customFavorites, setCustomFavorites] = useState<SmartMoneyStrategyPreset[]>(loadCustomFavorites)
  const [hiddenDefaultFavoriteIds, setHiddenDefaultFavoriteIds] = useState<string[]>(loadHiddenDefaultFavoriteIds)
  const [researchDraftMeta, setResearchDraftMeta] = useState<StrategyResearchBuilderDraft | null>(null)
  const importedResearchDraftIdRef = useRef<string | null>(null)

  // 모달 상태
  const [showSaveModal, setShowSaveModal] = useState(false)
  const [showNaturalLang, setShowNaturalLang] = useState(false)
  const [showAiGenerate, setShowAiGenerate] = useState(false)
  // AI 생성 후보를 빌더로 불러올 때 name·riskPlan을 저장 모달 초기값으로 전달 (researchDraftMeta 패턴)
  const [aiDraftMeta, setAiDraftMeta] = useState<StrategyBuilderAiGenerateItem | null>(null)
  const [showDslEditor, setShowDslEditor] = useState(false)
  const [dslEditorTab, setDslEditorTab] = useState<'entry' | 'exit'>('entry')
  const [validateError, setValidateError] = useState<string | null>(null)
  // T-SC Phase 3 — 종목 찾기 모달 (ADR 0071 §8 H-1)
  const [showScreener, setShowScreener] = useState(false)
  // Canvas 탭 state — BlockPalette 클릭 흐름 정합 (hotfix 2026-05-14)
  const [canvasTab, setCanvasTab] = useState<'entry' | 'exit'>('entry')

  // DSL 직접 편집 모달 — ESC 닫기 + body scroll lock (공용 훅)
  // SaveModal·NaturalLanguageInput·ScreenerResultModal 은 각 컴포넌트 내부에서 훅 적용.
  useModalBehavior({ open: showDslEditor, onClose: () => setShowDslEditor(false) })

  // userId: onboarding store (feedback_no_mock_data 정합)
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const { data: loadedStrategy, isError: isLoadStrategyError, error: loadStrategyError } = useSigStrategy(routeStrategyId)

  const { mutate: validateMutate, isPending: isValidating } = useStrategyValidate()
  const { mutateAsync: evaluateAsync, isPending: isEvaluating } = useStrategyEvaluate()
  const { width: paletteWidth, handleMouseDown: handlePaletteResizeDown, isDragging: isPaletteResizing } = usePaletteResize()

  const defaultFavoritePresets = useMemo(
    () => [...SMART_MONEY_STRATEGY_PRESETS, ...MINERVINI_STRATEGY_FAVORITES],
    [],
  )
  const favoritePresets = useMemo(() => {
    const hiddenIds = new Set(hiddenDefaultFavoriteIds)
    return [
      ...defaultFavoritePresets.filter((preset) => !hiddenIds.has(preset.id)),
      ...customFavorites,
    ]
  }, [customFavorites, defaultFavoritePresets, hiddenDefaultFavoriteIds])

  const showToast = useCallback((msg: string, type: 'success' | 'error' = 'success') => {
    setToast(msg)
    setToastType(type)
    setTimeout(() => setToast(null), 3000)
  }, [])

  useEffect(() => {
    if (!loadedStrategy || loadedStrategyIdRef.current === loadedStrategy.id) return
    if (!loadedStrategy.buyCondition) {
      showToast('불러온 전략에 매수 조건이 없습니다', 'error')
      return
    }
    try {
      setTree(parseDslTextToTree(loadedStrategy.buyCondition, loadedStrategy.sellCondition))
      setStrategyId(loadedStrategy.id)
      setAiDraftMeta(null)
      setComplexity(loadedStrategy.complexity ?? null)
      setEvalResult(null)
      setValidateError(null)
      setEvalForm((prev) => ({
        code: loadedStrategy.codes[0] ?? prev.code,
        interval: loadedStrategy.intervals[0] ?? loadedStrategy.tf ?? prev.interval,
      }))
      loadedStrategyIdRef.current = loadedStrategy.id
      showToast(`${loadedStrategy.nameKo || loadedStrategy.name} 불러옴`)
    } catch (err) {
      showToast(`전략 DSL 불러오기 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }, [loadedStrategy, showToast])

  useEffect(() => {
    const draftId = searchParams.get('researchDraftId')
    if (!draftId || importedResearchDraftIdRef.current === draftId) return
    importedResearchDraftIdRef.current = draftId

    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('researchDraftId')
    setSearchParams(nextParams, { replace: true })

    const draft = consumeStrategyResearchDraft(draftId)
    if (!draft) {
      showToast('전략 리서치 draft를 찾을 수 없습니다', 'error')
      return
    }
    if ((tree.entry || tree.exit) && !window.confirm('현재 작성 중인 조건을 리서치 후보로 교체할까요?')) {
      return
    }
    try {
      setTree(parseDslTextToTree(draft.entryDsl, draft.exitDsl))
      setResearchDraftMeta(draft)
      setAiDraftMeta(null)
      setStrategyId(undefined)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setCanvasTab('entry')
      setEvalForm({ code: draft.code, interval: draft.interval })
      loadedStrategyIdRef.current = null
      showToast(`${draft.candidateTitle} draft 불러옴`)
    } catch (err) {
      showToast(`리서치 draft 변환 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }, [searchParams, setSearchParams, showToast, tree.entry, tree.exit])

  useEffect(() => {
    if (isLoadStrategyError && loadStrategyError) {
      showToast(`전략 조회 실패: ${loadStrategyError.message}`, 'error')
    }
  }, [isLoadStrategyError, loadStrategyError, showToast])

  // 팔레트에서 블록 선택 → 캔버스 현재 탭 루트에 추가
  // hotfix 2026-05-14: canvasTab 인식 — 매수/매도 탭에 따라 분기 (이전 결함: 항상 entry 에만 추가)
  const handlePaletteSelect = (block: BlockNode) => {
    const applyBlock = (current?: BlockNode): BlockNode | undefined => {
      if (!current) return cloneNode(block)
      const inserted = insertBlockIntoFirstEmptySlot(current, block)
      if (inserted) return inserted
      const ok = window.confirm('현재 조건에 빈 슬롯이 없습니다. 선택한 블록으로 현재 조건을 교체할까요?')
      return ok ? cloneNode(block) : current
    }

    setTree((prev) => (
      canvasTab === 'entry'
        ? { ...prev, entry: applyBlock(prev.entry) }
        : { ...prev, exit: applyBlock(prev.exit) }
    ))
  }

  const applyPresetTree = (
    preset: SmartMoneyStrategyPreset,
    options: { useSuggestedInterval: boolean },
  ) => {
    if (tree.entry || tree.exit) {
      const ok = window.confirm('현재 조건을 프리셋 후보로 교체할까요?')
      if (!ok) return
    }
    setTree(clonePresetTree(preset.tree))
    setCanvasTab('entry')
    setStrategyId(undefined)
    setAiDraftMeta(null)
    setComplexity(null)
    setEvalResult(null)
    setValidateError(null)
    if (options.useSuggestedInterval) {
      setEvalForm((prev) => ({ ...prev, interval: preset.suggestedInterval }))
    }
    showToast(`${preset.label} 적용됨`)
  }

  const handleFavoriteApply = (preset: SmartMoneyStrategyPreset) => {
    applyPresetTree(preset, { useSuggestedInterval: false })
  }

  const handleAddFavorite = () => {
    const { entry } = serializeBlockTree(tree)
    if (!entry) {
      showToast('즐겨찾기에 추가할 매수 조건이 없습니다', 'error')
      return
    }
    const label = window.prompt('즐겨찾기 이름을 입력하세요', '나의 전략 후보')?.trim()
    if (!label) return
    const nextFavorite: SmartMoneyStrategyPreset = {
      id: `custom-${Date.now()}`,
      label,
      description: entry.length > 72 ? `${entry.slice(0, 72)}...` : entry,
      suggestedInterval: evalForm.interval,
      tree: clonePresetTree(tree),
    }
    const next = [nextFavorite, ...customFavorites]
    setCustomFavorites(next)
    saveCustomFavorites(next)
    showToast(`${label} 즐겨찾기 추가됨`)
  }

  const handleRemoveFavorite = (id: string) => {
    if (customFavorites.some((favorite) => favorite.id === id)) {
      const next = customFavorites.filter((favorite) => favorite.id !== id)
      setCustomFavorites(next)
      saveCustomFavorites(next)
      showToast('즐겨찾기에서 삭제했습니다')
      return
    }
    const next = Array.from(new Set([...hiddenDefaultFavoriteIds, id]))
    setHiddenDefaultFavoriteIds(next)
    saveHiddenDefaultFavoriteIds(next)
    showToast('즐겨찾기에서 삭제했습니다')
  }

  // 검증
  const handleValidate = () => {
    const { entry } = serializeBlockTree(tree)
    if (!entry) return
    setValidateError(null)
    validateMutate(
      { expr: entry },
      {
        onSuccess: (result) => {
          setComplexity(result)
          showToast(`검증 완료 — 복잡도: ${result.complexityClass}`)
        },
        onError: (err) => {
          setComplexity(null)
          setValidateError(err.message)
          showToast(`검증 실패: ${err.message}`, 'error')
        },
      },
    )
  }

  // 저장 완료
  const handleSaved = (id: string) => {
    setStrategyId(id)
    setSearchParams({ strategyId: id })
    showToast('전략이 저장되었습니다')
  }

  // 평가
  const ensureEvaluationCandles = async (code: string, interval: string) => {
    const candleInterval = interval as CandleInterval
    setEvalPrepMessage('캔들 데이터 확인 중...')
    const current = await symbolApi.getCandles(code, candleInterval, undefined, 1)
    if (current.candles.length > 0) return

    setEvalPrepMessage('캔들 데이터가 없어 KIS에서 갱신 중...')
    await symbolApi.refreshCandles(code, [interval])

    setEvalPrepMessage('갱신된 캔들 확인 중...')
    const refreshed = await symbolApi.getCandles(code, candleInterval, undefined, 1)
    if (refreshed.candles.length === 0) {
      throw new Error('캔들 갱신 후에도 평가 가능한 차트 데이터가 없습니다. 잠시 후 다시 실행하거나 종목 상세에서 재수집을 실행해 주세요.')
    }
  }

  const handleEvaluate = async () => {
    if (!strategyId) return
    const code = evalForm.code.trim()
    if (!/^\d{6}$/.test(code)) {
      showToast('평가 종목은 6자리 종목코드로 선택해 주세요', 'error')
      return
    }
    setIsPreparingEvaluation(true)
    setEvalResult(null)
    try {
      await ensureEvaluationCandles(code, evalForm.interval)
      setEvalPrepMessage('전략 평가 중...')
      const result = await evaluateAsync({ strategyId, req: { code, interval: evalForm.interval } })
      setEvalResult(result)
      showToast(`평가 완료 — 신호: ${result.signal}`)
    } catch (err) {
      const msg = err instanceof Error ? err.message : '알 수 없는 오류'
      setEvalResult(null)
      showToast(`평가 실패: ${msg}`, 'error')
    } finally {
      setEvalPrepMessage(null)
      setIsPreparingEvaluation(false)
    }
  }

  // AI 생성 후보 → 빌더 폼에 주입 (자동 저장 0 — ADR 0112 매매 결정권 0)
  // NaturalLanguageInput이 DSL을 트리로 넣는 기존 경로(parseDslTextToTree) 재사용.
  const handleAiDraftLoad = (item: StrategyBuilderAiGenerateItem) => {
    if (!item.dslValid) return
    if ((tree.entry || tree.exit) && !window.confirm('현재 작성 중인 조건을 AI 생성 후보로 교체할까요?')) {
      return
    }
    try {
      setTree(parseDslTextToTree(item.entryDsl, item.exitDsl ?? undefined))
      setAiDraftMeta(item)
      setResearchDraftMeta(null)
      setStrategyId(undefined)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setCanvasTab('entry')
      loadedStrategyIdRef.current = null
      setShowAiGenerate(false)
      showToast(`${item.name} 후보를 빌더로 불러왔습니다`)
    } catch (err) {
      showToast(`AI 후보 변환 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }

  // 자연어 변환 결과 적용
  const handleNaturalResult = (result: Partial<BlockTree>) => {
    setTree((prev) => ({
      entry: result.entry ?? prev.entry,
      exit: result.exit ?? prev.exit,
    }))
  }

  // DSL 편집 열기
  const handleEditDsl = () => {
    setDslEditorTab(canvasTab)
    setShowDslEditor(true)
  }

  // DSL 편집 → 트리 반영
  const handleDslChange = (node: BlockNode) => {
    setTree((prev) =>
      dslEditorTab === 'entry' ? { ...prev, entry: node } : { ...prev, exit: node },
    )
    setShowDslEditor(false)
  }

  // YAML 내보내기
  const handleYamlExport = () => {
    const { entry } = serializeBlockTree(tree)
    if (!entry) return
    const yaml = kisYamlExport(tree, {
      name: 'my_strategy',
      nameKo: '나의 전략',
      universe: 'KOSPI',
      tf: '1d',
    })
    const blob = new Blob([yaml], { type: 'text/yaml' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'strategy.kis.yaml'
    a.click()
    URL.revokeObjectURL(url)
  }

  // YAML 불러오기
  const handleYamlImport = (file: File) => {
    if (file.size > 1024 * 1024) {
      showToast('파일이 너무 큽니다 (최대 1MB)', 'error')
      return
    }
    const reader = new FileReader()
    reader.onload = (e) => {
      try {
        const text = e.target?.result as string
        const imported = kisYamlImport(text)
        const entryNode = imported.entry ? parseDslText(imported.entry) : undefined
        const exitNode = imported.exit ? parseDslText(imported.exit) : undefined
        setTree({ entry: entryNode, exit: exitNode })
        showToast('YAML 불러오기 완료')
      } catch (err) {
        showToast(
          `YAML 파싱 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`,
          'error',
        )
      }
    }
    reader.readAsText(file)
  }

  const { entry: entryDsl, exit: exitDsl } = serializeBlockTree(tree)

  return (
    <div className="flex flex-col overflow-hidden bg-gray-950" style={{ height: 'calc(100vh - 60px)' }}>
      {/* 모바일 안내 (DoD #26 — 768px 이하) */}
      <div className="flex flex-col items-center justify-center p-6 text-center lg:hidden">
        <p className="text-sm text-gray-400">
          전략 빌더는 현재 데스크톱(1280px 이상)에서 권장합니다.
        </p>
        <p className="mt-1 text-xs text-gray-600">모바일 최적화는 후속 업데이트에서 제공됩니다.</p>
      </div>

      {/* 데스크톱 레이아웃 */}
      <div className="hidden min-h-0 flex-1 lg:flex">
        {/* 좌: 팔레트 + 드래그 리사이즈 핸들 */}
        <BlockPalette
          onSelect={handlePaletteSelect}
          favorites={favoritePresets}
          hasFavoriteSource={!!entryDsl}
          onFavoriteApply={handleFavoriteApply}
          onFavoriteAddCurrent={handleAddFavorite}
          onFavoriteRemove={handleRemoveFavorite}
          width={paletteWidth}
        />
        {/* ResizeHandle — col-resize 핸들 */}
        <div
          role="separator"
          aria-label="팔레트 폭 조절"
          aria-orientation="vertical"
          className={[
            'relative z-10 flex w-1.5 flex-shrink-0 cursor-col-resize select-none items-center justify-center',
            'hover:bg-blue-500/40 active:bg-blue-500/60',
            isPaletteResizing ? 'bg-blue-500/60' : 'bg-transparent',
            'transition-colors duration-100',
          ].join(' ')}
          onMouseDown={handlePaletteResizeDown}
          title="드래그하여 패널 폭 조절"
        >
          {/* 핸들 점선 비주얼 */}
          <div
            className={[
              'h-12 w-0.5 rounded-full',
              isPaletteResizing ? 'bg-blue-400' : 'bg-gray-700 group-hover:bg-blue-500',
            ].join(' ')}
          />
        </div>

        {/* 중: 캔버스 */}
        <StrategyBuilderCanvas
          tree={tree}
          onTreeChange={setTree}
          tab={canvasTab}
          onTabChange={setCanvasTab}
          onEditDsl={handleEditDsl}
          onNaturalLanguage={() => setShowNaturalLang(true)}
          onAiGenerate={() => setShowAiGenerate(true)}
          onYamlExport={handleYamlExport}
          onYamlImport={handleYamlImport}
          onValidate={handleValidate}
          onSave={() => setShowSaveModal(true)}
          onEvaluate={handleEvaluate}
          strategyId={strategyId}
          isValidating={isValidating}
          isSaving={false}
        />

        {/* 우: 결과 영역 */}
        <aside className="flex w-72 flex-shrink-0 flex-col gap-4 overflow-y-auto border-l border-gray-800 p-4">
          <h2 className="text-sm font-semibold text-gray-300">전략 실행</h2>

          {researchDraftMeta && !strategyId && (
            <div className="rounded-lg border border-blue-900/60 bg-blue-950/20 p-3">
              <div className="text-xs font-semibold text-blue-200">리서치 draft</div>
              <div className="mt-1 text-sm text-gray-200">{researchDraftMeta.candidateTitle}</div>
              <div className="mt-1 text-xs text-gray-500">
                {researchDraftMeta.code} · {researchDraftMeta.interval} · 익절 {researchDraftMeta.takeProfitPct}% · 손절 {researchDraftMeta.stopLossPct}%
              </div>
              {researchDraftMeta.conversionNotes.length > 0 && (
                <div className="mt-2 space-y-1 text-xs text-amber-300">
                  {researchDraftMeta.conversionNotes.map((note) => (
                    <p key={note}>{note}</p>
                  ))}
                </div>
              )}
            </div>
          )}

          {aiDraftMeta && !strategyId && (
            <div className="rounded-lg border border-violet-900/60 bg-violet-950/20 p-3">
              <div className="text-xs font-semibold text-violet-200">AI 생성 후보</div>
              <div className="mt-1 text-sm text-gray-200">{aiDraftMeta.name}</div>
              <div className="mt-1 text-xs text-gray-500">
                {[
                  aiDraftMeta.riskPlan.takeProfitPct != null ? `익절 ${aiDraftMeta.riskPlan.takeProfitPct}%` : null,
                  aiDraftMeta.riskPlan.fixedStopLossPct != null ? `손절 ${aiDraftMeta.riskPlan.fixedStopLossPct}%` : null,
                  aiDraftMeta.riskPlan.minHoldBars != null ? `최소보유 ${aiDraftMeta.riskPlan.minHoldBars}봉` : null,
                ].filter(Boolean).join(' · ') || '리스크 플랜 미지정'}
              </div>
              <p className="mt-2 text-xs text-amber-300">
                저장하지 않았습니다. 검토·검증·평가 후 직접 저장하세요.
              </p>
            </div>
          )}

          <div className="rounded-lg border border-gray-800 bg-gray-900 p-3">
            <div className="mb-2 text-xs text-gray-500">종목·기준봉</div>
            <div className="space-y-2">
              <InstrumentSearchInput
                value={evalForm.code}
                onRawChange={(value) => setEvalForm((f) => ({ ...f, code: value.trim() }))}
                onSelect={(result) => setEvalForm((f) => ({ ...f, code: result.code }))}
                placeholder="종목명 또는 코드 검색"
              />
              <select
                aria-label="평가 기준봉"
                value={evalForm.interval}
                onChange={(e) => setEvalForm((f) => ({ ...f, interval: e.target.value }))}
                className="w-full rounded-md border border-gray-700 bg-gray-950 px-2 py-1.5 text-sm text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
              >
                {EVAL_INTERVAL_OPTIONS.map((i) => (
                  <option key={i.value} value={i.value}>{i.label}</option>
                ))}
              </select>
              <button
                className="w-full rounded-md bg-emerald-600 py-1.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={handleEvaluate}
                disabled={!strategyId || isEvaluating || isPreparingEvaluation || !evalForm.code}
                title={!strategyId ? '먼저 전략을 저장하세요' : undefined}
              >
                {isEvaluating || isPreparingEvaluation ? '평가 중...' : '평가 실행'}
              </button>
              {evalPrepMessage && (
                <p className="text-xs text-emerald-300" role="status">
                  {evalPrepMessage}
                </p>
              )}
            </div>
          </div>

          {/* 복잡도 카드 */}
          {complexity && <ComplexityCard complexity={complexity} />}

          {/* 검증 에러 */}
          {validateError && !complexity && (
            <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-3 text-sm text-red-400">
              {validateError}
            </div>
          )}

          {/* 평가 결과 카드 */}
          {evalResult ? (
            <EvaluationResultCard
              result={evalResult}
              code={evalForm.code}
            />
          ) : strategyId && !isEvaluating && !isPreparingEvaluation ? (
            <EvaluationEmptyCard />
          ) : null}

          {/* T-SC Phase 3 — 이 전략으로 종목 찾기 (ADR 0071 §8 H-1) */}
          {strategyId && (
            <button
              type="button"
              onClick={() => setShowScreener(true)}
              className="w-full rounded-xl bg-indigo-700 py-2.5 text-sm font-semibold text-white hover:bg-indigo-600 active:bg-indigo-800 disabled:cursor-not-allowed disabled:opacity-50"
              aria-label="이 전략으로 종목 찾기"
            >
              이 전략으로 종목 찾기
            </button>
          )}

          {/* 저장된 전략 정보 + 다음 단계 도선 (검증 퍼널 — 빌더 → 백테스트) */}
          {strategyId && (
            <div className="rounded-lg bg-gray-900 p-3 text-xs text-gray-500">
              <p>
                전략 ID: <code className="text-gray-400">{strategyId}</code>
              </p>
              <p className="mb-2 mt-2 text-[11px] text-gray-600">
                다음 단계 — 과거 데이터로 전략을 검증하세요.
              </p>
              <Link
                to={`/strategy/backtest?strategyId=${encodeURIComponent(strategyId)}`}
                className="block w-full rounded-md bg-blue-600 py-1.5 text-center text-sm font-medium text-white hover:bg-blue-700"
              >
                이 전략 백테스트 →
              </Link>
              <Link
                to="/strategy/list"
                className="mt-1.5 block w-full rounded-md border border-gray-700 py-1.5 text-center text-xs text-gray-400 hover:bg-gray-800"
              >
                전략 목록에서 관리
              </Link>
            </div>
          )}

          {/* 빈 상태 */}
          {!complexity && !evalResult && !strategyId && (
            <div className="text-center text-xs text-gray-600">
              <p>검증·평가 결과가 여기에 표시됩니다</p>
            </div>
          )}
        </aside>
      </div>

      {/* 저장 모달 */}
      {showSaveModal && (
        <StrategySaveModal
          strategyId={strategyId}
          userId={registeredUserId}
          entry={entryDsl}
          exit={exitDsl}
          initialName={loadedStrategy?.name ?? researchDraftMeta?.name ?? aiDraftMeta?.name}
          initialNameKo={loadedStrategy?.nameKo ?? researchDraftMeta?.nameKo ?? aiDraftMeta?.name}
          initialUniverse={loadedStrategy?.universe}
          initialTf={evalForm.interval}
          initialCode={evalForm.code}
          initialCodes={loadedStrategy?.codes}
          initialCodeNames={loadedStrategy?.codeNames}
          initialStopLossPct={
            loadedStrategy?.stopLossPct ?? researchDraftMeta?.stopLossPct ?? aiDraftMeta?.riskPlan.fixedStopLossPct ?? undefined
          }
          initialTakeProfitPct={
            loadedStrategy?.takeProfitPct ?? researchDraftMeta?.takeProfitPct ?? aiDraftMeta?.riskPlan.takeProfitPct ?? undefined
          }
          initialMinHoldBars={loadedStrategy?.minHoldBars ?? aiDraftMeta?.riskPlan.minHoldBars ?? null}
          initialViActionPolicy={loadedStrategy?.viActionPolicy ?? null}
          initialScaleIn={loadedStrategy?.scaleIn}
          initialPartialExits={loadedStrategy?.partialExits}
          onSaved={handleSaved}
          onClose={() => setShowSaveModal(false)}
        />
      )}

      {/* 자연어 입력 모달 */}
      {showNaturalLang && (
        <NaturalLanguageInput
          onResult={handleNaturalResult}
          onClose={() => setShowNaturalLang(false)}
        />
      )}

      {/* AI 전략 생성 모달 */}
      {showAiGenerate && (
        <StrategyAiGenerateModal
          userId={registeredUserId}
          defaultInterval={evalForm.interval}
          onLoadDraft={handleAiDraftLoad}
          onClose={() => setShowAiGenerate(false)}
        />
      )}

      {/* DSL 직접 편집 */}
      {showDslEditor && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/60">
          <div className="w-full max-w-lg p-4">
            <DslTextEditor
              dsl={dslEditorTab === 'entry' ? entryDsl : exitDsl}
              onDslChange={handleDslChange}
              onClose={() => setShowDslEditor(false)}
              tabLabel={dslEditorTab}
            />
          </div>
        </div>
      )}

      {/* T-SC Phase 3 — 종목 찾기 모달 (ADR 0071 §8) */}
      {showScreener && strategyId && (
        <ScreenerResultModal
          strategyId={parseInt(strategyId, 10)}
          userId={registeredUserId}
          onClose={() => setShowScreener(false)}
        />
      )}

      {/* 토스트 알림 */}
      {toast && (
        <div
          className={[
            'fixed bottom-4 right-4 z-50 rounded-xl px-4 py-3 text-sm font-medium shadow-lg transition-all',
            toastType === 'success'
              ? 'bg-emerald-900 text-emerald-200'
              : 'bg-red-900 text-red-200',
          ].join(' ')}
          role="status"
        >
          {toast}
        </div>
      )}
    </div>
  )
}
