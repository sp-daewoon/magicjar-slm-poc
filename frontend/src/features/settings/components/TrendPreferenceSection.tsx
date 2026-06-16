/**
 * TrendPreferenceSection — SettingsModal "추세 분석 설정" 섹션 (T-MARKET R2 §2-1)
 *
 * 본 섹션:
 *   1. 프리셋 4 버튼 (classic·mid·long·custom)
 *   2. MA periods 입력 (1~5개·각 1~252·sorted ASC·중복 없음)
 *   3. rsPeriod 입력 (1차 readonly 60 — backend 요구치)
 *   4. crossPair 옵션 (off=auto · on=short/long 선택)
 *   5. 변경 시 PUT /api/users/me/preferences/trend → STOMP 다중 탭 sync
 *
 * Bean Validation 한국어 message는 backend 권위. frontend는 1차 pre-validation만.
 */
import { useEffect, useState } from 'react'
import {
  detectPresetId,
  TREND_PRESETS,
  useTrendPreference,
  type TrendPreset,
} from '../../../api/hooks/useTrendPreference'
import { useOnboardingStore } from '../../../stores/onboarding'
import { SettingsSection, type SectionStatus } from '../../../components/settings/SettingsSection'
import { PresetButtons } from './PresetButtons'

const RS_PERIOD_DEFAULT = 60

interface ValidationResult {
  ok: boolean
  message?: string
}

/** 1차 client-side pre-validation — backend 권위 (위반 시 backend 한국어 message 그대로 표시). */
function validateMaPeriods(periods: number[]): ValidationResult {
  if (periods.length < 1 || periods.length > 5) {
    return { ok: false, message: 'MA periods 는 1~5개 사이여야 합니다.' }
  }
  if (periods.some((p) => !Number.isInteger(p) || p < 1 || p > 252)) {
    return { ok: false, message: '각 period 는 1~252 사이 정수여야 합니다.' }
  }
  // sorted ASC + 중복 없음 검증.
  for (let i = 1; i < periods.length; i++) {
    if (periods[i] <= periods[i - 1]) {
      return { ok: false, message: 'periods 는 오름차순이며 중복이 없어야 합니다.' }
    }
  }
  return { ok: true }
}

