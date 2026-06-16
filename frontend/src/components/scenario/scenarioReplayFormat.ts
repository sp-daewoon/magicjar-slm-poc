import type { ScenarioEventDto, ScenarioRunSummaryDto } from '../../api/scenarioReplay'

export function formatKst(iso: string | null | undefined): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  }).format(date)
}

export function formatMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '-'
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isNaN(n)) return String(value)
  return `${Math.round(n).toLocaleString('ko-KR')}원`
}

export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-'
  return `${value.toLocaleString('ko-KR')}주`
}

export function statusLabel(status: ScenarioRunSummaryDto['status']): string {
  if (status === 'SUCCESS') return '완료'
  if (status === 'ABORTED') return '중단'
  if (status === 'FAILED') return '실패'
  return status
}

export function statusClass(status: ScenarioRunSummaryDto['status']): string {
  if (status === 'SUCCESS') return 'bg-emerald-900/50 text-emerald-300'
  if (status === 'ABORTED') return 'bg-amber-900/50 text-amber-300'
  if (status === 'FAILED') return 'bg-red-900/50 text-red-300'
  return 'bg-gray-800 text-gray-300'
}

export function eventLabel(type: ScenarioEventDto['type']): string {
  const labels: Record<string, string> = {
    CANDLE: '캔들',
    SIGNAL_BUY: '매수 신호',
    SIGNAL_SELL: '매도 신호',
    SIGNAL_HOLD: '보류 신호',
    RISK_ALLOWED: '리플레이 리스크 통과',
    RISK_BLOCKED: '리플레이 리스크 차단',
    ORDER_REQUESTED: '가상 주문 요청',
    ORDER_HELD: '가상 주문 보류',
    ORDER_REJECTED: '가상 주문 거절',
    ORDER_FILLED: '가상 체결',
    POSITION_UPDATED: '가상 포지션 갱신',
    DATA_NOT_READY: '리플레이 데이터 미준비',
    STRATEGY_ERROR: '전략 평가 오류',
    FAULT_INJECTED: '장애 주입',
    GATE_SKIPPED: '게이트 차단(장운영시간 외)',
    GATE_NOT_SIMULATED: '게이트 미재현(실계좌 상태 필요)',
  }
  return labels[type] ?? type
}

export function eventClass(event: ScenarioEventDto): string {
  if (event.severity === 'ERROR') return 'border-red-800 bg-red-950/30 text-red-200'
  if (event.severity === 'WARN') return 'border-amber-800 bg-amber-950/30 text-amber-200'
  if (event.type === 'ORDER_FILLED') return 'border-emerald-800 bg-emerald-950/30 text-emerald-200'
  if (event.type.startsWith('SIGNAL')) return 'border-blue-800 bg-blue-950/30 text-blue-200'
  return 'border-gray-800 bg-gray-900 text-gray-200'
}

export function compactPayload(payload: Record<string, unknown>): string {
  const keys = Object.keys(payload)
  if (keys.length === 0) return ''
  return JSON.stringify(payload)
}
