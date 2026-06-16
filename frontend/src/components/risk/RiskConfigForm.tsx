/**
 * RiskConfigForm — 리스크 설정 폼 (T-RISK Phase 3 — ADR 0067)
 *
 * 섹션:
 *   A 포지션 한도 (절대 한도 + 비율 한도)
 *   B DLL (활성 토글 + 절대값 OR 비율, autoPauseThreshold)
 *   C 동시 보유 종목 한도 slider (1~20, default 5)
 *   D 거래 빈도 dual slider (분당 default 3, 일별 default 50)
 *   reset 버튼 (default seed)
 *
 * [hotfix] backend flat DTO 정합 — nested 가정(positionLimit·dll·maxHolding·tradeFrequency) 제거.
 * 사용자 메모리 정합: no-mock-data → 빈 config 시 default seed 유도
 */
import { useEffect, useState } from 'react'
import {
  useRiskConfig,
  useUpdateRiskConfig,
  useResetRiskConfig,
} from '../../api/hooks/useRiskConfig'
import type { RiskConfigUpdateRequest } from '../../api/risk'
import { useToast } from '../ui/ToastProvider'

// ──────────────────────────────────────────────
// 유틸
// ──────────────────────────────────────────────

function parseKrw(str: string): number {
  const n = Number(str.replace(/[^0-9.]/g, ''))
  return isNaN(n) ? 0 : n
}

// ──────────────────────────────────────────────
// 로컬 폼 상태 — backend flat 키와 1:1 대응
// ──────────────────────────────────────────────

interface FormState {
  maxPositionAmount: number
  maxPositionPercent: number       // 퍼센트 단위 (10 = 10%)
  dllAbs: number                   // UI에서 null 대신 0으로 표현 (0 = 미설정)
  dllPct: number                   // 퍼센트 단위 (3 = 3%)
  dllMode: 'PERCENT' | 'AMOUNT'
  isDailyLossLimitActive: boolean
  maxConcurrentPositions: number
  maxTradesPerMinute: number
  maxTradesPerDay: number
  autoPauseThreshold: number
}

// ──────────────────────────────────────────────
// NumberInput — 천 단위 포맷 입력 필드
// ──────────────────────────────────────────────

interface NumberInputProps {
  label: string
  value: number
  onChange: (v: number) => void
  min?: number
  max?: number
  suffix?: string
  hint?: string
  disabled?: boolean
}