export function TrendPreferenceSection() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const { preference, isLoading, isError, update, isUpdating, updateError } = useTrendPreference({
    userId,
    enabled: userId != null,
  })

  // 로컬 편집 상태 — 서버 응답으로 초기화.
  const [maPeriods, setMaPeriods] = useState<number[]>([5, 20, 60])
  const [rsPeriod, _setRsPeriod] = useState<number>(RS_PERIOD_DEFAULT)
  const [useCrossPair, setUseCrossPair] = useState(false)
  const [crossShort, setCrossShort] = useState<number>(5)
  const [crossLong, setCrossLong] = useState<number>(60)
  const [validationMessage, setValidationMessage] = useState<string | null>(null)
  const [sectionStatus, setSectionStatus] = useState<SectionStatus>('idle')
  const [serverError, setServerError] = useState<string | null>(null)

  // 서버 응답 → 로컬 상태 sync (mount + STOMP push 변경 시).
  useEffect(() => {
    if (!preference) return
    setMaPeriods(preference.maPeriods)
    if (preference.crossPair) {
      setUseCrossPair(true)
      setCrossShort(preference.crossPair.short)
      setCrossLong(preference.crossPair.long)
    } else {
      setUseCrossPair(false)
      setCrossShort(preference.maPeriods[0])
      setCrossLong(preference.maPeriods[preference.maPeriods.length - 1])
    }
  }, [preference])

  const selectedPresetId = detectPresetId(maPeriods)

  function applyPreset(preset: TrendPreset | 'custom') {
    if (preset === 'custom') {
      // 직접 입력 — 현재값 유지하고 selectedId만 'custom'으로 비춤.
      // 사용자가 input 수정하면 자연스럽게 custom 상태 유지.
      return
    }
    setMaPeriods(preset.maPeriods)
    setValidationMessage(null)
  }

  function updateMaAt(idx: number, raw: string) {
    const n = Number(raw)
    if (Number.isNaN(n)) return
    const next = [...maPeriods]
    next[idx] = n
    setMaPeriods(next)
  }

  function addMaSlot() {
    if (maPeriods.length >= 5) return
    const last = maPeriods[maPeriods.length - 1] ?? 1
    setMaPeriods([...maPeriods, Math.min(252, last + 30)])
  }

  function removeMaSlot(idx: number) {
    if (maPeriods.length <= 1) return
    setMaPeriods(maPeriods.filter((_, i) => i !== idx))
  }

  async function handleSave() {
    setValidationMessage(null)
    setServerError(null)

    const validation = validateMaPeriods(maPeriods)
    if (!validation.ok) {
      setValidationMessage(validation.message ?? '입력값을 확인해 주세요.')
      return
    }

    if (useCrossPair) {
      if (!maPeriods.includes(crossShort) || !maPeriods.includes(crossLong)) {
        setValidationMessage('crossPair short·long 모두 MA periods 내 값이어야 합니다.')
        return
      }
      if (crossShort >= crossLong) {
        setValidationMessage('crossPair short는 long보다 작아야 합니다.')
        return
      }
    }

    setSectionStatus('saving')
    update(
      {
        maPeriods,
        rsPeriod,
        crossPair: useCrossPair ? { short: crossShort, long: crossLong } : null,
      },
      {
        onSuccess: () => {
          setSectionStatus('saved')
          setTimeout(() => setSectionStatus('idle'), 3000)
        },
        onError: (err: unknown) => {
          const message =
            err instanceof Error
              ? err.message
              : '설정 저장에 실패했습니다. 다시 시도해 주세요.'
          setSectionStatus('error')
          setServerError(message)
        },
      },
    )
  }

  if (userId == null) {
    return (
      <SettingsSection
        title="추세 분석 설정"
        description="KIS 계좌 등록을 완료한 후 사용할 수 있습니다."
      >
        <p className="text-xs text-gray-500">
          /setup 페이지에서 계좌를 먼저 등록해 주세요.
        </p>
      </SettingsSection>
    )
  }

  return (
    <SettingsSection
      title="추세 분석 설정"
      description="시장 지수·섹터 추세에 사용할 MA 기간과 RS 비교 기간을 변경합니다. 저장 시 즉시 모든 탭에 반영됩니다."
      status={sectionStatus}
      errorMessage={serverError ?? undefined}
    >
      {isLoading ? (
        <div className="h-24 animate-pulse rounded-lg border border-gray-800 bg-gray-900" />
      ) : isError ? (
        <div role="alert" className="text-xs text-red-400">
          설정을 불러오는 중 오류가 발생했습니다.
        </div>
      ) : (
        <div className="space-y-5">
          {/* 프리셋 */}
          <div>
            <label className="mb-2 block text-xs font-medium text-gray-300">프리셋</label>
            <PresetButtons selectedId={selectedPresetId} onSelect={applyPreset} />
          </div>

          {/* MA periods 입력 */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <label className="block text-xs font-medium text-gray-300">
                MA 기간 ({maPeriods.length}/5)
              </label>
              <button
                type="button"
                onClick={addMaSlot}
                disabled={maPeriods.length >= 5}
                className="rounded border border-gray-700 px-2 py-0.5 text-[11px] text-gray-300 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
                aria-label="MA 기간 추가"
              >
                + 추가
              </button>
            </div>
            <div className="flex flex-wrap gap-2">
              {maPeriods.map((p, idx) => (
                <div key={idx} className="flex items-center gap-1">
                  <input
                    type="number"
                    min={1}
                    max={252}
                    value={p}
                    onChange={(e) => updateMaAt(idx, e.target.value)}
                    aria-label={`MA period ${idx + 1}`}
                    className="w-20 rounded-lg border border-gray-700 bg-gray-800 px-2 py-1 text-sm text-white tabular-nums focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                  />
                  <button
                    type="button"
                    onClick={() => removeMaSlot(idx)}
                    disabled={maPeriods.length <= 1}
                    aria-label={`MA period ${idx + 1} 삭제`}
                    className="rounded p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300 disabled:cursor-not-allowed disabled:opacity-30"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
            <p className="mt-1 text-[10px] text-gray-500">
              1~5개 · 각 1~252일 · 오름차순 · 중복 없음
            </p>
          </div>

          {/* rsPeriod (1차 readonly) */}
          <div>
            <label className="mb-2 block text-xs font-medium text-gray-300">
              Mansfield RS 비교 기간
            </label>
            <input
              type="number"
              value={rsPeriod}
              readOnly
              aria-label="RS 비교 기간 (1차 readonly)"
              className="w-24 rounded-lg border border-gray-700 bg-gray-800/50 px-2 py-1 text-sm text-gray-400 tabular-nums focus:outline-none"
            />
            <p className="mt-1 text-[10px] text-gray-500">
              1차 release는 60일 고정 (향후 슬롯 1~252).
            </p>
          </div>

          {/* crossPair 옵션 */}
          <div>
            <label className="mb-2 flex items-center gap-2 text-xs font-medium text-gray-300">
              <input
                type="checkbox"
                checked={useCrossPair}
                onChange={(e) => setUseCrossPair(e.target.checked)}
                className="rounded border-gray-700 bg-gray-800 text-blue-600 focus:ring-blue-500"
              />
              <span>MA 교차 페어 명시</span>
            </label>
            <p className="mb-2 text-[10px] text-gray-500">
              비활성 시 maPeriods 최소·최대 자동 (예: [5,20,60] → 5×60 교차).
            </p>
            {useCrossPair && (
              <div className="flex items-center gap-2">
                <select
                  value={crossShort}
                  onChange={(e) => setCrossShort(Number(e.target.value))}
                  aria-label="cross short MA"
                  className="rounded-lg border border-gray-700 bg-gray-800 px-2 py-1 text-sm text-white"
                >
                  {maPeriods.map((p) => (
                    <option key={p} value={p}>
                      {p}일
                    </option>
                  ))}
                </select>
                <span className="text-xs text-gray-500">×</span>
                <select
                  value={crossLong}
                  onChange={(e) => setCrossLong(Number(e.target.value))}
                  aria-label="cross long MA"
                  className="rounded-lg border border-gray-700 bg-gray-800 px-2 py-1 text-sm text-white"
                >
                  {maPeriods.map((p) => (
                    <option key={p} value={p}>
                      {p}일
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          {/* 검증 메시지 */}
          {validationMessage && (
            <div role="alert" className="rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2 text-xs text-red-300">
              {validationMessage}
            </div>
          )}

          {/* 서버 에러 (mutation onError) */}
          {updateError && !validationMessage && (
            <div role="alert" className="rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2 text-xs text-red-300">
              {updateError instanceof Error
                ? updateError.message
                : '저장 중 오류가 발생했습니다.'}
            </div>
          )}

          {/* 저장 + 현재 상태 */}
          <div className="flex items-center justify-between gap-3">
            <p className="text-[11px] text-gray-500">
              {preference?.isUserCustom
                ? '사용자 정의 설정 적용 중'
                : '기본 설정 적용 중 (변경 후 저장 시 사용자 정의 됨)'}
            </p>
            <button
              type="button"
              onClick={handleSave}
              disabled={isUpdating}
              className="rounded-lg bg-blue-600 px-4 py-1.5 text-xs font-medium text-white transition-colors hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-blue-400"
            >
              {isUpdating ? '저장 중...' : '저장'}
            </button>
          </div>

          {/* 미리보기 안내 (현재값 echo) */}
          {TREND_PRESETS.find((p) => p.id === selectedPresetId) && (
            <p className="text-[10px] text-gray-600">
              현재 프리셋:{' '}
              <span className="text-gray-400">
                {TREND_PRESETS.find((p) => p.id === selectedPresetId)?.label ?? '사용자 정의'}
              </span>
            </p>
          )}
        </div>
      )}
    </SettingsSection>
  )
}
