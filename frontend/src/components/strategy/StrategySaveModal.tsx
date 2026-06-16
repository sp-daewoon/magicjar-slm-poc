/**
 * StrategySaveModal — 전략 저장 모달
 *
 * 입력 필드:
 *   name     — 전략 식별자 (한글·영문·숫자·공백·일부 구분자)
 *   nameKo   — 한국어 이름
 *   universe — 종목 유니버스 (KOSPI/KOSDAQ/ALL)
 *   tf       — 타임프레임 (1m/3m/5m/15m/30m/90m/1h/4h/12h/1d/1wk/1mo/1y)
 *
 * 저장 후:
 *   onSaved(strategyId) 콜백 호출
 *   invalidateQueries(['sig-strategies','list']) — useCreateSigStrategy/useUpdateSigStrategy 내부
 *
 * 룰 5: LLM provider import 0건
 */
import { useState, useEffect, FormEvent } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useCreateSigStrategy, useUpdateSigStrategy } from '../../api/hooks/useSigStrategy'
import type { PartialExitDto, ScaleInDto, ViActionPolicy } from '../../api/strategy'
import { InstrumentSearchInput } from '../InstrumentSearchInput'
import { RiskPlanPreviewPanel } from './RiskPlanPreviewPanel'
import { ScaleInExitPanel } from './ScaleInExitPanel'
import {
  SCALE_IN_LIMITS,
  SCALE_IN_NOT_ENABLED_NOTICE,
  isScaleInNotEnabledError,
  validatePartialExits,
  validateScaleIn,
} from './scaleInExitDisplay'

const DEFAULT_SCALE_IN: ScaleInDto = {
  totalSlices: 3,
  sliceWeights: null,
  reentryTrigger: 'SIGNAL_REPEAT',
  priceDropPct: null,
  minSliceIntervalSec: SCALE_IN_LIMITS.DEFAULT_MIN_SLICE_INTERVAL_SEC,
}

interface StrategySaveModalProps {
  /** 기존 전략 ID — 있으면 수정, 없으면 신규 생성 */
  strategyId?: string
  /** 소유자 사용자 ID */
  userId: number | null
  /** 매수 조건 DSL 텍스트 */
  entry: string
  /** 매도 조건 DSL 텍스트 (optional) */
  exit?: string
  initialName?: string
  initialNameKo?: string
  initialUniverse?: string
  initialTf?: string
  initialCode?: string
  initialCodes?: string[]
  initialCodeNames?: Record<string, string>
  initialStopLossPct?: number | string | null
  initialTakeProfitPct?: number | string | null
  /** 기존 전략의 최소 보유 캔들(잔익절 방어 B안) */
  initialMinHoldBars?: number | null
  /** 기존 전략의 VI 행동 정책 */
  initialViActionPolicy?: ViActionPolicy | null
  /** 기존 전략의 분할 매수 설정 (수정 모드) */
  initialScaleIn?: ScaleInDto | null
  /** 기존 전략의 분할 익절 단계 (수정 모드) */
  initialPartialExits?: PartialExitDto[] | null
  /** 저장 완료 콜백 — strategyId 반환 */
  onSaved: (strategyId: string) => void
  /** 모달 닫기 */
  onClose: () => void
}

const UNIVERSE_OPTIONS = [
  { value: 'ALL', label: '전체 (ALL)' },
  { value: 'KOSPI', label: 'KOSPI' },
  { value: 'KOSDAQ', label: 'KOSDAQ' },
  { value: 'KOSPI200', label: 'KOSPI200' },
]

const TF_OPTIONS = [
  { value: '1m', label: '1분봉' },
  { value: '3m', label: '3분봉' },
  { value: '5m', label: '5분봉' },
  { value: '15m', label: '15분봉' },
  { value: '30m', label: '30분봉' },
  { value: '90m', label: '90분봉' },
  { value: '1h', label: '1시간봉' },
  { value: '4h', label: '4시간봉' },
  { value: '12h', label: '정규장 전체' },
  { value: '1d', label: '일봉' },
  { value: '1wk', label: '주봉' },
  { value: '1mo', label: '월봉' },
  { value: '1y', label: '년봉' },
]

