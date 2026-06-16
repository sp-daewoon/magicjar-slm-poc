import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  strategyResearchApi,
  strategyResearchErrorMessage,
  type StrategyResearchAiDiscoveryCorePromptDto,
  type StrategyResearchAiDiscoveryCorePromptUpdateRequestDto,
  type StrategyResearchAiDiscoveryItemDto,
  type StrategyResearchAiDiscoveryItemStatus,
  type StrategyResearchAiDiscoveryPresetCreateRequestDto,
  type StrategyResearchAiDiscoveryPresetDto,
  type StrategyResearchAiDiscoveryPresetRecommendItemDto,
  type StrategyResearchAiDiscoveryPresetRecommendResponseDto,
  type StrategyResearchAiDiscoveryPresetUpdateRequestDto,
  type StrategyResearchAiDiscoveryPromptPreviewDto,
  type StrategyResearchAiDiscoveryPromptPreviewRequestDto,
  type StrategyResearchAiDiscoveryRequestDto,
  type StrategyResearchAiDiscoveryResponseDto,
  type StrategyResearchCandidateDto,
  type StrategyResearchReportDto,
  type StrategyResearchRequestDto,
  type StrategyResearchTradeDto,
} from '../../api/strategyResearch'
import type { InstrumentSearchResultDto } from '../../api/instrument'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'
import { AiDiscoveryCard } from './AiDiscoveryCard'
import ResearchCandidateLibraryCard from './ResearchCandidateLibraryCard'
import { StrategyResearchChart } from '../../components/strategyResearch/StrategyResearchChart'
import { formatKst, formatMoney } from '../../components/scenario/scenarioReplayFormat'
import { kstInputToIso } from '../../components/scenario/scenarioReplayTime'
import {
  buildStrategyResearchDraft,
  storeStrategyResearchDraft,
} from '../../lib/strategyResearch/builderDraft'
import { useOnboardingStore } from '../../stores/onboarding'
import { useModalBehavior } from '../../hooks/useModalBehavior'

const INTERVALS = ['1m', '3m', '5m', '15m', '30m', '1h', '1d']
const MAX_RESEARCH_CANDLES_PER_RUN = 20_000
const MIN_RESEARCH_CANDLES = 40
const RESEARCH_CANDLE_PADDING_TRADING_DAYS = 5
const RESEARCH_FORM_STORAGE_KEY = 'magicjar.strategyResearch.form.v1'
const AI_DISCOVERY_DEFAULT_QUERY = '요즘 반도체와 AI 인프라 쪽에서 전략 리서치로 검증해볼 국내주'
const AI_DISCOVERY_MARKETS = ['', 'KOSPI', 'KOSDAQ', 'KONEX'] as const
// 발굴 프리셋 최근 사용 추적 — 최신순, 최대 5 보관. 상위 3개를 버튼으로 노출하고 나머지는 콤보로 넘긴다.
const DISCOVERY_PRESET_RECENT_KEY = 'magicjar.discovery.presetRecentIds'
const DISCOVERY_PRESET_RECENT_MAX = 5
const DISCOVERY_PRESET_BUTTON_COUNT = 3
const ONE_DAY_MS = 24 * 60 * 60 * 1000
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const CANDLES_PER_TRADING_DAY: Record<string, number> = {
  '1m': 391,
  '3m': 131,
  '5m': 79,
  '15m': 27,
  '30m': 14,
  '1h': 7,
  '1d': 1,
}

interface ResearchFormSettings {
  interval: string
  fromKst: string
  toKst: string
  targetProfitPct: number
  stopLossPct: number
  maxHoldBars: number
}

