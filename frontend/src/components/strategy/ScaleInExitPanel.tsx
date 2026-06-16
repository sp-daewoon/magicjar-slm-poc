/**
 * ScaleInExitPanel — 분할 매수 / 분할 익절 입력 패널 (ADR 0111 · 분할매수 R3-a)
 *
 * 빌더 저장 모달에 add-only 로 얹는다. 미사용(토글 OFF)이 기본 — 현행 단일 진입 + 전량 익절 그대로(하위호환).
 *
 * 분할 매수:
 *   - 사용 토글
 *   - 분할 회수 N (1~10)
 *   - 회당 비중: 균등(default) / 수동(합=100 검증 + 시각 피드백)
 *   - 재진입 트리거: 시그널 재발화(default) / 가격 하락%(음수)
 *   - 회차 간 최소 간격(초, default 60)
 *
 * 분할 익절:
 *   - 단계 추가(≤5) — 단계별 수익률%·청산 비중%(합≤100 검증)
 *
 * 부모(StrategySaveModal)가 ScaleInDto·PartialExitDto[] state를 보유하고 검증한다. 본 패널은 controlled.
 *
 * 룰 5: LLM provider import 0건
 */
import type { PartialExitDto, ReentryTrigger, ScaleInDto } from '../../api/strategy'
import {
  EXIT_LIMITS,
  SCALE_IN_LIMITS,
  evenSliceWeights,
  sumWeights,
  validatePartialExits,
  validateScaleIn,
} from './scaleInExitDisplay'

interface ScaleInExitPanelProps {
  /** 분할 매수 사용 여부 */
  scaleInEnabled: boolean
  onScaleInEnabledChange: (enabled: boolean) => void
  scaleIn: ScaleInDto
  onScaleInChange: (next: ScaleInDto) => void
  /** 수동 비중 사용 여부 (false면 균등) */
  manualWeights: boolean
  onManualWeightsChange: (manual: boolean) => void

  /** 분할 익절 사용 여부 */
  partialExitsEnabled: boolean
  onPartialExitsEnabledChange: (enabled: boolean) => void
  partialExits: PartialExitDto[]
  onPartialExitsChange: (next: PartialExitDto[]) => void

  className?: string
}

const inputCls =
  'w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'