const VI_POLICY_OPTIONS: Array<{ value: ViActionPolicy; label: string; hint: string }> = [
  { value: 'ALERT_ONLY', label: '알림만', hint: 'VI 감지 이벤트만 남기고 주문하지 않습니다.' },
  { value: 'EXIT_MARKET', label: '시장가 청산', hint: '보유 중 VI가 감지되면 시장가 매도합니다.' },
  { value: 'HOLD', label: '보유 유지', hint: 'VI 감시 구독과 주문을 모두 하지 않습니다.' },
]

const STRATEGY_NAME_PATTERN = /^[\p{L}\p{N}_ [\]()·-]+$/u

function defaultViActionPolicy(
  name?: string,
  nameKo?: string,
): ViActionPolicy {
  const text = `${name ?? ''} ${nameKo ?? ''}`.toUpperCase()
  if (text.includes('DAYTRADE') || text.includes('SCALP')) return 'EXIT_MARKET'
  if (text.includes('SWING') || text.includes('POSITION')) return 'HOLD'
  return 'ALERT_ONLY'
}

function extractInstrumentCode(value: string): string | null {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : null
}

function normalizeTargetCodes(codes: Array<string | null | undefined>): string[] {
  return codes
    .map((code) => code?.trim() ?? '')
    .filter((code): code is string => /^\d{6}$/.test(code))
    .filter((code, index, arr) => arr.indexOf(code) === index)
}