function formatDatePart(date: Date): string {
  const year = date.getUTCFullYear()
  const month = String(date.getUTCMonth() + 1).padStart(2, '0')
  const day = String(date.getUTCDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function defaultResearchFormSettings(now: Date = new Date()): ResearchFormSettings {
  const kstNow = new Date(now.getTime() + KST_OFFSET_MS)
  const toDayUtc = Date.UTC(kstNow.getUTCFullYear(), kstNow.getUTCMonth(), kstNow.getUTCDate())
  const fromDayUtc = toDayUtc - 7 * ONE_DAY_MS
  return {
    interval: '1d',
    fromKst: `${formatDatePart(new Date(fromDayUtc))}T09:00`,
    toKst: `${formatDatePart(new Date(toDayUtc))}T15:30`,
    targetProfitPct: 3,
    stopLossPct: 3,
    maxHoldBars: 12,
  }
}

function readResearchFormSettings(): ResearchFormSettings {
  const defaults = defaultResearchFormSettings()
  try {
    const raw = window.localStorage.getItem(RESEARCH_FORM_STORAGE_KEY)
    if (!raw) return defaults
    const parsed = JSON.parse(raw) as Partial<ResearchFormSettings>
    return {
      interval: typeof parsed.interval === 'string' && INTERVALS.includes(parsed.interval) ? parsed.interval : defaults.interval,
      fromKst: typeof parsed.fromKst === 'string' && parsed.fromKst ? parsed.fromKst : defaults.fromKst,
      toKst: typeof parsed.toKst === 'string' && parsed.toKst ? parsed.toKst : defaults.toKst,
      targetProfitPct: readStoredNumber(parsed.targetProfitPct, defaults.targetProfitPct),
      stopLossPct: readStoredNumber(parsed.stopLossPct, defaults.stopLossPct),
      maxHoldBars: readStoredNumber(parsed.maxHoldBars, defaults.maxHoldBars),
    }
  } catch {
    return defaults
  }
}

function readStoredNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function storeResearchFormSettings(settings: ResearchFormSettings) {
  try {
    window.localStorage.setItem(RESEARCH_FORM_STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // localStorage 비가용 환경에서는 입력값 저장만 생략한다.
  }
}

function datePartToUtcDay(value: string): number | null {
  const [year, month, day] = value.slice(0, 10).split('-').map(Number)
  if (!year || !month || !day) return null
  return Date.UTC(year, month - 1, day)
}

function estimateResearchCandles(
  interval: string,
  fromKst: string,
  toKst: string,
): number | null {
  const fromDay = datePartToUtcDay(fromKst)
  const toDay = datePartToUtcDay(toKst)
  const candlesPerTradingDay = CANDLES_PER_TRADING_DAY[interval]
  if (fromDay == null || toDay == null || !candlesPerTradingDay || fromDay > toDay) return null

  let tradingDays = 0
  for (let day = fromDay; day <= toDay; day += ONE_DAY_MS) {
    const dow = new Date(day).getUTCDay()
    if (dow !== 0 && dow !== 6) tradingDays += 1
  }
  return tradingDays * candlesPerTradingDay
}

function readRecentPresetIds(): string[] {
  try {
    const raw = window.localStorage.getItem(DISCOVERY_PRESET_RECENT_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((id): id is string => typeof id === 'string')
  } catch {
    return []
  }
}

function bumpRecentPresetIds(prev: string[], presetId: string): string[] {
  const next = [presetId, ...prev.filter((id) => id !== presetId)]
  return next.slice(0, DISCOVERY_PRESET_RECENT_MAX)
}

function sameStringList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function mergeAiDiscoveryCandleAvailability(
  current: StrategyResearchAiDiscoveryResponseDto,
  latest: StrategyResearchAiDiscoveryResponseDto,
): StrategyResearchAiDiscoveryResponseDto {
  const latestByItemKey = new Map(latest.items.map((item) => [item.itemKey, item]))
  const latestByCode = new Map(latest.items.filter((item) => item.code).map((item) => [item.code, item]))
  let changed = false

  const items = current.items.map((item) => {
    const refreshed = latestByItemKey.get(item.itemKey) ?? (item.code ? latestByCode.get(item.code) : undefined)
    if (!refreshed) return item

    const hasCount = Object.prototype.hasOwnProperty.call(refreshed, 'candleCount')
    const hasAvailability = Object.prototype.hasOwnProperty.call(refreshed, 'hasResearchCandles')
    if (!hasCount && !hasAvailability) return item

    const nextCandleCount = hasCount ? refreshed.candleCount ?? null : item.candleCount
    const nextHasResearchCandles = hasAvailability ? refreshed.hasResearchCandles ?? null : item.hasResearchCandles
    if (nextCandleCount === item.candleCount && nextHasResearchCandles === item.hasResearchCandles) return item

    changed = true
    return {
      ...item,
      candleCount: nextCandleCount,
      hasResearchCandles: nextHasResearchCandles,
    }
  })

  const warningsChanged = !sameStringList(current.warnings, latest.warnings)
  if (!changed && !warningsChanged) return current
  return {
    ...current,
    warnings: latest.warnings,
    items,
  }
}

function formatPct(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function pctClass(value: number): string {
  if (value > 0) return 'text-emerald-300'
  if (value < 0) return 'text-red-300'
  return 'text-gray-300'
}

function regimeLabel(type: string): string {
  if (type === 'DOWNTREND') return '하락장'
  if (type === 'UPTREND') return '상승장'
  if (type === 'SIDEWAYS') return '횡보'
  return type
}

function exitReasonLabel(reason: StrategyResearchTradeDto['exitReason']): string {
  if (reason === 'TAKE_PROFIT') return '익절'
  if (reason === 'STOP_LOSS') return '손절'
  if (reason === 'TIME_EXIT') return '시간 청산'
  return reason
}

function candidateTone(candidate: StrategyResearchCandidateDto): string {
  if (candidate.stats.totalReturnPct > 0) return 'border-emerald-800 bg-emerald-950/20'
  if (candidate.stats.trades === 0) return 'border-gray-800 bg-gray-900'
  return 'border-amber-800 bg-amber-950/20'
}

function instrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function extractInstrumentCode(value: string): string | null {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : null
}

export function StrategyResearchPage() {
  const navigate = useNavigate()
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const userIdText = registeredUserId == null ? null : String(registeredUserId)
  const [initialResearchForm] = useState(() => readResearchFormSettings())
  const [code, setCode] = useState('')
  const [instrumentInput, setInstrumentInput] = useState('')
  const [selectedInstrumentName, setSelectedInstrumentName] = useState<string | null>(null)
  const [interval, setInterval] = useState(initialResearchForm.interval)
  const [fromKst, setFromKst] = useState(initialResearchForm.fromKst)
  const [toKst, setToKst] = useState(initialResearchForm.toKst)
  const [targetProfitPct, setTargetProfitPct] = useState(initialResearchForm.targetProfitPct)
  const [stopLossPct, setStopLossPct] = useState(initialResearchForm.stopLossPct)
  const [maxHoldBars, setMaxHoldBars] = useState(initialResearchForm.maxHoldBars)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [discoveryQuery, setDiscoveryQuery] = useState(AI_DISCOVERY_DEFAULT_QUERY)
  const [discoveryMarket, setDiscoveryMarket] = useState<(typeof AI_DISCOVERY_MARKETS)[number]>('')
  const [discoveryLimit, setDiscoveryLimit] = useState(6)
  const [discoveryError, setDiscoveryError] = useState<string | null>(null)
  const [discoveryReport, setDiscoveryReport] = useState<StrategyResearchAiDiscoveryResponseDto | null>(null)
  const [selectedDiscoveryPresetId, setSelectedDiscoveryPresetId] = useState<string | null>(null)
  const [discoveryPresetTouched, setDiscoveryPresetTouched] = useState(false)
  // 최근 사용 프리셋 id (최신순) — localStorage 동기화. 상위 3개 버튼 + 나머지 콤보 구성에 사용.
  const [recentPresetIds, setRecentPresetIds] = useState<string[]>(() => readRecentPresetIds())
  const [promptPreviewOpen, setPromptPreviewOpen] = useState(false)
  const [promptPreview, setPromptPreview] = useState<StrategyResearchAiDiscoveryPromptPreviewDto | null>(null)
  const [presetSettingsOpen, setPresetSettingsOpen] = useState(false)
  const [editingPresetId, setEditingPresetId] = useState<string | null>(null)
  const [presetDraft, setPresetDraft] = useState<StrategyResearchAiDiscoveryPresetUpdateRequestDto | null>(null)
  const [presetSettingsError, setPresetSettingsError] = useState<string | null>(null)
  const [recommendOpen, setRecommendOpen] = useState(false)
  const [recommendHint, setRecommendHint] = useState('')
  const [recommendError, setRecommendError] = useState<string | null>(null)
  const [recommendResult, setRecommendResult] =
    useState<StrategyResearchAiDiscoveryPresetRecommendResponseDto | null>(null)
  // 추천 항목 중 "프리셋으로 추가" 완료된 인덱스 집합 — 추가됨 상태 + 버튼 비활성
  const [recommendAddedIndexes, setRecommendAddedIndexes] = useState<number[]>([])
  const [recommendAddingIndex, setRecommendAddingIndex] = useState<number | null>(null)
  const candleRecollectOptions = useMemo(() => {
    try {
      return {
        from: kstInputToIso(fromKst),
        to: kstInputToIso(toKst),
        paddingTradingDays: RESEARCH_CANDLE_PADDING_TRADING_DAYS,
      }
    } catch {
      return null
    }
  }, [fromKst, toKst])
  const latestDiscoveryOptions = useMemo(
    () =>
      candleRecollectOptions
        ? {
            interval,
            from: candleRecollectOptions.from,
            to: candleRecollectOptions.to,
          }
        : undefined,
    [candleRecollectOptions, interval],
  )
  const [corePromptDraft, setCorePromptDraft] = useState('')
  const [corePromptTouched, setCorePromptTouched] = useState(false)
  const [corePromptError, setCorePromptError] = useState<string | null>(null)
  // GET/PUT/reset 중 가장 최근 권위 DTO — 뱃지(isDefault) 표시 단일 출처
  const [corePromptMeta, setCorePromptMeta] = useState<StrategyResearchAiDiscoveryCorePromptDto | null>(null)
  const [discoveryTouched, setDiscoveryTouched] = useState(false)
  // 전체 가져오기 순차 큐: 처리 대기 code 목록
  const [bulkQueue, setBulkQueue] = useState<string[]>([])
  // 전체 가져오기 누적 진행 (성공/실패)
  const [bulkProgress, setBulkProgress] = useState<{
    total: number
    done: number
    failed: { code: string; name: string; error?: string }[]
  } | null>(null)

  // 3종 팝업 공용 동작 — ESC 닫기 + body scroll lock (사용자 보고 진원지: 프리셋 설정·AI 추천·프롬프트 보기)
  // AI 추천 모달은 닫아도 mutation은 백그라운드 지속 — 기존 닫기 버튼과 동일 동작.
  useModalBehavior({ open: promptPreviewOpen, onClose: () => setPromptPreviewOpen(false) })
  useModalBehavior({ open: presetSettingsOpen, onClose: () => setPresetSettingsOpen(false) })
  useModalBehavior({ open: recommendOpen, onClose: () => setRecommendOpen(false) })

  const mutation = useMutation<StrategyResearchReportDto, unknown, StrategyResearchRequestDto>({
    mutationFn: strategyResearchApi.propose,
    onSuccess: (report) => {
      setSelectedKey(report.candidates[0]?.key ?? null)
      setFormError(null)
    },
    onError: (error) => setFormError(strategyResearchErrorMessage(error)),
  })

  const discoveryMutation = useMutation<
    StrategyResearchAiDiscoveryResponseDto,
    unknown,
    StrategyResearchAiDiscoveryRequestDto
  >({
    mutationFn: strategyResearchApi.discoverWithAi,
    onSuccess: (report) => {
      setDiscoveryReport(report)
      setDiscoveryQuery(report.query)
      setSelectedDiscoveryPresetId(report.presetId ?? null)
      setDiscoveryMarket((report.market ?? '') as (typeof AI_DISCOVERY_MARKETS)[number])
      setDiscoveryLimit(report.limit)
      setDiscoveryError(null)
    },
    onError: (error) => setDiscoveryError(strategyResearchErrorMessage(error)),
  })

  const discoveryPresetQuery = useQuery({
    queryKey: ['strategy-research', 'ai-discovery', 'presets'],
    queryFn: strategyResearchApi.getAiDiscoveryPresets,
    staleTime: 60_000,
  })

  const presetRecommendMutation = useMutation<
    StrategyResearchAiDiscoveryPresetRecommendResponseDto,
    unknown,
    { hint: string | null; count: number }
  >({
    mutationFn: ({ hint, count }) =>
      strategyResearchApi.recommendAiDiscoveryPresets({ userId: userIdText!, hint, count }),
    onSuccess: (result) => {
      setRecommendResult(result)
      setRecommendAddedIndexes([])
      setRecommendError(null)
    },
    onError: (error) => setRecommendError(strategyResearchErrorMessage(error)),
  })

  // 추천 카드 → 프리셋 추가 전용 mutation (프리셋 설정 편집 상태를 건드리지 않음)
  const recommendAddMutation = useMutation<
    StrategyResearchAiDiscoveryPresetDto,
    unknown,
    { index: number; item: StrategyResearchAiDiscoveryPresetRecommendItemDto }
  >({
    mutationFn: ({ item }) => {
      const nextSortOrder =
        (discoveryPresets.reduce((max, preset) => Math.max(max, preset.sortOrder), 0) || 0) + 10
      return strategyResearchApi.createAiDiscoveryPreset({
        label: item.label,
        description: item.description,
        promptText: item.promptText,
        sortOrder: nextSortOrder,
        active: true,
      })
    },
    onSuccess: (_created, variables) => {
      setRecommendAddedIndexes((prev) =>
        prev.includes(variables.index) ? prev : [...prev, variables.index],
      )
      setRecommendAddingIndex(null)
      setRecommendError(null)
      void discoveryPresetQuery.refetch()
    },
    onError: (error) => {
      setRecommendAddingIndex(null)
      setRecommendError(strategyResearchErrorMessage(error))
    },
  })

  const promptPreviewMutation = useMutation<
    StrategyResearchAiDiscoveryPromptPreviewDto,
    unknown,
    StrategyResearchAiDiscoveryPromptPreviewRequestDto
  >({
    mutationFn: strategyResearchApi.previewAiDiscoveryPrompt,
    onSuccess: (preview) => {
      setPromptPreview(preview)
      setDiscoveryError(null)
    },
    onError: (error) => setDiscoveryError(strategyResearchErrorMessage(error)),
  })

  const presetCreateMutation = useMutation<
    StrategyResearchAiDiscoveryPresetDto,
    unknown,
    StrategyResearchAiDiscoveryPresetCreateRequestDto
  >({
    mutationFn: strategyResearchApi.createAiDiscoveryPreset,
    onSuccess: (created) => {
      setPresetSettingsError(null)
      setEditingPresetId(created.id)
      setSelectedDiscoveryPresetId(created.id)
      setDiscoveryPresetTouched(true)
      setPresetDraft({
        label: created.label,
        description: created.description,
        promptText: created.promptText,
        sortOrder: created.sortOrder,
        active: created.active,
      })
      void discoveryPresetQuery.refetch()
    },
    onError: (error) => setPresetSettingsError(strategyResearchErrorMessage(error)),
  })

  const presetUpdateMutation = useMutation<
    StrategyResearchAiDiscoveryPresetDto,
    unknown,
    { presetId: string; body: StrategyResearchAiDiscoveryPresetUpdateRequestDto }
  >({
    mutationFn: ({ presetId, body }) => strategyResearchApi.updateAiDiscoveryPreset(presetId, body),
    onSuccess: (updated) => {
      setPresetSettingsError(null)
      setEditingPresetId(updated.id)
      setPresetDraft({
        label: updated.label,
        description: updated.description,
        promptText: updated.promptText,
        sortOrder: updated.sortOrder,
        active: updated.active,
      })
      void discoveryPresetQuery.refetch()
    },
    onError: (error) => setPresetSettingsError(strategyResearchErrorMessage(error)),
  })

  const presetDeleteMutation = useMutation<void, unknown, string>({
    mutationFn: (presetId) => strategyResearchApi.deleteAiDiscoveryPreset(presetId),
    onSuccess: (_data, presetId) => {
      setPresetSettingsError(null)
      if (selectedDiscoveryPresetId === presetId) {
        setSelectedDiscoveryPresetId(null)
        setDiscoveryPresetTouched(true)
      }
      if (editingPresetId === presetId) {
        setEditingPresetId(null)
      }
      void discoveryPresetQuery.refetch()
    },
    onError: (error) => setPresetSettingsError(strategyResearchErrorMessage(error)),
  })

  const queryClient = useQueryClient()
  const CORE_PROMPT_QUERY_KEY = ['strategy-research', 'ai-discovery', 'core-prompt']

  const corePromptQuery = useQuery({
    queryKey: CORE_PROMPT_QUERY_KEY,
    queryFn: strategyResearchApi.getAiDiscoveryCorePrompt,
    staleTime: 60_000,
  })

  const corePromptUpdateMutation = useMutation<
    StrategyResearchAiDiscoveryCorePromptDto,
    unknown,
    StrategyResearchAiDiscoveryCorePromptUpdateRequestDto
  >({
    mutationFn: strategyResearchApi.updateAiDiscoveryCorePrompt,
    onSuccess: (dto) => {
      // PUT 응답이 권위 DTO — 쿼리 캐시까지 즉시 동기화해야 한다.
      // (캐시를 안 갱신하면 touched=false 전환 직후 effect가 stale GET(기본값)으로 draft를 되돌리고,
      //  그 상태에서 재저장 시 기본값이 DB를 덮어쓰는 수정본 유실 버그 — 2026-06-06 사용자 보고)
      queryClient.setQueryData(CORE_PROMPT_QUERY_KEY, dto)
      setCorePromptError(null)
      setCorePromptDraft(dto.promptText)
      setCorePromptMeta(dto)
      setCorePromptTouched(false)
    },
    onError: (error) => setCorePromptError(strategyResearchErrorMessage(error)),
  })

  const corePromptResetMutation = useMutation<StrategyResearchAiDiscoveryCorePromptDto, unknown>({
    mutationFn: strategyResearchApi.resetAiDiscoveryCorePrompt,
    onSuccess: (dto) => {
      queryClient.setQueryData(CORE_PROMPT_QUERY_KEY, dto)
      setCorePromptError(null)
      setCorePromptDraft(dto.promptText)
      setCorePromptMeta(dto)
      setCorePromptTouched(false)
    },
    onError: (error) => setCorePromptError(strategyResearchErrorMessage(error)),
  })

  const latestDiscoveryQuery = useQuery({
    queryKey: [
      'strategy-research',
      'ai-discovery',
      'latest',
      userIdText,
      latestDiscoveryOptions?.interval ?? null,
      latestDiscoveryOptions?.from ?? null,
      latestDiscoveryOptions?.to ?? null,
    ],
    queryFn: () => strategyResearchApi.getLatestAiDiscovery(userIdText!, latestDiscoveryOptions),
    enabled: userIdText !== null,
    retry: false,
    staleTime: 30_000,
  })

  const discoveryStatusMutation = useMutation<
    StrategyResearchAiDiscoveryResponseDto,
    unknown,
    { itemKey: string; status: StrategyResearchAiDiscoveryItemStatus }
  >({
    mutationFn: ({ itemKey, status }) =>
      strategyResearchApi.updateAiDiscoveryItemStatus({
        userId: userIdText!,
        itemKey,
        status,
      }),
    onSuccess: (report) => {
      setDiscoveryReport(report)
      setDiscoveryError(null)
    },
    onError: (error) => setDiscoveryError(strategyResearchErrorMessage(error)),
  })

  const clearDiscoveryMutation = useMutation<void, unknown>({
    mutationFn: () => strategyResearchApi.clearAiDiscovery(userIdText!),
    onSuccess: () => {
      setDiscoveryReport(null)
      setDiscoveryQuery(AI_DISCOVERY_DEFAULT_QUERY)
      setDiscoveryMarket('')
      setDiscoveryLimit(6)
      setSelectedDiscoveryPresetId(activeDiscoveryPresets[0]?.id ?? null)
      setDiscoveryPresetTouched(false)
      setDiscoveryError(null)
    },
    onError: (error) => setDiscoveryError(strategyResearchErrorMessage(error)),
  })

  const report = mutation.data
  const discoveryPresets = discoveryPresetQuery.data ?? []
  const activeDiscoveryPresets = useMemo(
    () => discoveryPresets.filter((preset) => preset.active),
    [discoveryPresets],
  )
  const selectedDiscoveryPreset = useMemo(
    () => discoveryPresets.find((preset) => preset.id === selectedDiscoveryPresetId) ?? null,
    [discoveryPresets, selectedDiscoveryPresetId],
  )
  // 버튼으로 노출할 상위 3개: 선정은 최근 사용 순(localStorage, 이력 없으면 sortOrder fallback),
  // **표시는 라벨 글자순 고정** — 클릭마다 버튼 순서가 재배열되는 불편 제거(사용자 지시 2026-06-08).
  // 같은 3개 구성이면 무엇을 클릭해도 위치 불변, 새 프리셋이 셋에 진입할 때만 재배열된다.
  // 현재 선택된 프리셋이 콤보 쪽에 있어도 항상 버튼에 포함시켜 시각 식별을 보장한다.
  const discoveryPresetButtons = useMemo(() => {
    const byId = new Map(activeDiscoveryPresets.map((preset) => [preset.id, preset]))
    const ordered: typeof activeDiscoveryPresets = []
    const seen = new Set<string>()
    for (const id of recentPresetIds) {
      const preset = byId.get(id)
      if (preset && !seen.has(id)) {
        ordered.push(preset)
        seen.add(id)
      }
    }
    for (const preset of activeDiscoveryPresets) {
      if (!seen.has(preset.id)) {
        ordered.push(preset)
        seen.add(preset.id)
      }
    }
    const buttons = ordered.slice(0, DISCOVERY_PRESET_BUTTON_COUNT)
    // 선택된 프리셋이 버튼 밖(콤보)에 있으면 마지막 버튼과 교체해 항상 버튼에서 식별되게 한다.
    if (
      selectedDiscoveryPresetId &&
      byId.has(selectedDiscoveryPresetId) &&
      !buttons.some((preset) => preset.id === selectedDiscoveryPresetId)
    ) {
      buttons[buttons.length - 1] = byId.get(selectedDiscoveryPresetId)!
    }
    // 선정 결과를 라벨 글자순으로 고정 정렬 (ko locale — 한글/영문 혼용 라벨 안정).
    return [...buttons].sort((a, b) => a.label.localeCompare(b.label, 'ko'))
  }, [activeDiscoveryPresets, recentPresetIds, selectedDiscoveryPresetId])
  const discoveryPresetComboItems = useMemo(() => {
    const buttonIds = new Set(discoveryPresetButtons.map((preset) => preset.id))
    return activeDiscoveryPresets.filter((preset) => !buttonIds.has(preset.id))
  }, [activeDiscoveryPresets, discoveryPresetButtons])
  const editingPreset = useMemo(
    () => discoveryPresets.find((preset) => preset.id === editingPresetId) ?? discoveryPresets[0] ?? null,
    [discoveryPresets, editingPresetId],
  )
  const visibleDiscoveryItems = useMemo(
    () => discoveryReport?.items.filter((item) => item.status !== 'DISMISSED') ?? [],
    [discoveryReport],
  )
  // 리서치 캔들 부족 후보 — 정확한 수량보다 리서치 가능 여부만 사용한다.
  const emptyCandleItems = useMemo(
    () =>
      visibleDiscoveryItems.filter(
        (item) =>
          item.code &&
          item.matched &&
          (item.hasResearchCandles === false ||
            (item.hasResearchCandles == null &&
              item.candleCount != null &&
              item.candleCount < MIN_RESEARCH_CANDLES)),
      ),
    [visibleDiscoveryItems],
  )
  const keptDiscoveryCount = useMemo(
    () => discoveryReport?.items.filter((item) => item.status === 'KEPT').length ?? 0,
    [discoveryReport],
  )
  const dismissedDiscoveryCount = useMemo(
    () => discoveryReport?.items.filter((item) => item.status === 'DISMISSED').length ?? 0,
    [discoveryReport],
  )
  const discoveryLimitMin = Math.max(1, keptDiscoveryCount)
  const selectedCandidate = useMemo(() => {
    if (!report) return null
    return report.candidates.find((candidate) => candidate.key === selectedKey) ?? report.candidates[0] ?? null
  }, [report, selectedKey])

  useEffect(() => {
    if (latestDiscoveryQuery.data === undefined) return
    const latest = latestDiscoveryQuery.data
    if (discoveryTouched) {
      if (!latest) return
      setDiscoveryReport((current) => (current ? mergeAiDiscoveryCandleAvailability(current, latest) : current))
      return
    }
    setDiscoveryReport(latest)
    if (latest) {
      setDiscoveryQuery(latest.query)
      setSelectedDiscoveryPresetId(latest.presetId ?? null)
      setDiscoveryMarket((latest.market ?? '') as (typeof AI_DISCOVERY_MARKETS)[number])
      setDiscoveryLimit(latest.limit)
    }
  }, [discoveryTouched, latestDiscoveryQuery.data])

  useEffect(() => {
    if (discoveryPresetTouched) return
    if (selectedDiscoveryPresetId !== null) return
    if (latestDiscoveryQuery.data && latestDiscoveryQuery.data.presetId == null) return
    const firstPreset = activeDiscoveryPresets[0]
    if (firstPreset) {
      setSelectedDiscoveryPresetId(firstPreset.id)
    }
  }, [activeDiscoveryPresets, discoveryPresetTouched, latestDiscoveryQuery.data, selectedDiscoveryPresetId])

  useEffect(() => {
    if (corePromptQuery.data === undefined) return
    setCorePromptMeta((prev) => prev ?? corePromptQuery.data)
    if (corePromptTouched) return
    setCorePromptDraft(corePromptQuery.data.promptText)
  }, [corePromptQuery.data, corePromptTouched])

  useEffect(() => {
    storeResearchFormSettings({
      interval,
      fromKst,
      toKst,
      targetProfitPct,
      stopLossPct,
      maxHoldBars,
    })
  }, [fromKst, interval, maxHoldBars, stopLossPct, targetProfitPct, toKst])

  useEffect(() => {
    if (!presetSettingsOpen) return
    const preset = editingPreset
    if (!preset) {
      setPresetDraft(null)
      return
    }
    setEditingPresetId(preset.id)
    setPresetDraft({
      label: preset.label,
      description: preset.description,
      promptText: preset.promptText,
      sortOrder: preset.sortOrder,
      active: preset.active,
    })
  }, [editingPreset, presetSettingsOpen])

  const handleInstrumentRawChange = (value: string) => {
    setInstrumentInput(value)
    setSelectedInstrumentName(null)
    setCode(extractInstrumentCode(value) ?? '')
  }

  const handleInstrumentSelect = (result: InstrumentSearchResultDto) => {
    setCode(result.code)
    setInstrumentInput(instrumentDisplay(result))
    setSelectedInstrumentName(result.name)
  }

  const submitDiscovery = () => {
    const query = discoveryQuery.trim()
    // 직접 입력 모드(presetId 없음)에서는 프롬프트 필수. 프리셋 선택 시 추가 요청은 선택.
    if (!query && selectedDiscoveryPresetId === null) {
      setDiscoveryError('직접 입력 모드에서는 프롬프트를 입력해주세요')
      return
    }
    const normalizedLimit = Math.max(1, Math.min(12, Math.trunc(discoveryLimit || 1)))
    if (keptDiscoveryCount > normalizedLimit) {
      setDiscoveryLimit(keptDiscoveryCount)
      setDiscoveryError(
        `유지 후보가 ${keptDiscoveryCount}건이라 표시 개수는 최소 ${keptDiscoveryCount}건입니다. ${normalizedLimit}건만 보려면 유지 해제하거나 초기화하세요.`,
      )
      return
    }
    setDiscoveryError(null)
    setDiscoveryTouched(true)
    discoveryMutation.mutate({
      userId: userIdText!,
      query,
      presetId: selectedDiscoveryPresetId,
      market: discoveryMarket,
      limit: normalizedLimit,
      interval,
      from: kstInputToIso(fromKst),
      to: kstInputToIso(toKst),
    })
  }

  const bumpRecentPreset = (presetId: string) => {
    setRecentPresetIds((prev) => {
      const next = bumpRecentPresetIds(prev, presetId)
      try {
        window.localStorage.setItem(DISCOVERY_PRESET_RECENT_KEY, JSON.stringify(next))
      } catch {
        // localStorage 비가용(시크릿 모드 등) — 인메모리 순서만 유지하고 무시한다.
      }
      return next
    })
  }

  const selectDiscoveryPreset = (presetId: string | null) => {
    setSelectedDiscoveryPresetId(presetId)
    setDiscoveryPresetTouched(true)
    setDiscoveryTouched(true)
    if (presetId) bumpRecentPreset(presetId)
  }

  const openPromptPreview = () => {
    const query = discoveryQuery.trim()
    if (!query && selectedDiscoveryPresetId === null) {
      setDiscoveryError('직접 입력 모드에서는 프롬프트를 먼저 입력해주세요')
      return
    }
    const normalizedLimit = Math.max(1, Math.min(12, Math.trunc(discoveryLimit || 1)))
    setPromptPreviewOpen(true)
    setPromptPreview(null)
    setDiscoveryError(null)
    promptPreviewMutation.mutate({
      userId: userIdText!,
      query,
      presetId: selectedDiscoveryPresetId,
      market: discoveryMarket,
      limit: normalizedLimit,
    })
  }

  const openPresetSettings = () => {
    setPresetSettingsOpen(true)
    setPresetSettingsError(null)
    setEditingPresetId(selectedDiscoveryPresetId ?? discoveryPresets[0]?.id ?? null)
  }

  const openRecommend = () => {
    setRecommendOpen(true)
    setRecommendError(null)
  }

  const submitRecommend = () => {
    setRecommendError(null)
    presetRecommendMutation.mutate({
      hint: recommendHint.trim() ? recommendHint.trim() : null,
      count: 3,
    })
  }

  const addRecommendedPreset = (
    index: number,
    item: StrategyResearchAiDiscoveryPresetRecommendItemDto,
  ) => {
    if (recommendAddedIndexes.includes(index)) return
    setRecommendAddingIndex(index)
    recommendAddMutation.mutate({ index, item })
  }

  const createPresetDraft = () => {
    const nextSortOrder = (discoveryPresets.reduce((max, preset) => Math.max(max, preset.sortOrder), 0) || 0) + 10
    presetCreateMutation.mutate({
      label: '새 발굴 프리셋',
      description: '검증하고 싶은 발굴 로직을 설명하세요',
      promptText: '현재 시장에서 사용자가 입력한 조건과 결합해 Screener와 Backtest로 검증할 후보 종목을 제안해라.',
      sortOrder: nextSortOrder,
      active: true,
    })
  }

  const savePresetDraft = () => {
    if (!editingPreset || !presetDraft) return
    if (!presetDraft.label.trim() || !presetDraft.description.trim() || !presetDraft.promptText.trim()) {
      setPresetSettingsError('프리셋 이름, 설명, 프롬프트를 모두 입력해주세요')
      return
    }
    presetUpdateMutation.mutate({
      presetId: editingPreset.id,
      body: {
        label: presetDraft.label.trim(),
        description: presetDraft.description.trim(),
        promptText: presetDraft.promptText.trim(),
        sortOrder: presetDraft.sortOrder,
        active: presetDraft.active,
      },
    })
  }

  const presetIsBuiltin = (preset: StrategyResearchAiDiscoveryPresetDto): boolean => {
    if (typeof preset.builtIn === 'boolean') return preset.builtIn
    return !preset.id.startsWith('custom_')
  }

  const deletePreset = (preset: StrategyResearchAiDiscoveryPresetDto) => {
    const builtinNote = presetIsBuiltin(preset)
      ? '\n기본 제공 프리셋은 삭제 후 자동 복구되지 않습니다.'
      : ''
    if (!window.confirm(`'${preset.label}' 프리셋을 삭제합니다. 되돌릴 수 없습니다.${builtinNote}`)) {
      return
    }
    presetDeleteMutation.mutate(preset.id)
  }

  const saveCorePrompt = () => {
    const text = corePromptDraft.trim()
    if (!text) {
      setCorePromptError('Core Wrapper 프롬프트를 입력해주세요')
      return
    }
    corePromptUpdateMutation.mutate({ promptText: text })
  }

  const resetCorePrompt = () => {
    if (!window.confirm('Core Wrapper를 기본값으로 복원합니다. 현재 수정본은 사라집니다.')) {
      return
    }
    corePromptResetMutation.mutate()
  }

  const updateDiscoveryItemStatus = (
    item: StrategyResearchAiDiscoveryItemDto,
    status: StrategyResearchAiDiscoveryItemStatus,
  ) => {
    setDiscoveryTouched(true)
    discoveryStatusMutation.mutate({ itemKey: item.itemKey, status })
  }

  const selectDiscoveryItem = (item: StrategyResearchAiDiscoveryItemDto) => {
    if (!item.code) {
      setDiscoveryError('종목 마스터와 매칭된 후보만 선택할 수 있습니다')
      return
    }
    if (
      item.hasResearchCandles === false ||
      (item.hasResearchCandles == null && item.candleCount != null && item.candleCount < MIN_RESEARCH_CANDLES)
    ) {
      setDiscoveryError('선택한 interval·기간에 리서치 가능한 candle이 부족합니다. 캔들 가져오기 후 다시 선택해주세요')
      return
    }
    const result: InstrumentSearchResultDto = {
      code: item.code,
      name: item.name,
      market: item.market ?? 'UNKNOWN',
      sector: item.sector,
      score: 1,
    }
    handleInstrumentSelect(result)
    setFormError(null)
  }

  const handleCandlesFetched = (_itemCode: string, _inserted: number) => {
    void latestDiscoveryQuery.refetch().then((result) => {
      if (result.data !== undefined) {
        setDiscoveryReport(result.data)
      }
    })
  }

  // 순차 큐의 현재 활성 code (큐 맨 앞) — 해당 카드만 자동 시작
  const activeFetchCode = bulkQueue[0] ?? null

  const handleFetchSettled = (
    itemCode: string,
    result: { ok: boolean; inserted: number; error?: string },
  ) => {
    setBulkQueue((prev) => {
      if (prev[0] !== itemCode) return prev
      return prev.slice(1)
    })
    setBulkProgress((prev) => {
      if (!prev) return prev
      if (result.ok) return { ...prev, done: prev.done + 1 }
      const name = visibleDiscoveryItems.find((i) => i.code === itemCode)?.name ?? itemCode
      return {
        ...prev,
        done: prev.done + 1,
        failed: [...prev.failed, { code: itemCode, name, error: result.error }],
      }
    })
  }

  const startBulkFetch = () => {
    if (!candleRecollectOptions) {
      setDiscoveryError('시작/종료 시각 형식이 올바르지 않아 캔들을 가져올 수 없습니다')
      return
    }
    const codes = emptyCandleItems.map((item) => item.code!).filter(Boolean)
    if (codes.length === 0) return
    setBulkProgress({ total: codes.length, done: 0, failed: [] })
    setBulkQueue(codes)
  }

  const bulkRunning = bulkQueue.length > 0
  const bulkActiveItem = activeFetchCode
    ? visibleDiscoveryItems.find((item) => item.code === activeFetchCode) ?? null
    : null

  if (!registeredUserId) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <p className="text-sm text-gray-400">사용자 정보가 없습니다</p>
          <p className="mt-1 text-xs text-gray-600">셋업 마법사를 완료한 후 이용해주세요</p>
          <Link
            to="/setup"
            className="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            셋업 시작
          </Link>
        </div>
      </div>
    )
  }

  const submit = () => {
    try {
      setFormError(null)
      const selectedCode = code || extractInstrumentCode(instrumentInput)
      if (!selectedCode) {
        setFormError('종목명을 검색해서 선택하거나 6자리 종목코드를 입력해주세요')
        return
      }
      const estimatedCandles = estimateResearchCandles(interval, fromKst, toKst)
      if (estimatedCandles != null && estimatedCandles > MAX_RESEARCH_CANDLES_PER_RUN) {
        setFormError(
          `선택 기간의 예상 candle 수가 너무 많습니다: ${estimatedCandles.toLocaleString('ko-KR')} > ${MAX_RESEARCH_CANDLES_PER_RUN.toLocaleString('ko-KR')}. 기간을 줄이거나 더 큰 interval을 선택해주세요.`,
        )
        return
      }
      mutation.mutate({
        userId: String(registeredUserId),
        code: selectedCode,
        interval,
        from: kstInputToIso(fromKst),
        to: kstInputToIso(toKst),
        targetProfitPct,
        stopLossPct,
        maxHoldBars,
      })
    } catch (error) {
      setFormError(error instanceof Error ? error.message : '입력값을 확인해주세요')
    }
  }

  const openBuilderDraft = (candidate: StrategyResearchCandidateDto) => {
    if (!report) return
    const result = buildStrategyResearchDraft(report, candidate)
    if (!result.draft) {
      setFormError(result.reason ?? '이 후보는 전략 빌더로 가져올 수 없습니다.')
      return
    }
    const draftId = storeStrategyResearchDraft(result.draft)
    navigate(`/strategy/builder?researchDraftId=${encodeURIComponent(draftId)}`)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-gray-100">전략 리서치</h1>
          <p className="mt-0.5 text-xs text-gray-500">캔들 기반 전략 후보·가상 진입/청산 분석</p>
        </div>
        <div className="flex gap-2">
          <Link
            to="/strategy/scenario-replay"
            className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          >
            시나리오 리플레이
          </Link>
          <Link
            to="/strategy/builder"
            className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          >
            전략 빌더
          </Link>
        </div>
      </div>

      <div className="mb-4 grid gap-2 rounded-lg border border-sky-900/60 bg-sky-950/20 p-3 text-xs text-sky-200 sm:grid-cols-3">
        <div>
          <span className="font-semibold text-sky-100">리서치 전용</span>
          <span className="ml-2 text-sky-400">/strategy/research</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">가상 분석</span>
          <span className="ml-2 text-sky-400">KIS 주문 미발행</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">저장 없음</span>
          <span className="ml-2 text-sky-400">후보 초안만 표시</span>
        </div>
      </div>

      <ResearchCandidateLibraryCard registeredUserId={registeredUserId} />

      <section className="mb-4 rounded-lg border border-gray-800 bg-gray-900 p-4" aria-labelledby="ai-discovery-heading">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 id="ai-discovery-heading" className="text-sm font-semibold text-gray-200">
              AI 종목발굴
            </h2>
            <p className="mt-0.5 text-xs text-gray-500">Claude CLI 후보 · 종목 마스터 매핑</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {latestDiscoveryQuery.isLoading && (
              <span className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-500">
                저장된 결과 확인 중
              </span>
            )}
            <button
              type="button"
              onClick={openPromptPreview}
              disabled={promptPreviewMutation.isPending || discoveryMutation.isPending}
              className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
            >
              프롬프트 보기
            </button>
            <button
              type="button"
              onClick={openPresetSettings}
              disabled={discoveryPresetQuery.isLoading}
              className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
            >
              프리셋 설정
            </button>
            <button
              type="button"
              onClick={openRecommend}
              className="rounded border border-violet-700 bg-violet-950/30 px-2 py-1 text-xs font-semibold text-violet-200 hover:bg-violet-900/40"
            >
              AI 프리셋 추천
            </button>
            {discoveryReport && (
              <>
                <span className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-400">
                  {discoveryReport.provider}
                </span>
                <span className="rounded border border-gray-800 px-2 py-1 text-xs text-gray-500">
                  유지 {keptDiscoveryCount} · 제외 {dismissedDiscoveryCount}
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setDiscoveryTouched(true)
                    clearDiscoveryMutation.mutate()
                  }}
                  disabled={clearDiscoveryMutation.isPending || discoveryMutation.isPending}
                  className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  초기화
                </button>
              </>
            )}
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <span className="text-xs text-gray-500">프리셋</span>
          {/* 최근 사용 상위 3개를 버튼으로 노출 (이력 없으면 sortOrder 상위 fallback) */}
          {discoveryPresetButtons.map((preset) => {
            const active = preset.id === selectedDiscoveryPresetId
            return (
              <button
                key={preset.id}
                type="button"
                title={preset.description}
                onClick={() => selectDiscoveryPreset(preset.id)}
                disabled={discoveryMutation.isPending}
                className={[
                  'rounded border px-2.5 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-50',
                  active
                    ? 'border-indigo-500 bg-indigo-950/40 text-indigo-100'
                    : 'border-gray-700 text-gray-300 hover:bg-gray-800',
                ].join(' ')}
              >
                {preset.label}
              </button>
            )
          })}
          {/* 버튼에 안 들어간 나머지 활성 프리셋 — 선택 즉시 활성화 + 최근 이력 bump */}
          {discoveryPresetComboItems.length > 0 && (
            <select
              aria-label="다른 프리셋"
              value={
                selectedDiscoveryPresetId &&
                discoveryPresetComboItems.some((preset) => preset.id === selectedDiscoveryPresetId)
                  ? selectedDiscoveryPresetId
                  : ''
              }
              onChange={(event) => {
                if (event.target.value) selectDiscoveryPreset(event.target.value)
              }}
              disabled={discoveryMutation.isPending}
              className={[
                'rounded border px-2 py-1 text-xs focus:outline-none disabled:cursor-not-allowed disabled:opacity-50',
                selectedDiscoveryPresetId &&
                discoveryPresetComboItems.some((preset) => preset.id === selectedDiscoveryPresetId)
                  ? 'border-indigo-500 bg-indigo-950/40 text-indigo-100'
                  : 'border-gray-700 bg-gray-950 text-gray-300 hover:bg-gray-800',
              ].join(' ')}
            >
              <option value="">다른 프리셋…</option>
              {discoveryPresetComboItems.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.label}
                </option>
              ))}
            </select>
          )}
          {selectedDiscoveryPreset && (
            <span className="text-xs text-gray-500">{selectedDiscoveryPreset.description}</span>
          )}
          {/* 직접 입력 — 행 최우측 끝으로 밀어낸다 (작업 3) */}
          <button
            type="button"
            onClick={() => selectDiscoveryPreset(null)}
            disabled={discoveryMutation.isPending}
            className={[
              'ml-auto rounded border px-2.5 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-50',
              selectedDiscoveryPresetId === null
                ? 'border-blue-500 bg-blue-950/40 text-blue-100'
                : 'border-gray-700 text-gray-300 hover:bg-gray-800',
            ].join(' ')}
          >
            직접 입력
          </button>
        </div>

        <div className="mt-4 grid gap-3 lg:grid-cols-12">
          <label className="lg:col-span-7">
            <span className="text-xs text-gray-500">
              프롬프트{' '}
              {selectedDiscoveryPresetId === null ? (
                <span className="text-red-300">(필수)</span>
              ) : (
                <span className="text-gray-600">(선택)</span>
              )}
            </span>
            <textarea
              value={discoveryQuery}
              onChange={(event) => setDiscoveryQuery(event.target.value)}
              disabled={discoveryMutation.isPending}
              rows={3}
              placeholder={
                selectedDiscoveryPresetId === null
                  ? '발굴할 테마·조건을 입력하세요 (필수)'
                  : '(선택) 프리셋에 더할 추가 요청 — 비워두면 프리셋 지시문만 사용'
              }
              aria-label="프롬프트"
              className="mt-1 w-full resize-none rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 placeholder-gray-600 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">시장</span>
            <select
              value={discoveryMarket}
              onChange={(event) => setDiscoveryMarket(event.target.value as (typeof AI_DISCOVERY_MARKETS)[number])}
              disabled={discoveryMutation.isPending}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            >
              {AI_DISCOVERY_MARKETS.map((item) => (
                <option key={item || 'ALL'} value={item}>{item || '전체'}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">표시 개수</span>
            <input
              type="number"
              min={discoveryLimitMin}
              max="12"
              value={discoveryLimit}
              onChange={(event) => setDiscoveryLimit(Number(event.target.value))}
              disabled={discoveryMutation.isPending}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </label>
          {/* 발굴 버튼 — 옆 입력 필드와 동일한 label 패턴(빈 라벨 줄 + mt-1 h-10)으로 평행 정렬 (사용자 지정 마크업) */}
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">&nbsp;</span>
            <button
              type="button"
              onClick={submitDiscovery}
              disabled={discoveryMutation.isPending}
              aria-label={discoveryMutation.isPending ? '발굴 중' : '발굴'}
              className="mt-1 h-10 w-full rounded bg-indigo-600 px-4 text-sm font-semibold text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {discoveryMutation.isPending ? '발굴 중' : '발굴'}
            </button>
          </label>
        </div>

        {discoveryMutation.isPending && (
          <div
            className="mt-4 rounded border border-indigo-900 bg-indigo-950/20 px-3 py-3"
            role="status"
            aria-live="polite"
          >
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <span className="font-medium text-indigo-100">Claude CLI가 후보를 찾는 중입니다</span>
              <span className="text-indigo-300">최대 5분까지 기다립니다</span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-gray-800">
              <div className="ai-discovery-loading-bar h-full w-1/3 rounded-full bg-indigo-400" />
            </div>
          </div>
        )}

        {discoveryError && (
          <div className="mt-4 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
            {discoveryError}
          </div>
        )}

        {discoveryReport && (
          <div className="mt-4 space-y-3">
            <div className="rounded border border-gray-800 bg-gray-950/60 px-3 py-2 text-xs text-gray-400">
              유지한 후보는 재발굴 후에도 남고, 제외한 후보는 초기화 전까지 숨겨집니다.
            </div>
            {discoveryReport.warnings.length > 0 && (
              <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
                {discoveryReport.warnings.join(' · ')}
              </div>
            )}
            {emptyCandleItems.length > 0 && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded border border-amber-900 bg-amber-950/20 px-3 py-2">
                <span className="text-xs text-amber-200">
                  리서치 캔들이 부족한 매칭 후보 {emptyCandleItems.length}건 — KIS에서 {interval} 캔들을 앞뒤 5거래일 포함해 가져옵니다.
                </span>
                <button
                  type="button"
                  onClick={startBulkFetch}
                  disabled={bulkRunning || !candleRecollectOptions}
                  className="rounded border border-amber-700 px-2.5 py-1 text-xs font-semibold text-amber-200 hover:bg-amber-950/40 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {bulkRunning ? '캔들 수집 중' : '부족 후보 전체 가져오기'}
                </button>
              </div>
            )}
            {bulkProgress && (
              <div
                className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200"
                role="status"
                aria-live="polite"
              >
                {bulkRunning ? (
                  <span>
                    캔들 수집 중 {bulkProgress.done + 1}/{bulkProgress.total}
                    {bulkActiveItem ? ` — ${bulkActiveItem.name}` : ''}
                  </span>
                ) : (
                  <span>
                    캔들 수집 완료 {bulkProgress.done}/{bulkProgress.total}
                    {bulkProgress.failed.length > 0
                      ? ` — 실패 ${bulkProgress.failed.length}건: ${bulkProgress.failed
                          .map((f) => f.name)
                          .join(', ')}`
                      : ' — 전체 성공'}
                  </span>
                )}
              </div>
            )}
            {visibleDiscoveryItems.length === 0 ? (
              <div className="rounded border border-gray-800 bg-gray-950 px-4 py-8 text-center text-sm text-gray-500">
                표시할 후보가 없습니다. 제외 목록까지 비우려면 초기화 후 다시 발굴하세요.
              </div>
            ) : (
              <div className="grid gap-3 lg:grid-cols-3">
                {visibleDiscoveryItems.map((item, index) => (
                  <AiDiscoveryCard
                    key={`${item.itemKey}-${index}`}
                    item={item}
                    interval={interval}
                    recollectOptions={candleRecollectOptions}
                    activeFetchCode={activeFetchCode}
                    statusPending={discoveryStatusMutation.isPending}
                    onStatusChange={updateDiscoveryItemStatus}
                    onSelect={selectDiscoveryItem}
                    onCandlesFetched={handleCandlesFetched}
                    onFetchSettled={handleFetchSettled}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </section>

      <section className="rounded-lg border border-gray-800 bg-gray-900 p-4" aria-labelledby="research-form-heading">
        <h2 id="research-form-heading" className="text-sm font-semibold text-gray-200">
          후보 생성
        </h2>
        <div className="mt-4 grid gap-4 lg:grid-cols-12">
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">종목</span>
            <div className="mt-1">
              <InstrumentSearchInput
                value={instrumentInput}
                onRawChange={handleInstrumentRawChange}
                onSelect={handleInstrumentSelect}
                placeholder="종목명 또는 코드 검색"
              />
            </div>
            {(code || selectedInstrumentName) && (
              <p className="mt-1 text-xs text-gray-500">
                선택: {selectedInstrumentName && <span className="text-gray-300">{selectedInstrumentName}</span>}{' '}
                {code && <span className="font-mono text-blue-300">{code}</span>}
              </p>
            )}
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">캔들</span>
            <select
              value={interval}
              onChange={(event) => setInterval(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-2 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            >
              {INTERVALS.map((item) => (
                <option key={item} value={item}>{item}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">시작 시각 (KST)</span>
            <input
              type="datetime-local"
              value={fromKst}
              onChange={(event) => setFromKst(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-2 py-2 text-xs text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">종료 시각 (KST)</span>
            <input
              type="datetime-local"
              value={toKst}
              onChange={(event) => setToKst(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-2 py-2 text-xs text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">익절 %</span>
            <input
              type="number"
              min="0.1"
              max="10"
              step="0.1"
              value={targetProfitPct}
              onChange={(event) => setTargetProfitPct(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">손절 %</span>
            <input
              type="number"
              min="0.1"
              max="10"
              step="0.1"
              value={stopLossPct}
              onChange={(event) => setStopLossPct(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">보유</span>
            <input
              type="number"
              min="1"
              max="120"
              value={maxHoldBars}
              onChange={(event) => setMaxHoldBars(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          {/* 리서치 버튼 — 발굴 버튼과 동일한 label 패턴(빈 라벨 줄 + mt-1 h-10)으로 평행 정렬 (사용자 지정 마크업) */}
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">&nbsp;</span>
            <button
              type="button"
              onClick={submit}
              disabled={mutation.isPending}
              aria-label={mutation.isPending ? '분석 중' : '리서치'}
              className="mt-1 h-10 w-full rounded bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {mutation.isPending ? '분석 중' : '리서치'}
            </button>
          </label>
        </div>
        {formError && (
          <div className="mt-4 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
            {formError}
          </div>
        )}
      </section>

      {report && (
        <div className="mt-5 space-y-5">
          <section className="grid gap-3 md:grid-cols-4">
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">시장 상태</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{regimeLabel(report.marketRegime.type)}</div>
              <div className={['mt-1 text-sm tabular-nums', pctClass(report.marketRegime.trendPct)].join(' ')}>
                {formatPct(report.marketRegime.trendPct)}
              </div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">candle</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{report.candleCount.toLocaleString('ko-KR')}</div>
              <div className="mt-1 text-sm text-gray-500">{report.request.interval}</div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">분석 구간</div>
              <div className="mt-1 text-sm text-gray-200">{formatKst(report.request.from)}</div>
              <div className="mt-1 text-sm text-gray-500">{formatKst(report.request.to)}</div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">후보</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{report.candidates.length.toLocaleString('ko-KR')}</div>
              <div className="mt-1 text-sm text-gray-500">익절 {report.request.targetProfitPct}% · 손절 {report.request.stopLossPct}%</div>
            </div>
          </section>

          {report.warnings.length > 0 && (
            <section className="rounded border border-amber-900 bg-amber-950/20 px-4 py-3 text-sm text-amber-200">
              {report.warnings.join(' · ')}
            </section>
          )}

          <section className="rounded-lg border border-gray-800 bg-gray-900 p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 className="text-sm font-semibold text-gray-200">후보 전략</h2>
                <p className="mt-0.5 text-xs text-gray-500">{report.marketRegime.explanation}</p>
              </div>
            </div>
            <div className="grid gap-3 lg:grid-cols-3">
              {report.candidates.map((candidate) => (
                <button
                  key={candidate.key}
                  type="button"
                  onClick={() => setSelectedKey(candidate.key)}
                  className={[
                    'rounded border p-4 text-left transition-colors hover:border-blue-600',
                    candidateTone(candidate),
                    selectedCandidate?.key === candidate.key ? 'ring-1 ring-blue-500' : '',
                  ].join(' ')}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="text-sm font-semibold text-gray-100">{candidate.title}</div>
                      <div className="mt-1 text-xs text-gray-500">{candidate.marketFit}</div>
                    </div>
                    <span className="rounded border border-gray-700 px-2 py-0.5 text-xs text-gray-400">
                      {candidate.score}
                    </span>
                  </div>
                  <div className="mt-4 grid grid-cols-3 gap-2 text-xs">
                    <div>
                      <div className="text-gray-500">거래</div>
                      <div className="mt-1 text-gray-100">{candidate.stats.trades}</div>
                    </div>
                    <div>
                      <div className="text-gray-500">승률</div>
                      <div className="mt-1 text-gray-100">{candidate.stats.winRatePct}%</div>
                    </div>
                    <div>
                      <div className="text-gray-500">누적</div>
                      <div className={['mt-1', pctClass(candidate.stats.totalReturnPct)].join(' ')}>
                        {formatPct(candidate.stats.totalReturnPct)}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </section>

          {selectedCandidate && (
            <section className="rounded-lg border border-gray-800 bg-gray-900 p-4">
              <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="text-base font-semibold text-gray-100">{selectedCandidate.title}</h2>
                  <p className="mt-1 text-sm text-gray-500">{selectedCandidate.summary}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    MDD {selectedCandidate.stats.maxDrawdownPct}%
                  </span>
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    평균 {formatPct(selectedCandidate.stats.avgTradeReturnPct)}
                  </span>
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    연속손실 {selectedCandidate.stats.maxConsecutiveLosses}
                  </span>
                  <button
                    type="button"
                    onClick={() => openBuilderDraft(selectedCandidate)}
                    className="rounded bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
                  >
                    전략 빌더로 가져오기
                  </button>
                </div>
              </div>

              <StrategyResearchChart
                code={report.request.code}
                interval={report.request.interval}
                candles={report.candles}
                candidate={selectedCandidate}
              />

              <div className="mt-5 grid gap-4 lg:grid-cols-3">
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">진입 조건</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.entryRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">청산 조건</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.exitRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">검증 가드</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.riskRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
              </div>

              <div className="mt-5 grid gap-3 lg:grid-cols-2">
                <div className="rounded border border-gray-800 bg-gray-950 p-3">
                  <div className="text-xs text-gray-500">entry draft</div>
                  <pre className="mt-2 whitespace-pre-wrap break-words text-xs text-blue-200">{selectedCandidate.entryDslDraft}</pre>
                </div>
                <div className="rounded border border-gray-800 bg-gray-950 p-3">
                  <div className="text-xs text-gray-500">exit draft</div>
                  <pre className="mt-2 whitespace-pre-wrap break-words text-xs text-blue-200">{selectedCandidate.exitDslDraft}</pre>
                </div>
              </div>

              <div className="mt-5 overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-800 text-sm">
                  <thead>
                    <tr className="text-left text-xs text-gray-500">
                      <th className="px-3 py-2 font-medium">진입</th>
                      <th className="px-3 py-2 font-medium">청산</th>
                      <th className="px-3 py-2 text-right font-medium">진입가</th>
                      <th className="px-3 py-2 text-right font-medium">청산가</th>
                      <th className="px-3 py-2 text-right font-medium">수익률</th>
                      <th className="px-3 py-2 font-medium">근거</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-800">
                    {selectedCandidate.trades.slice(0, 40).map((trade, index) => (
                      <tr key={`${trade.entryTime}-${index}`} className="text-gray-300">
                        <td className="whitespace-nowrap px-3 py-2">{formatKst(trade.entryTime)}</td>
                        <td className="whitespace-nowrap px-3 py-2">{formatKst(trade.exitTime)} · {exitReasonLabel(trade.exitReason)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{formatMoney(trade.entryPrice)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{formatMoney(trade.exitPrice)}</td>
                        <td className={['px-3 py-2 text-right tabular-nums', pctClass(trade.returnPct)].join(' ')}>
                          {formatPct(trade.returnPct)}
                        </td>
                        <td className="min-w-56 px-3 py-2 text-xs text-gray-500">{trade.reason}</td>
                      </tr>
                    ))}
                    {selectedCandidate.trades.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-3 py-8 text-center text-sm text-gray-500">
                          조건을 만족한 가상 거래가 없습니다
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
      {promptPreviewOpen && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-label="AI 종목발굴 프롬프트 보기"
        >
          <div className="max-h-[86vh] w-full max-w-4xl overflow-y-auto rounded-lg border border-gray-800 bg-gray-900 shadow-xl">
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-gray-100">AI 종목발굴 프롬프트</h2>
                <p className="mt-0.5 text-xs text-gray-500">
                  {promptPreview?.preset?.label ?? '직접 입력'} · {promptPreview?.generatedAt ? formatKst(promptPreview.generatedAt) : '생성 중'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setPromptPreviewOpen(false)}
                className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
              >
                닫기
              </button>
            </div>
            <div className="space-y-3 p-4">
              {promptPreviewMutation.isPending && (
                <div className="rounded border border-indigo-900 bg-indigo-950/20 px-3 py-2 text-sm text-indigo-100">
                  최종 프롬프트를 조립하는 중입니다
                </div>
              )}
              {promptPreview?.warnings.length ? (
                <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
                  {promptPreview.warnings.join(' · ')}
                </div>
              ) : null}
              {promptPreview && (
                <>
                  <div className="rounded border border-gray-800 bg-gray-950 p-3">
                    <div className="text-xs font-semibold text-gray-400">Core Wrapper</div>
                    <pre className="mt-2 whitespace-pre-wrap break-words text-xs leading-5 text-gray-300">
                      {promptPreview.systemPrompt}
                    </pre>
                  </div>
                  <div className="rounded border border-gray-800 bg-gray-950 p-3">
                    <div className="text-xs font-semibold text-gray-400">Final Prompt</div>
                    <pre className="mt-2 whitespace-pre-wrap break-words text-xs leading-5 text-blue-100">
                      {promptPreview.finalPrompt}
                    </pre>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {presetSettingsOpen && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-label="AI 종목발굴 프리셋 설정"
        >
          <div className="max-h-[86vh] w-full max-w-5xl overflow-y-auto rounded-lg border border-gray-800 bg-gray-900 shadow-xl">
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-gray-100">AI 종목발굴 프리셋 설정</h2>
                <p className="mt-0.5 text-xs text-gray-500">Core Wrapper(전역 프롬프트)와 프리셋 intent prompt를 함께 관리합니다</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={createPresetDraft}
                  disabled={presetCreateMutation.isPending}
                  className="rounded border border-blue-700 px-2.5 py-1 text-xs text-blue-200 hover:bg-blue-950/30 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {presetCreateMutation.isPending ? '추가 중' : '새 프리셋'}
                </button>
                <button
                  type="button"
                  onClick={() => setPresetSettingsOpen(false)}
                  className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
                >
                  닫기
                </button>
              </div>
            </div>
            <div className="border-b border-gray-800 p-4">
              <div className="rounded border border-indigo-900/60 bg-indigo-950/20 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <h3 className="text-sm font-semibold text-indigo-100">Core Wrapper (전역 프롬프트)</h3>
                    {corePromptMeta && corePromptMeta.isDefault === false && (
                      <span className="rounded border border-amber-700 bg-amber-950/30 px-2 py-0.5 text-xs text-amber-200">
                        사용자 수정본
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={saveCorePrompt}
                      disabled={
                        corePromptQuery.isLoading ||
                        corePromptUpdateMutation.isPending ||
                        corePromptResetMutation.isPending
                      }
                      className="rounded bg-indigo-600 px-3 py-1 text-xs font-semibold text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {corePromptUpdateMutation.isPending ? '저장 중' : 'Core Wrapper 저장'}
                    </button>
                    <button
                      type="button"
                      onClick={resetCorePrompt}
                      disabled={
                        corePromptQuery.isLoading ||
                        corePromptUpdateMutation.isPending ||
                        corePromptResetMutation.isPending
                      }
                      className="rounded border border-gray-700 px-3 py-1 text-xs text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {corePromptResetMutation.isPending ? '복원 중' : '기본값 복원'}
                    </button>
                  </div>
                </div>
                <p className="mt-1 text-xs text-gray-500">
                  모든 프리셋·직접 입력 위에 공통으로 적용되는 전역 지시문입니다. 계속 개선해 나가는 자산이므로 직접 편집할 수 있습니다.
                </p>
                <label className="mt-2 block">
                  <span className="sr-only">Core Wrapper 프롬프트</span>
                  <textarea
                    value={corePromptDraft}
                    onChange={(event) => {
                      setCorePromptDraft(event.target.value)
                      setCorePromptTouched(true)
                    }}
                    disabled={corePromptQuery.isLoading}
                    rows={8}
                    aria-label="Core Wrapper 프롬프트"
                    placeholder={corePromptQuery.isLoading ? 'Core Wrapper를 불러오는 중입니다' : 'Core Wrapper 프롬프트'}
                    className="w-full resize-y rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm leading-6 text-gray-100 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
                  />
                </label>
                {corePromptError && (
                  <div className="mt-2 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
                    {corePromptError}
                  </div>
                )}
                {corePromptUpdateMutation.isSuccess && !corePromptTouched && !corePromptError && (
                  <div className="mt-2 rounded border border-emerald-900 bg-emerald-950/20 px-3 py-2 text-sm text-emerald-200">
                    Core Wrapper를 저장했습니다
                  </div>
                )}
                {corePromptResetMutation.isSuccess && !corePromptTouched && !corePromptError && (
                  <div className="mt-2 rounded border border-emerald-900 bg-emerald-950/20 px-3 py-2 text-sm text-emerald-200">
                    Core Wrapper를 기본값으로 복원했습니다
                  </div>
                )}
              </div>
            </div>
            <div className="grid gap-4 p-4 lg:grid-cols-12">
              {/* 6개 초과 시에만 스크롤 — 6행(≈ 84px·py-2 2줄 카드) 기준 max-h. 6개 이하는 자연 높이. */}
              <div className="max-h-[31rem] space-y-2 overflow-y-auto lg:col-span-4">
                {discoveryPresets.map((preset) => {
                  const active = preset.id === editingPreset?.id
                  return (
                    <button
                      key={preset.id}
                      type="button"
                      onClick={() => setEditingPresetId(preset.id)}
                      className={[
                        'w-full rounded border px-3 py-2 text-left',
                        active
                          ? 'border-indigo-500 bg-indigo-950/30'
                          : 'border-gray-800 bg-gray-950 hover:bg-gray-800/70',
                      ].join(' ')}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-sm font-semibold text-gray-100">{preset.label}</span>
                        <span className={preset.active ? 'text-xs text-emerald-300' : 'text-xs text-gray-500'}>
                          {preset.active ? '활성' : '비활성'}
                        </span>
                      </div>
                      <p className="mt-1 text-xs leading-5 text-gray-500">{preset.description}</p>
                    </button>
                  )
                })}
                {discoveryPresetQuery.isLoading && (
                  <div className="rounded border border-gray-800 bg-gray-950 px-3 py-6 text-center text-sm text-gray-500">
                    프리셋을 불러오는 중입니다
                  </div>
                )}
              </div>

              <div className="lg:col-span-8">
                {editingPreset && presetDraft ? (
                  <div className="space-y-3">
                    <label className="block">
                      <span className="text-xs text-gray-500">프리셋 이름</span>
                      <input
                        value={presetDraft.label}
                        onChange={(event) => setPresetDraft({ ...presetDraft, label: event.target.value })}
                        className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
                      />
                    </label>
                    <label className="block">
                      <span className="text-xs text-gray-500">설명</span>
                      <input
                        value={presetDraft.description}
                        onChange={(event) => setPresetDraft({ ...presetDraft, description: event.target.value })}
                        className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
                      />
                    </label>
                    <label className="block">
                      <span className="text-xs text-gray-500">Intent Prompt</span>
                      <textarea
                        value={presetDraft.promptText}
                        onChange={(event) => setPresetDraft({ ...presetDraft, promptText: event.target.value })}
                        rows={10}
                        className="mt-1 w-full resize-y rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm leading-6 text-gray-100 focus:border-blue-500 focus:outline-none"
                      />
                    </label>
                    <div className="flex flex-wrap items-end gap-3">
                      <label>
                        <span className="text-xs text-gray-500">정렬</span>
                        <input
                          type="number"
                          min="0"
                          max="1000"
                          value={presetDraft.sortOrder}
                          onChange={(event) => setPresetDraft({ ...presetDraft, sortOrder: Number(event.target.value) })}
                          className="mt-1 w-28 rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
                        />
                      </label>
                      <label className="flex items-center gap-2 rounded border border-gray-800 bg-gray-950 px-3 py-2 text-sm text-gray-300">
                        <input
                          type="checkbox"
                          checked={presetDraft.active}
                          onChange={(event) => setPresetDraft({ ...presetDraft, active: event.target.checked })}
                          className="h-4 w-4 accent-blue-600"
                        />
                        활성
                      </label>
                      <button
                        type="button"
                        onClick={savePresetDraft}
                        disabled={presetUpdateMutation.isPending}
                        className="rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {presetUpdateMutation.isPending ? '저장 중' : '저장'}
                      </button>
                      <button
                        type="button"
                        onClick={() => deletePreset(editingPreset)}
                        disabled={presetDeleteMutation.isPending}
                        className="rounded border border-red-800 px-4 py-2 text-sm font-semibold text-red-200 hover:bg-red-950/40 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {presetDeleteMutation.isPending ? '삭제 중' : '삭제'}
                      </button>
                    </div>
                    {presetSettingsError && (
                      <div className="rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
                        {presetSettingsError}
                      </div>
                    )}
                    {presetUpdateMutation.isSuccess && !presetSettingsError && (
                      <div className="rounded border border-emerald-900 bg-emerald-950/20 px-3 py-2 text-sm text-emerald-200">
                        프리셋을 저장했습니다
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="rounded border border-gray-800 bg-gray-950 px-3 py-10 text-center text-sm text-gray-500">
                    설정할 프리셋이 없습니다
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {recommendOpen && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-label="AI 프리셋 추천"
        >
          <div className="max-h-[86vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-violet-900/60 bg-gray-900 shadow-xl">
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-violet-100">AI 프리셋 추천</h2>
                <p className="mt-0.5 text-xs text-gray-500">
                  현재 프리셋 구성과 우리 발굴 구조에 맞는 새 프리셋을 AI가 제안합니다
                </p>
              </div>
              <button
                type="button"
                onClick={() => setRecommendOpen(false)}
                className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
              >
                닫기
              </button>
            </div>
            <div className="space-y-3 p-4">
              <label className="block">
                <span className="text-xs text-gray-500">원하는 방향 (선택)</span>
                <textarea
                  value={recommendHint}
                  onChange={(event) => setRecommendHint(event.target.value)}
                  disabled={presetRecommendMutation.isPending}
                  rows={2}
                  aria-label="원하는 방향 (선택)"
                  placeholder="예: 수급 기반 단타 / 낙폭 과대 반등 — 비워두면 AI가 현재 프리셋과 겹치지 않는 방향을 제안합니다"
                  className="mt-1 w-full resize-none rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 placeholder-gray-600 focus:border-violet-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
                />
              </label>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={submitRecommend}
                  disabled={presetRecommendMutation.isPending}
                  className="rounded bg-violet-600 px-4 py-2 text-sm font-semibold text-white hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {presetRecommendMutation.isPending ? '추천 생성 중' : '추천받기'}
                </button>
                {recommendResult && (
                  <span className="text-xs text-gray-500">{recommendResult.provider}</span>
                )}
              </div>

              {presetRecommendMutation.isPending && (
                <div
                  className="rounded border border-violet-900 bg-violet-950/20 px-3 py-3"
                  role="status"
                  aria-live="polite"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                    <span className="font-medium text-violet-100">Claude CLI가 추천을 생성 중입니다</span>
                    <span className="text-violet-300">최대 수 분까지 기다립니다</span>
                  </div>
                  <div className="mt-2 h-2 overflow-hidden rounded-full bg-gray-800">
                    <div className="ai-discovery-loading-bar h-full w-1/3 rounded-full bg-violet-400" />
                  </div>
                </div>
              )}

              {recommendError && (
                <div className="rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
                  {recommendError}
                </div>
              )}

              {recommendResult && recommendResult.warnings.length > 0 && (
                <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
                  {recommendResult.warnings.join(' · ')}
                </div>
              )}

              {recommendResult && (
                recommendResult.items.length === 0 ? (
                  <div className="rounded border border-gray-800 bg-gray-950 px-4 py-8 text-center text-sm text-gray-500">
                    추천 결과가 없습니다. 방향을 입력하고 다시 시도해보세요.
                  </div>
                ) : (
                  <div className="space-y-3">
                    {recommendResult.items.map((item, index) => {
                      const added = recommendAddedIndexes.includes(index)
                      const adding = recommendAddingIndex === index
                      return (
                        <div
                          key={`${item.label}-${index}`}
                          className="rounded border border-gray-800 bg-gray-950 p-3"
                        >
                          <div className="flex items-start justify-between gap-3">
                            <div className="min-w-0">
                              <div className="text-sm font-semibold text-gray-100">{item.label}</div>
                              <p className="mt-1 text-xs leading-5 text-gray-400">{item.description}</p>
                            </div>
                            <button
                              type="button"
                              onClick={() => addRecommendedPreset(index, item)}
                              disabled={added || adding}
                              className={[
                                'shrink-0 rounded px-3 py-1.5 text-xs font-semibold disabled:cursor-not-allowed',
                                added
                                  ? 'border border-emerald-800 bg-emerald-950/30 text-emerald-300'
                                  : 'bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-60',
                              ].join(' ')}
                            >
                              {added ? '추가됨' : adding ? '추가 중' : '프리셋으로 추가'}
                            </button>
                          </div>
                          <details className="mt-2 rounded border border-gray-800 bg-gray-900/60 px-3 py-2">
                            <summary className="cursor-pointer text-xs font-medium text-gray-400">
                              프롬프트 보기
                            </summary>
                            <pre className="mt-2 max-h-48 overflow-y-auto whitespace-pre-wrap break-words text-xs leading-5 text-blue-100">
                              {item.promptText}
                            </pre>
                          </details>
                          {item.rationale && (
                            <p className="mt-2 text-xs leading-5 text-gray-500">{item.rationale}</p>
                          )}
                        </div>
                      )
                    })}
                  </div>
                )
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
