export interface RiskPlanLevels {
  entryPrice: number
  stopPrice: number
  takePrice: number
  stopLossPct: number
  takeProfitPct: number
  rewardRiskRatio: number
}

export function parsePositiveNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined) return null
  const parsed = typeof value === 'number' ? value : Number(String(value).trim())
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

export function buildRiskPlanLevels(
  entryPrice: number | string | null | undefined,
  stopLossPct: number | string | null | undefined,
  takeProfitPct: number | string | null | undefined,
): RiskPlanLevels | null {
  const entry = parsePositiveNumber(entryPrice)
  const stop = parsePositiveNumber(stopLossPct)
  const take = parsePositiveNumber(takeProfitPct)
  if (entry === null || stop === null || take === null) return null

  return {
    entryPrice: entry,
    stopPrice: roundPrice(entry * (1 - stop / 100)),
    takePrice: roundPrice(entry * (1 + take / 100)),
    stopLossPct: stop,
    takeProfitPct: take,
    rewardRiskRatio: roundPrice(take / stop),
  }
}

function roundPrice(value: number): number {
  return Math.round(value * 10000) / 10000
}

export function formatRiskPlanPct(value: number | string | null | undefined): string {
  const parsed = parsePositiveNumber(value)
  if (parsed === null) return '-'
  return parsed.toLocaleString('ko-KR', {
    maximumFractionDigits: 2,
  })
}

export function formatRiskPlanPrice(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-'
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}