export function StrategySaveModal({
  strategyId,
  userId,
  entry,
  exit,
  initialName,
  initialNameKo,
  initialUniverse,
  initialTf,
  initialCode,
  initialCodes,
  initialCodeNames,
  initialStopLossPct,
  initialTakeProfitPct,
  initialMinHoldBars,
  initialViActionPolicy,
  initialScaleIn,
  initialPartialExits,
  onSaved,
  onClose,
}: StrategySaveModalProps) {
  const initialTargetCodes = normalizeTargetCodes(initialCodes?.length ? initialCodes : [initialCode])
  const [name, setName] = useState(initialName ?? '')
  const [nameKo, setNameKo] = useState(initialNameKo ?? '')
  const [universe, setUniverse] = useState(initialUniverse ?? 'KOSPI')
  const [tf, setTf] = useState(initialTf ?? '1d')
  const [targetCodeInput, setTargetCodeInput] = useState('')
  const [targetCodes, setTargetCodes] = useState<string[]>(initialTargetCodes)
  const [targetCodeNames, setTargetCodeNames] = useState<Record<string, string>>(initialCodeNames ?? {})
  const [stopLossPct, setStopLossPct] = useState(initialStopLossPct?.toString() ?? '5')
  const [takeProfitPct, setTakeProfitPct] = useState(initialTakeProfitPct?.toString() ?? '10')
  // 잔익절 방어 B안 — 최소 보유 캔들(빈 문자열·0 = 비활성 현행)
  const [minHoldBars, setMinHoldBars] = useState(initialMinHoldBars?.toString() ?? '')
  const [viActionPolicy, setViActionPolicy] = useState<ViActionPolicy>(
    initialViActionPolicy ?? defaultViActionPolicy(initialName, initialNameKo),
  )
  const [previewEntryPrice, setPreviewEntryPrice] = useState('100000')
  const [formError, setFormError] = useState<string | null>(null)
  const [formNotice, setFormNotice] = useState<string | null>(null)

  // ── 분할 매수 / 분할 익절 (ADR 0111 · 분할매수 R3-a) ──
  const [scaleInEnabled, setScaleInEnabled] = useState<boolean>(!!initialScaleIn)
  const [scaleIn, setScaleIn] = useState<ScaleInDto>(initialScaleIn ?? DEFAULT_SCALE_IN)
  const [manualWeights, setManualWeights] = useState<boolean>(!!initialScaleIn?.sliceWeights)
  const [partialExitsEnabled, setPartialExitsEnabled] = useState<boolean>(
    !!initialPartialExits && initialPartialExits.length > 0,
  )
  const [partialExits, setPartialExits] = useState<PartialExitDto[]>(initialPartialExits ?? [])

  // ESC 닫기 + body scroll lock (공용 훅 — 저장 form 입력 중에도 ESC 닫기, IME 조합 중 무시)
  useModalBehavior({ open: true, onClose })

  const createMutation = useCreateSigStrategy()
  const updateMutation = useUpdateSigStrategy()
  const isPending = createMutation.isPending || updateMutation.isPending
  const mutationError = createMutation.error ?? updateMutation.error
  const isError = createMutation.isError || updateMutation.isError

  useEffect(() => {
    if (isError && mutationError) {
      setFormError(`저장 실패: ${mutationError.message}`)
    }
  }, [isError, mutationError])

  useEffect(() => {
    setName(initialName ?? '')
    setNameKo(initialNameKo ?? '')
    setUniverse(initialUniverse ?? 'KOSPI')
    setTf(initialTf ?? '1d')
    setTargetCodeInput('')
    setTargetCodes(normalizeTargetCodes(initialCodes?.length ? initialCodes : [initialCode]))
    setTargetCodeNames(initialCodeNames ?? {})
    setStopLossPct(initialStopLossPct?.toString() ?? '5')
    setTakeProfitPct(initialTakeProfitPct?.toString() ?? '10')
    setMinHoldBars(initialMinHoldBars?.toString() ?? '')
    setViActionPolicy(initialViActionPolicy ?? defaultViActionPolicy(initialName, initialNameKo))
    setScaleInEnabled(!!initialScaleIn)
    setScaleIn(initialScaleIn ?? DEFAULT_SCALE_IN)
    setManualWeights(!!initialScaleIn?.sliceWeights)
    setPartialExitsEnabled(!!initialPartialExits && initialPartialExits.length > 0)
    setPartialExits(initialPartialExits ?? [])
  }, [
    strategyId,
    initialName,
    initialNameKo,
    initialUniverse,
    initialTf,
    initialCode,
    initialCodes,
    initialCodeNames,
    initialStopLossPct,
    initialTakeProfitPct,
    initialMinHoldBars,
    initialViActionPolicy,
    initialScaleIn,
    initialPartialExits,
  ])

  function addTargetCode(rawValue: string, name?: string) {
    const code = extractInstrumentCode(rawValue)
    if (!code) {
      setFormError('검색 결과를 선택하거나 6자리 종목코드를 추가하세요')
      return
    }
    setTargetCodes((prev) => (prev.includes(code) ? prev : [...prev, code]))
    if (name?.trim()) {
      setTargetCodeNames((prev) => ({ ...prev, [code]: name.trim() }))
    }
    setTargetCodeInput('')
    setFormError(null)
  }

  function removeTargetCode(code: string) {
    setTargetCodes((prev) => prev.filter((item) => item !== code))
  }

  function targetCodeLabel(code: string): string {
    const name = targetCodeNames[code]?.trim()
    return name ? `${name} (${code})` : code
  }

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    setFormError(null)
    setFormNotice(null)

    if (userId === null) {
      setFormError('계좌 등록 후 전략을 저장할 수 있습니다')
      return
    }
    if (!name.trim()) {
      setFormError('전략 식별자를 입력하세요')
      return
    }
    if (!STRATEGY_NAME_PATTERN.test(name.trim())) {
      setFormError('전략 식별자는 한글·영문·숫자와 공백, _, -, [], (), · 조합만 허용됩니다')
      return
    }
    if (!entry.trim()) {
      setFormError('매수 조건(Entry)이 없습니다')
      return
    }
    const pendingCode = targetCodeInput.trim() ? extractInstrumentCode(targetCodeInput) : null
    if (targetCodeInput.trim() && !pendingCode) {
      setFormError('입력 중인 종목은 검색 결과를 선택하거나 6자리 코드로 추가하세요')
      return
    }
    const normalizedCodes = normalizeTargetCodes([...targetCodes, pendingCode])
    if (normalizedCodes.length === 0) {
      setFormError('자동매매 대상 종목을 선택하세요')
      return
    }
    const parsedStopLossPct = Number(stopLossPct.trim())
    if (!Number.isFinite(parsedStopLossPct) || parsedStopLossPct <= 0) {
      setFormError('손절 기준은 0보다 큰 숫자로 입력하세요')
      return
    }
    const parsedTakeProfitPct = Number(takeProfitPct.trim())
    if (!Number.isFinite(parsedTakeProfitPct) || parsedTakeProfitPct <= 0) {
      setFormError('익절 기준은 0보다 큰 숫자로 입력하세요')
      return
    }

    // 잔익절 방어 B안 — minHoldBars(빈 문자열=비활성). 입력 시 0 이상 정수.
    const minHoldRaw = minHoldBars.trim()
    let minHoldBarsPayload: number | null = null
    if (minHoldRaw) {
      const parsed = Number(minHoldRaw)
      if (!Number.isInteger(parsed) || parsed < 0) {
        setFormError('최소 보유 캔들은 0 이상 정수로 입력하세요')
        return
      }
      minHoldBarsPayload = parsed > 0 ? parsed : null
    }

    // ── 분할 매수 / 분할 익절 검증 + payload 구성 (ADR 0111) ──
    let scaleInPayload: ScaleInDto | null = null
    if (scaleInEnabled) {
      const effectiveScaleIn: ScaleInDto = {
        ...scaleIn,
        sliceWeights: manualWeights ? scaleIn.sliceWeights ?? null : null,
        priceDropPct: scaleIn.reentryTrigger === 'PRICE_DROP_PCT' ? scaleIn.priceDropPct ?? null : null,
      }
      const v = validateScaleIn({
        totalSlices: effectiveScaleIn.totalSlices,
        sliceWeights: effectiveScaleIn.sliceWeights,
        reentryTrigger: effectiveScaleIn.reentryTrigger,
        priceDropPct: effectiveScaleIn.priceDropPct,
        minSliceIntervalSec: effectiveScaleIn.minSliceIntervalSec,
      })
      if (!v.ok) {
        setFormError(v.error ?? '분할 매수 설정이 올바르지 않습니다')
        return
      }
      scaleInPayload = effectiveScaleIn
    }

    let partialExitsPayload: PartialExitDto[] | null = null
    if (partialExitsEnabled && partialExits.length > 0) {
      const v = validatePartialExits(partialExits)
      if (!v.ok) {
        setFormError(v.error ?? '분할 익절 설정이 올바르지 않습니다')
        return
      }
      partialExitsPayload = partialExits
    }

    const payload = {
      userId,
      name: name.trim(),
      nameKo: nameKo.trim() || undefined,
      entry: entry.trim(),
      exit: exit?.trim() || undefined,
      universe,
      tf,
      stopLossPct: parsedStopLossPct,
      takeProfitPct: parsedTakeProfitPct,
      minHoldBars: minHoldBarsPayload,
      viActionPolicy,
      codes: normalizedCodes,
      intervals: [tf],
      scaleIn: scaleInPayload,
      partialExits: partialExitsPayload,
    }
    const callbacks = {
      onSuccess: (resp: { id: string; warnings?: string[] }) => {
        // 잔익절 방어 A안 — 서버 저장 경고가 있으면 모달을 닫지 않고 표시(사용자 확인). 없으면 즉시 닫기.
        onSaved(resp.id)
        if (resp.warnings && resp.warnings.length > 0) {
          setFormNotice(resp.warnings.join('\n'))
          setFormError(null)
          return
        }
        onClose()
      },
      onError: (err: Error) => {
        // 활성화 게이트 graceful — 저장은 됐으나 ACTIVE 전이가 막힌 경우 안내(DRAFT 저장 가능)
        if (isScaleInNotEnabledError(err.message)) {
          setFormNotice(SCALE_IN_NOT_ENABLED_NOTICE)
          setFormError(null)
          return
        }
        setFormError(`저장 실패: ${err.message}`)
      },
    }

    if (strategyId) {
      updateMutation.mutate(
        {
          id: strategyId,
          req: payload,
        },
        callbacks,
      )
    } else {
      createMutation.mutate(payload, callbacks)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      role="dialog"
      aria-modal="true"
      aria-label="전략 저장"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="max-h-[92vh] w-full max-w-lg overflow-y-auto rounded-xl border border-gray-700 bg-gray-900 p-6 shadow-2xl">
        {/* 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">{strategyId ? '전략 수정' : '전략 저장'}</h2>
          <button
            className="rounded p-1 text-gray-500 hover:text-gray-300 focus:outline-none"
            onClick={onClose}
            aria-label="모달 닫기"
          >
            ×
          </button>
        </div>

        {/* DSL 미리보기 */}
        <div className="mb-4 rounded-lg bg-gray-950 p-3">
          <div className="mb-1 text-xs text-gray-500">매수 조건 (Entry)</div>
          <code className="block truncate text-xs text-blue-300">{entry || '(없음)'}</code>
          {exit && (
            <>
              <div className="mb-1 mt-2 text-xs text-gray-500">매도 조건 (Exit)</div>
              <code className="block truncate text-xs text-orange-300">{exit}</code>
            </>
          )}
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          {/* name */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              전략 식별자 <span className="text-red-400">*</span>
              <span className="ml-1 font-normal text-gray-600">(한글·영문·숫자·공백·_ 가능)</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: [페이퍼] 장전 갭상승·RVOL·거래대금 setup"
              className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              autoFocus
            />
          </div>

          {/* nameKo */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              한국어 이름
              <span className="ml-1 font-normal text-gray-600">(optional)</span>
            </label>
            <input
              type="text"
              value={nameKo}
              onChange={(e) => setNameKo(e.target.value)}
              placeholder="예: RSI 골든크로스 전략"
              className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>

          {/* universe + tf */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-400">유니버스</label>
              <select
                value={universe}
                onChange={(e) => setUniverse(e.target.value)}
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {UNIVERSE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-400">타임프레임</label>
              <select
                value={tf}
                onChange={(e) => setTf(e.target.value)}
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {TF_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* target code */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              자동매매 대상 종목 <span className="text-red-400">*</span>
              <span className="ml-1 font-normal text-gray-600">({targetCodes.length}개)</span>
            </label>
            <span className="mb-1 block text-xs text-gray-600">
              전략 목록과 자동매매는 이 종목 매핑 전체를 기준으로 평가됩니다.
            </span>
            <div className="flex gap-2">
              <div className="min-w-0 flex-1">
                <InstrumentSearchInput
                  value={targetCodeInput}
                  onRawChange={(value) => setTargetCodeInput(value)}
                  onSelect={(result) => addTargetCode(result.code, result.name)}
                  placeholder="종목명 또는 코드 검색"
                />
              </div>
              <button
                type="button"
                onClick={() => addTargetCode(targetCodeInput)}
                className="h-[42px] shrink-0 rounded-lg border border-gray-700 bg-gray-800 px-3 text-sm font-medium text-gray-200 hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
              >
                추가
              </button>
            </div>
            {targetCodes.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {targetCodes.map((code) => (
                  <span
                    key={code}
                    className="inline-flex max-w-full items-center gap-1 rounded-md border border-gray-700 bg-gray-800 px-2 py-1 text-xs text-gray-200"
                    title={targetCodeLabel(code)}
                  >
                    <span className="max-w-[14rem] truncate">{targetCodeLabel(code)}</span>
                    <button
                      type="button"
                      onClick={() => removeTargetCode(code)}
                      className="rounded px-1 text-gray-500 hover:bg-gray-700 hover:text-gray-200 focus:outline-none focus:ring-1 focus:ring-blue-400"
                      aria-label={`${targetCodeLabel(code)} 제거`}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>

          {/* mandatory risk plan */}
          <div className="rounded-lg border border-amber-900/60 bg-amber-950/20 p-3">
            <div className="mb-2 text-xs font-semibold text-amber-200">자동매매 RiskPlan</div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  손절 기준 <span className="text-red-400">*</span>
                  <span className="ml-1 font-normal text-gray-600">(%)</span>
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={stopLossPct}
                  onChange={(e) => setStopLossPct(e.target.value)}
                  placeholder="예: 5"
                  className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  익절 기준 <span className="text-red-400">*</span>
                  <span className="ml-1 font-normal text-gray-600">(%)</span>
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={takeProfitPct}
                  onChange={(e) => setTakeProfitPct(e.target.value)}
                  placeholder="예: 10"
                  className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>
            <div className="mt-3">
              <label className="mb-1 block text-xs font-medium text-gray-400">
                미리보기 기준가
                <span className="ml-1 font-normal text-gray-600">(저장 안 됨)</span>
              </label>
              <input
                type="number"
                min="0"
                step="1"
                value={previewEntryPrice}
                onChange={(e) => setPreviewEntryPrice(e.target.value)}
                placeholder="예: 100000"
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <RiskPlanPreviewPanel
              className="mt-3"
              title="저장 전 손절·익절 미리보기"
              stopLossPct={stopLossPct}
              takeProfitPct={takeProfitPct}
              entryPrice={previewEntryPrice}
            />
            {/* 잔익절 방어 A안 — 익절 목표가 왕복 거래비용(≈0.2%) 근접·미만이면 사전 경고(비차단) */}
            {Number.isFinite(Number(takeProfitPct.trim())) &&
              Number(takeProfitPct.trim()) > 0 &&
              Number(takeProfitPct.trim()) <= 0.3 && (
                <div
                  className="mt-3 rounded bg-amber-900/30 px-3 py-2 text-xs leading-5 text-amber-200"
                  role="status"
                >
                  익절 목표 {takeProfitPct.trim()}%는 왕복 거래비용(수수료+세금 약 0.2%)에 근접·미만입니다. 수수료·세금
                  차감 후 실현 손익이 음수가 될 수 있어요(잔익절). 익절 목표를 높이거나 아래 최소 보유 캔들로 노이즈 매도를
                  방어하세요.
                </div>
              )}
            {/* 잔익절 방어 B안 — 최소 보유 캔들(minHoldBars) */}
            <div className="mt-3">
              <label className="mb-1 block text-xs font-medium text-gray-400">
                최소 보유 캔들
                <span className="ml-1 font-normal text-gray-600">(잔익절 방어 · 0/빈칸=비활성)</span>
              </label>
              <input
                type="number"
                min="0"
                step="1"
                value={minHoldBars}
                onChange={(e) => setMinHoldBars(e.target.value)}
                placeholder="예: 3"
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <p className="mt-1 text-xs leading-5 text-gray-500">
                진입 체결 후 이 캔들 수만큼은 차트(DSL) 매도 시그널을 무시합니다. 손절·익절 라인은 항상 작동합니다.
              </p>
            </div>
            <p className="mt-2 text-xs leading-5 text-amber-100/70">
              자동매매는 이 기준이 모두 있어야 시작됩니다. 실제 주문 감시는 BUY 체결가 기준으로 계산됩니다.
            </p>
            <div className="mt-3 rounded-md border border-red-900/50 bg-red-950/20 px-3 py-2">
              <label className="mb-1 block text-xs font-semibold text-red-200">VI 발동 정책</label>
              <select
                value={viActionPolicy}
                onChange={(e) => setViActionPolicy(e.target.value as ViActionPolicy)}
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white focus:border-red-400 focus:outline-none focus:ring-1 focus:ring-red-400"
              >
                {VI_POLICY_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs leading-5 text-red-100/75">
                {VI_POLICY_OPTIONS.find((option) => option.value === viActionPolicy)?.hint}
              </p>
            </div>
          </div>

          {/* 분할 매수 / 분할 익절 (ADR 0111 · 분할매수 R3-a · add-only) */}
          <ScaleInExitPanel
            scaleInEnabled={scaleInEnabled}
            onScaleInEnabledChange={setScaleInEnabled}
            scaleIn={scaleIn}
            onScaleInChange={setScaleIn}
            manualWeights={manualWeights}
            onManualWeightsChange={setManualWeights}
            partialExitsEnabled={partialExitsEnabled}
            onPartialExitsEnabledChange={setPartialExitsEnabled}
            partialExits={partialExits}
            onPartialExitsChange={setPartialExits}
          />

          {/* 활성화 게이트 안내 (SCALE_IN_NOT_ENABLED — 저장 가능, ACTIVE만 차단) */}
          {formNotice && (
            <div className="whitespace-pre-line rounded bg-sky-950/40 px-3 py-2 text-sm text-sky-200" role="status">
              {formNotice}
            </div>
          )}

          {/* 에러 */}
          {formError && (
            <div className="rounded bg-red-900/20 px-3 py-2 text-sm text-red-400" role="alert">
              {formError}
            </div>
          )}

          {/* 버튼 */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-400 hover:text-gray-200 focus:outline-none"
              onClick={onClose}
            >
              취소
            </button>
            <button
              type="submit"
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
              disabled={isPending}
            >
              {isPending ? '저장 중...' : strategyId ? '수정' : '저장'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
