import { type SigStrategyDto } from '../../api/strategy'

export function formatStrategyCodeLabel(
  code: string,
  codeNames: Record<string, string> | undefined,
): string {
  const name = codeNames?.[code]?.trim()
  return name && name !== code ? `${name}(${code})` : code
}

export function formatStrategyTargetLabel(
  strategy: Pick<SigStrategyDto, 'codes' | 'codeNames' | 'intervals' | 'tf'>,
): string {
  const codes = strategy.codes.length > 0
    ? strategy.codes.map((code) => formatStrategyCodeLabel(code, strategy.codeNames)).join(', ')
    : '종목 미지정'
  const intervals = strategy.intervals.length > 0 ? strategy.intervals.join(', ') : strategy.tf ?? '-'
  return `${codes} / ${intervals}`
}

export function isPaperStrategy(strategy: Pick<SigStrategyDto, 'origin'>): boolean {
  return strategy.origin === 'PAPER_DISCOVERY'
}

export function strategyActivationBlockedReason(strategy: SigStrategyDto): string | null {
  if (isPaperStrategy(strategy)) return null
  if (strategy.activationReady !== false) return null
  return strategy.activationBlockedReason?.trim() || '성공한 백테스트가 있어야 자동매매를 시작할 수 있습니다.'
}

export function resolveStrategyMutationErrorMessage(error: unknown): string {
  const fallback = '상태 변경 실패. 다시 시도해 주세요.'
  if (error && typeof error === 'object' && 'response' in error) {
    const response = (error as { response?: { status?: number; data?: unknown } }).response
    const message = extractBackendMessage(response?.data)
    if (message && !(response?.status === 409 && message === 'Conflict')) return message
    if (response?.status === 409) {
      return '성공한 백테스트가 있어야 자동매매를 시작할 수 있습니다.'
    }
  }
  if (error instanceof Error && error.message) return error.message
  return fallback
}

function extractBackendMessage(data: unknown): string | null {
  if (typeof data === 'string' && data.trim()) return data
  if (!data || typeof data !== 'object') return null
  const record = data as Record<string, unknown>
  const message = record.detail ?? record.message ?? record.error ?? record.title
  return message ? String(message) : null
}
