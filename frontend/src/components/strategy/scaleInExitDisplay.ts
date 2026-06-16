/**
 * 분할 매수(scale-in) / 분할 익절(partial-exit) 빌더 입력 검증·표시 헬퍼 — ADR 0111 (분할매수 R3-a)
 *
 * 도메인 검증 상수(domain/strategy/ScaleInPlan.kt ScaleInLimits·ExitLimits)와 정합:
 *   - 분할 회수 1~10
 *   - 비중 합 100±0.01 (수동 입력 시)
 *   - 분할 익절 단계 ≤ 5
 *   - 익절 단계 weight 합 ≤ 100
 *
 * 서버(StrategyDefinition init / 컨트롤러)가 최종 권위지만, 사용자에게 즉시 피드백을 주기 위해
 * 동일 규칙을 클라이언트에서 선검증한다. mock 합성 0건 (계산만).
 *
 * 룰 5: LLM provider import 0건
 */
import type { PartialExitDto, ReentryTrigger, ScaleInDto } from '../../api/strategy'

export const SCALE_IN_LIMITS = {
  MIN_SLICES: 1,
  MAX_SLICES: 10,
  DEFAULT_MIN_SLICE_INTERVAL_SEC: 60,
  WEIGHT_TOTAL: 100,
  WEIGHT_SUM_TOLERANCE: 0.01,
} as const

export const EXIT_LIMITS = {
  MAX_PARTIAL_EXIT_STAGES: 5,
  WEIGHT_TOTAL: 100,
} as const

/** 균등 분할 비중 — 100/N, 마지막 회차가 나머지를 흡수해 합이 정확히 100. */
export function evenSliceWeights(totalSlices: number): number[] {
  if (totalSlices <= 0) return []
  const even = Math.floor((SCALE_IN_LIMITS.WEIGHT_TOTAL / totalSlices) * 100) / 100
  const head = Array.from({ length: totalSlices - 1 }, () => even)
  const last = roundTo2(SCALE_IN_LIMITS.WEIGHT_TOTAL - even * (totalSlices - 1))
  return [...head, last]
}

function roundTo2(value: number): number {
  return Math.round(value * 100) / 100
}

export function sumWeights(weights: Array<number | string>): number {
  return weights.reduce<number>((acc, w) => {
    const n = typeof w === 'number' ? w : Number(String(w).trim())
    return acc + (Number.isFinite(n) ? n : 0)
  }, 0)
}

export interface ScaleInValidationResult {
  ok: boolean
  /** 사용자 노출 오류 메시지 (ok=false 시) */
  error?: string
}

/**
 * 분할 매수 입력 검증 (도메인 ScaleInPlan.init 규칙 정합).
 * - totalSlices 1~10
 * - 수동 비중: 개수 = totalSlices, 각 양수, 합 100±0.01
 * - PRICE_DROP_PCT: priceDropPct 음수 필수
 * - minSliceIntervalSec >= 0
 */
export function validateScaleIn(input: {
  totalSlices: number
  sliceWeights?: Array<number | string> | null
  reentryTrigger?: ReentryTrigger
  priceDropPct?: number | string | null
  minSliceIntervalSec?: number | null
}): ScaleInValidationResult {
  const { totalSlices, sliceWeights, reentryTrigger, priceDropPct, minSliceIntervalSec } = input

  if (!Number.isInteger(totalSlices) || totalSlices < SCALE_IN_LIMITS.MIN_SLICES || totalSlices > SCALE_IN_LIMITS.MAX_SLICES) {
    return { ok: false, error: `분할 회수는 ${SCALE_IN_LIMITS.MIN_SLICES}~${SCALE_IN_LIMITS.MAX_SLICES} 사이여야 합니다` }
  }

  if (sliceWeights && sliceWeights.length > 0) {
    if (sliceWeights.length !== totalSlices) {
      return { ok: false, error: `회당 비중 개수(${sliceWeights.length})가 분할 회수(${totalSlices})와 같아야 합니다` }
    }
    const nums = sliceWeights.map((w) => (typeof w === 'number' ? w : Number(String(w).trim())))
    if (nums.some((n) => !Number.isFinite(n) || n <= 0)) {
      return { ok: false, error: '회당 비중은 모두 0보다 큰 숫자여야 합니다' }
    }
    const sum = sumWeights(sliceWeights)
    if (Math.abs(sum - SCALE_IN_LIMITS.WEIGHT_TOTAL) > SCALE_IN_LIMITS.WEIGHT_SUM_TOLERANCE) {
      return { ok: false, error: `회당 비중 합이 100이어야 합니다 (현재 ${roundTo2(sum)})` }
    }
  }

  if (reentryTrigger === 'PRICE_DROP_PCT') {
    const drop = priceDropPct === null || priceDropPct === undefined ? NaN : Number(String(priceDropPct).trim())
    if (!Number.isFinite(drop) || drop >= 0) {
      return { ok: false, error: '가격 하락 재진입은 하락폭을 음수(예: -3)로 입력해야 합니다' }
    }
  }

  if (minSliceIntervalSec !== null && minSliceIntervalSec !== undefined && minSliceIntervalSec < 0) {
    return { ok: false, error: '회차 간 최소 간격은 0 이상이어야 합니다' }
  }

  return { ok: true }
}

/**
 * 분할 익절 단계 검증 (도메인 PartialExit.init + ExitLimits.validatePartialExits 정합).
 * - 단계 ≤ 5
 * - 각 triggerProfitPct > 0, exitWeightPct ∈ (0, 100]
 * - weight 합 ≤ 100
 */
export function validatePartialExits(stages: PartialExitDto[]): ScaleInValidationResult {
  if (stages.length === 0) return { ok: true }
  if (stages.length > EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES) {
    return { ok: false, error: `분할 익절 단계는 최대 ${EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES}개입니다` }
  }
  let weightSum = 0
  for (const stage of stages) {
    const trigger = Number(String(stage.triggerProfitPct).trim())
    const weight = Number(String(stage.exitWeightPct).trim())
    if (!Number.isFinite(trigger) || trigger <= 0) {
      return { ok: false, error: '익절 수익률은 0보다 큰 숫자여야 합니다' }
    }
    if (!Number.isFinite(weight) || weight <= 0 || weight > EXIT_LIMITS.WEIGHT_TOTAL) {
      return { ok: false, error: '청산 비중은 0 초과 100 이하여야 합니다' }
    }
    weightSum += weight
  }
  if (weightSum > EXIT_LIMITS.WEIGHT_TOTAL) {
    return { ok: false, error: `분할 익절 비중 합이 100을 넘을 수 없습니다 (현재 ${roundTo2(weightSum)})` }
  }
  return { ok: true }
}

/** SCALE_IN_NOT_ENABLED 서버 거부 여부 판별 (활성화 게이트 graceful UX) */
export function isScaleInNotEnabledError(message: string | null | undefined): boolean {
  return !!message && message.includes('SCALE_IN_NOT_ENABLED')
}

export const SCALE_IN_NOT_ENABLED_NOTICE =
  '분할매수/분할익절 기능은 리플레이·백테스트 정합 검증 완료 후 활성화됩니다 — DRAFT(중지 상태) 저장은 가능합니다.'

/** 분할 매수 요약 라벨 (표시용) */
export function describeScaleIn(scaleIn: ScaleInDto | null | undefined): string | null {
  if (!scaleIn) return null
  const trigger = scaleIn.reentryTrigger === 'PRICE_DROP_PCT' ? '가격 하락 재진입' : '시그널 재발화'
  return `${scaleIn.totalSlices}분할 · ${trigger}`
}