function NumberInput({
  label,
  value,
  onChange,
  min,
  max,
  suffix = '',
  hint,
  disabled,
}: NumberInputProps) {
  const [raw, setRaw] = useState(String(value))

  useEffect(() => {
    setRaw(value.toLocaleString('ko-KR'))
  }, [value])

  return (
    <div>
      <label className="block text-xs text-gray-500">{label}</label>
      <div className="mt-1 flex items-center gap-1">
        <input
          type="text"
          aria-label={label}
          value={raw}
          disabled={disabled}
          onChange={(e) => {
            const v = e.target.value
            setRaw(v)
            const n = parseKrw(v)
            if (!isNaN(n)) {
              const clamped =
                min !== undefined && n < min ? min : max !== undefined && n > max ? max : n
              onChange(clamped)
            }
          }}
          onBlur={() => {
            setRaw(value.toLocaleString('ko-KR'))
          }}
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none disabled:opacity-50"
        />
        {suffix && <span className="shrink-0 text-xs text-gray-500">{suffix}</span>}
      </div>
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

// ──────────────────────────────────────────────
// SliderInput
// ──────────────────────────────────────────────

interface SliderInputProps {
  label: string
  value: number
  onChange: (v: number) => void
  min: number
  max: number
  step?: number
  unit?: string
  hint?: string
  disabled?: boolean
}

function SliderInput({
  label,
  value,
  onChange,
  min,
  max,
  step = 1,
  unit = '',
  hint,
  disabled,
}: SliderInputProps) {
  return (
    <div>
      <div className="flex items-center justify-between">
        <label className="text-xs text-gray-500">{label}</label>
        <span className="text-sm font-semibold text-white tabular-nums">
          {value.toLocaleString('ko-KR')}
          {unit}
        </span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-2 w-full accent-blue-500 disabled:opacity-50"
        aria-label={label}
      />
      <div className="flex justify-between text-xs text-gray-600 mt-1">
        <span>
          {min}
          {unit}
        </span>
        <span>
          {max}
          {unit}
        </span>
      </div>
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

// ──────────────────────────────────────────────
// 섹션 헤더
// ──────────────────────────────────────────────

function SectionHeader({ label, desc }: { label: string; desc?: string }) {
  return (
    <div className="mb-3">
      <h3 className="text-sm font-semibold text-gray-200">{label}</h3>
      {desc && <p className="mt-0.5 text-xs text-gray-500">{desc}</p>}
    </div>
  )
}

// ──────────────────────────────────────────────
// RiskConfigForm 본체
// ──────────────────────────────────────────────

interface RiskConfigFormProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /**
   * 설정 저장/초기화 성공 직후 호출.
   * 사용량 게이지의 분모(한도)를 새 값으로 즉시 갱신하기 위해 페이지가 주입한다.
   * 미주입(undefined)이면 기존 동작과 동일 — 레이아웃·기능 회귀 0.
   */
  onSaved?: () => void
}

export function RiskConfigForm({ userId, onSaved }: RiskConfigFormProps) {
  const { data, isLoading, isError } = useRiskConfig(userId)
  const updateMutation = useUpdateRiskConfig(userId)
  const resetMutation = useResetRiskConfig(userId)
  const { showToast } = useToast()

  const [form, setForm] = useState<FormState | null>(null)
  const [isDirty, setIsDirty] = useState(false)

  // 서버 데이터 → 폼 초기화 (flat 키 직접 매핑)
  useEffect(() => {
    if (!data) return
    setForm({
      maxPositionAmount: data.maxPositionAmount,
      maxPositionPercent: data.maxPositionPercent,      // backend가 이미 퍼센트 단위
      dllAbs: data.dllAbs ?? 0,                         // null → 0 (0 = 미설정)
      dllPct: data.dllPct ?? 3,                         // backend가 이미 퍼센트 단위
      dllMode: data.dllAbs !== null && data.dllAbs !== undefined ? 'AMOUNT' : 'PERCENT',
      isDailyLossLimitActive: data.isDailyLossLimitActive,
      maxConcurrentPositions: data.maxConcurrentPositions,
      maxTradesPerMinute: data.maxTradesPerMinute,
      maxTradesPerDay: data.maxTradesPerDay,
      autoPauseThreshold: data.autoPauseThreshold,
    })
    setIsDirty(false)
  }, [data])

  function patch(partial: Partial<FormState>) {
    setForm((prev) => (prev ? { ...prev, ...partial } : prev))
    setIsDirty(true)
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!form) return
    if (form.isDailyLossLimitActive && form.dllMode === 'AMOUNT' && form.dllAbs <= 0) {
      showToast('절대 DLL 금액을 0보다 크게 입력해 주세요.', 'error')
      return
    }

    const req: RiskConfigUpdateRequest = {
      maxPositionAmount: form.maxPositionAmount,
      maxPositionPercent: form.maxPositionPercent,
      isDailyLossLimitActive: form.isDailyLossLimitActive,
      dllAbs: form.isDailyLossLimitActive && form.dllMode === 'AMOUNT' ? form.dllAbs : null,
      dllPct: form.isDailyLossLimitActive && form.dllMode === 'PERCENT' ? form.dllPct : null,
      maxConcurrentPositions: form.maxConcurrentPositions,
      maxTradesPerMinute: form.maxTradesPerMinute,
      maxTradesPerDay: form.maxTradesPerDay,
      autoPauseThreshold: form.autoPauseThreshold,
    }

    try {
      await updateMutation.mutateAsync(req)
      showToast('리스크 설정이 저장되었습니다.', 'success')
      setIsDirty(false)
      onSaved?.()
    } catch {
      showToast('리스크 설정 저장에 실패했습니다. 다시 시도해 주세요.', 'error')
    }
  }

  async function handleReset() {
    if (!window.confirm('기본값으로 초기화합니다. 계속하시겠습니까?')) return
    try {
      await resetMutation.mutateAsync()
      showToast('기본값으로 초기화되었습니다.', 'info')
      onSaved?.()
    } catch {
      showToast('초기화에 실패했습니다. 다시 시도해 주세요.', 'error')
    }
  }

  // 로딩
  if (isLoading) {
    return (
      <div className="space-y-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-24 animate-pulse rounded-xl bg-gray-800" />
        ))}
      </div>
    )
  }

  // 오류
  if (isError) {
    return (
      <div className="rounded-xl border border-red-800 bg-red-900/20 p-6 text-center text-sm text-red-400">
        리스크 설정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
      </div>
    )
  }

  if (!form) return null

  const isPending = updateMutation.isPending || resetMutation.isPending

  return (
    <form onSubmit={(e) => void handleSubmit(e)} className="space-y-6">
      {/* A 포지션 한도 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
        <SectionHeader
          label="A. 포지션 한도"
          desc="종목당 최대 투자금액. 절대 한도와 비율 한도 중 작은 값이 적용됩니다."
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <NumberInput
            label="절대 한도 (원)"
            value={form.maxPositionAmount}
            onChange={(v) => patch({ maxPositionAmount: v })}
            min={0}
            suffix="원"
            hint="0 = 무제한"
          />
          <SliderInput
            label="비율 한도 (%)"
            value={form.maxPositionPercent}
            onChange={(v) => patch({ maxPositionPercent: v })}
            min={1}
            max={100}
            unit="%"
            hint="총 자산 대비 비율 (1~100%)"
          />
        </div>
      </div>

      {/* B 일일 손실 한도 (DLL) */}
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
        <SectionHeader
          label="B. 일일 손실 한도 (DLL)"
          desc="하루 최대 허용 손실. 초과 시 신규 BUY가 차단됩니다."
        />
        <div className="space-y-4">
          {/* DLL 활성 토글 */}
          <div className="flex items-center justify-between">
            <span className="text-xs text-gray-500">DLL 활성화</span>
            <button
              type="button"
              onClick={() => patch({ isDailyLossLimitActive: !form.isDailyLossLimitActive })}
              className={[
                'relative inline-flex h-5 w-9 items-center rounded-full transition-colors',
                form.isDailyLossLimitActive ? 'bg-blue-600' : 'bg-gray-700',
              ].join(' ')}
              aria-checked={form.isDailyLossLimitActive}
              role="switch"
            >
              <span
                className={[
                  'inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform',
                  form.isDailyLossLimitActive ? 'translate-x-4' : 'translate-x-1',
                ].join(' ')}
              />
            </button>
          </div>

          <div className="flex w-fit rounded-lg bg-gray-800 p-1">
            {[
              { value: 'PERCENT', label: '비율 기준' },
              { value: 'AMOUNT', label: '금액 기준' },
            ].map((option) => (
              <button
                key={option.value}
                type="button"
                disabled={!form.isDailyLossLimitActive}
                onClick={() => patch({ dllMode: option.value as FormState['dllMode'] })}
                className={[
                  'rounded-md px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
                  form.dllMode === option.value
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-400 hover:bg-gray-700 hover:text-gray-200',
                ].join(' ')}
              >
                {option.label}
              </button>
            ))}
          </div>

          <NumberInput
            label="절대 DLL (원)"
            value={form.dllAbs}
            onChange={(v) => patch({ dllAbs: v })}
            min={1}
            suffix="원"
            hint="금액 기준 선택 시 적용"
            disabled={!form.isDailyLossLimitActive || form.dllMode !== 'AMOUNT'}
          />

          <SliderInput
            label="비율 DLL (%)"
            value={form.dllPct}
            onChange={(v) => patch({ dllPct: v })}
            min={1}
            max={100}
            unit="%"
            hint="비율 기준 선택 시 적용"
            disabled={!form.isDailyLossLimitActive || form.dllMode !== 'PERCENT'}
          />

          <SliderInput
            label="자동 매수차단 임계치 (연속 BLOCKED)"
            value={form.autoPauseThreshold}
            onChange={(v) => patch({ autoPauseThreshold: v })}
            min={1}
            max={50}
            unit="회"
            hint="연속 BLOCKED가 이 횟수에 도달하면 신규 BUY만 차단 (default: 10)"
          />
        </div>
      </div>

      {/* C 동시 보유 종목 한도 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
        <SectionHeader
          label="C. 동시 보유 종목 한도"
          desc="한 번에 보유할 수 있는 최대 종목 수입니다."
        />
        <SliderInput
          label="최대 보유 종목 수"
          value={form.maxConcurrentPositions}
          onChange={(v) => patch({ maxConcurrentPositions: v })}
          min={1}
          max={20}
          unit="종목"
          hint="default: 5종목"
        />
      </div>

      {/* D 거래 빈도 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
        <SectionHeader
          label="D. 거래 빈도 한도"
          desc="분당·일별 최대 주문 횟수를 제한합니다."
        />
        <div className="space-y-5">
          <SliderInput
            label="분당 최대 거래"
            value={form.maxTradesPerMinute}
            onChange={(v) => patch({ maxTradesPerMinute: v })}
            min={1}
            max={30}
            unit="회/분"
            hint="default: 3회/분"
          />
          <SliderInput
            label="일별 최대 거래"
            value={form.maxTradesPerDay}
            onChange={(v) => patch({ maxTradesPerDay: v })}
            min={1}
            max={200}
            unit="회/일"
            hint="default: 50회/일"
          />
        </div>
      </div>

      {/* 버튼 영역 */}
      <div className="flex items-center justify-between gap-3">
        <button
          type="button"
          onClick={() => void handleReset()}
          disabled={isPending}
          className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-400 hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50 transition-colors"
        >
          기본값으로 초기화
        </button>

        <div className="flex items-center gap-3">
          {isDirty && (
            <span className="text-xs text-amber-400">저장되지 않은 변경이 있습니다.</span>
          )}
          <button
            type="submit"
            disabled={isPending || !isDirty}
            className="rounded-lg bg-blue-600 px-6 py-2 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50 transition-colors"
          >
            {updateMutation.isPending ? '저장 중...' : '설정 저장'}
          </button>
        </div>
      </div>
    </form>
  )
}