export function ScaleInExitPanel({
  scaleInEnabled,
  onScaleInEnabledChange,
  scaleIn,
  onScaleInChange,
  manualWeights,
  onManualWeightsChange,
  partialExitsEnabled,
  onPartialExitsEnabledChange,
  partialExits,
  onPartialExitsChange,
  className = '',
}: ScaleInExitPanelProps) {
  const weights = scaleIn.sliceWeights ?? evenSliceWeights(scaleIn.totalSlices)
  const weightSum = sumWeights(weights)
  const scaleInValidation = scaleInEnabled
    ? validateScaleIn({
        totalSlices: scaleIn.totalSlices,
        sliceWeights: manualWeights ? scaleIn.sliceWeights : null,
        reentryTrigger: scaleIn.reentryTrigger,
        priceDropPct: scaleIn.priceDropPct,
        minSliceIntervalSec: scaleIn.minSliceIntervalSec,
      })
    : { ok: true }
  const partialValidation = partialExitsEnabled ? validatePartialExits(partialExits) : { ok: true }

  function handleTotalSlicesChange(next: number) {
    const clamped = Math.max(SCALE_IN_LIMITS.MIN_SLICES, Math.min(SCALE_IN_LIMITS.MAX_SLICES, next || 1))
    // 수동 비중이면 길이를 회수에 맞춰 재조정(추가분은 균등값으로 채움)
    const nextWeights = manualWeights
      ? Array.from({ length: clamped }, (_, i) => scaleIn.sliceWeights?.[i] ?? evenSliceWeights(clamped)[i])
      : null
    onScaleInChange({ ...scaleIn, totalSlices: clamped, sliceWeights: nextWeights })
  }

  function handleManualWeightToggle(manual: boolean) {
    onManualWeightsChange(manual)
    onScaleInChange({ ...scaleIn, sliceWeights: manual ? evenSliceWeights(scaleIn.totalSlices) : null })
  }

  function handleWeightChange(index: number, value: string) {
    const base = scaleIn.sliceWeights ?? evenSliceWeights(scaleIn.totalSlices)
    const next = [...base]
    next[index] = value === '' ? 0 : Number(value)
    onScaleInChange({ ...scaleIn, sliceWeights: next })
  }

  function handleAddStage() {
    if (partialExits.length >= EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES) return
    onPartialExitsChange([...partialExits, { triggerProfitPct: 3, exitWeightPct: 50 }])
  }

  function handleStageChange(index: number, field: keyof PartialExitDto, value: string) {
    const next = partialExits.map((stage, i) =>
      i === index ? { ...stage, [field]: value === '' ? 0 : Number(value) } : stage,
    )
    onPartialExitsChange(next)
  }

  function handleRemoveStage(index: number) {
    onPartialExitsChange(partialExits.filter((_, i) => i !== index))
  }

  return (
    <div className={['space-y-4', className].filter(Boolean).join(' ')}>
      {/* ── 분할 매수 ── */}
      <section className="rounded-lg border border-indigo-900/60 bg-indigo-950/20 p-3" aria-label="분할 매수 설정">
        <label className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold text-indigo-200">분할 매수 (scale-in)</span>
          <input
            type="checkbox"
            checked={scaleInEnabled}
            onChange={(e) => onScaleInEnabledChange(e.target.checked)}
            className="h-4 w-4 rounded border-gray-600 bg-gray-900 text-indigo-500 focus:ring-indigo-400"
            aria-label="분할 매수 사용"
          />
        </label>

        {!scaleInEnabled ? (
          <p className="mt-2 text-xs leading-5 text-gray-500">
            미사용 시 1회 전량 진입(현행). 분할 진입과 회당 비중 조절이 필요하면 사용으로 전환하세요.
          </p>
        ) : (
          <div className="mt-3 space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  분할 회수 N <span className="text-gray-600">(1~10)</span>
                </label>
                <input
                  type="number"
                  min={SCALE_IN_LIMITS.MIN_SLICES}
                  max={SCALE_IN_LIMITS.MAX_SLICES}
                  step={1}
                  value={scaleIn.totalSlices}
                  onChange={(e) => handleTotalSlicesChange(Number(e.target.value))}
                  className={inputCls}
                  aria-label="분할 회수"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  회차 간 최소 간격 <span className="text-gray-600">(초)</span>
                </label>
                <input
                  type="number"
                  min={0}
                  step={1}
                  value={scaleIn.minSliceIntervalSec ?? SCALE_IN_LIMITS.DEFAULT_MIN_SLICE_INTERVAL_SEC}
                  onChange={(e) =>
                    onScaleInChange({ ...scaleIn, minSliceIntervalSec: e.target.value === '' ? null : Number(e.target.value) })
                  }
                  className={inputCls}
                  aria-label="회차 간 최소 간격"
                />
              </div>
            </div>

            {/* 회당 비중 */}
            <div>
              <div className="mb-1 flex items-center justify-between">
                <span className="text-xs font-medium text-gray-400">회당 비중%</span>
                <label className="flex items-center gap-1.5 text-xs text-gray-400">
                  <input
                    type="checkbox"
                    checked={manualWeights}
                    onChange={(e) => handleManualWeightToggle(e.target.checked)}
                    className="h-3.5 w-3.5 rounded border-gray-600 bg-gray-900 text-indigo-500 focus:ring-indigo-400"
                    aria-label="회당 비중 수동 입력"
                  />
                  수동 입력
                </label>
              </div>
              {manualWeights ? (
                <>
                  <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
                    {weights.map((w, i) => (
                      <input
                        key={i}
                        type="number"
                        min={0}
                        step={0.01}
                        value={w}
                        onChange={(e) => handleWeightChange(i, e.target.value)}
                        className={inputCls}
                        aria-label={`${i + 1}회차 비중`}
                      />
                    ))}
                  </div>
                  <p
                    className={`mt-1 text-xs ${
                      Math.abs(weightSum - SCALE_IN_LIMITS.WEIGHT_TOTAL) <= SCALE_IN_LIMITS.WEIGHT_SUM_TOLERANCE
                        ? 'text-emerald-300'
                        : 'text-red-300'
                    }`}
                    role="status"
                  >
                    비중 합 {Math.round(weightSum * 100) / 100} / 100
                  </p>
                </>
              ) : (
                <p className="text-xs text-gray-500">균등 분할 — {evenSliceWeights(scaleIn.totalSlices).join(' / ')}</p>
              )}
            </div>

            {/* 재진입 트리거 */}
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">재진입 트리거</label>
                <select
                  value={scaleIn.reentryTrigger ?? 'SIGNAL_REPEAT'}
                  onChange={(e) =>
                    onScaleInChange({ ...scaleIn, reentryTrigger: e.target.value as ReentryTrigger })
                  }
                  className={inputCls}
                  aria-label="재진입 트리거"
                >
                  <option value="SIGNAL_REPEAT">시그널 재발화</option>
                  <option value="PRICE_DROP_PCT">가격 하락 (averaging down)</option>
                </select>
              </div>
              {scaleIn.reentryTrigger === 'PRICE_DROP_PCT' && (
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-400">
                    하락폭% <span className="text-gray-600">(음수)</span>
                  </label>
                  <input
                    type="number"
                    step={0.1}
                    max={0}
                    value={scaleIn.priceDropPct ?? ''}
                    onChange={(e) =>
                      onScaleInChange({ ...scaleIn, priceDropPct: e.target.value === '' ? null : Number(e.target.value) })
                    }
                    placeholder="예: -3"
                    className={inputCls}
                    aria-label="하락폭"
                  />
                </div>
              )}
            </div>

            {!scaleInValidation.ok && (
              <p className="text-xs text-red-300" role="alert">
                {scaleInValidation.error}
              </p>
            )}
          </div>
        )}
      </section>

      {/* ── 분할 익절 ── */}
      <section className="rounded-lg border border-emerald-900/60 bg-emerald-950/20 p-3" aria-label="분할 익절 설정">
        <label className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold text-emerald-200">분할 익절 (partial take-profit)</span>
          <input
            type="checkbox"
            checked={partialExitsEnabled}
            onChange={(e) => onPartialExitsEnabledChange(e.target.checked)}
            className="h-4 w-4 rounded border-gray-600 bg-gray-900 text-emerald-500 focus:ring-emerald-400"
            aria-label="분할 익절 사용"
          />
        </label>

        {!partialExitsEnabled ? (
          <p className="mt-2 text-xs leading-5 text-gray-500">
            미사용 시 익절가 도달 시 전량 청산(현행). 단계적으로 나눠 익절하려면 사용으로 전환하세요.
          </p>
        ) : (
          <div className="mt-3 space-y-2">
            {partialExits.length === 0 && (
              <p className="text-xs text-gray-500">단계를 추가하세요 (최대 {EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES}개).</p>
            )}
            {partialExits.map((stage, i) => (
              <div key={i} className="flex items-end gap-2">
                <div className="flex-1">
                  <label className="mb-1 block text-[11px] text-gray-500">{i + 1}단계 수익률%</label>
                  <input
                    type="number"
                    min={0}
                    step={0.1}
                    value={stage.triggerProfitPct}
                    onChange={(e) => handleStageChange(i, 'triggerProfitPct', e.target.value)}
                    className={inputCls}
                    aria-label={`${i + 1}단계 수익률`}
                  />
                </div>
                <div className="flex-1">
                  <label className="mb-1 block text-[11px] text-gray-500">청산 비중%</label>
                  <input
                    type="number"
                    min={0}
                    max={100}
                    step={1}
                    value={stage.exitWeightPct}
                    onChange={(e) => handleStageChange(i, 'exitWeightPct', e.target.value)}
                    className={inputCls}
                    aria-label={`${i + 1}단계 청산 비중`}
                  />
                </div>
                <button
                  type="button"
                  onClick={() => handleRemoveStage(i)}
                  className="rounded-lg border border-gray-700 px-2 py-2 text-xs text-gray-400 hover:text-red-300 focus:outline-none"
                  aria-label={`${i + 1}단계 삭제`}
                >
                  ×
                </button>
              </div>
            ))}
            {partialExits.length < EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES && (
              <button
                type="button"
                onClick={handleAddStage}
                className="rounded-lg border border-emerald-800 bg-emerald-950/40 px-3 py-1.5 text-xs font-medium text-emerald-300 hover:bg-emerald-900/40 focus:outline-none focus:ring-2 focus:ring-emerald-400"
              >
                + 단계 추가
              </button>
            )}
            {!partialValidation.ok && (
              <p className="text-xs text-red-300" role="alert">
                {partialValidation.error}
              </p>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
