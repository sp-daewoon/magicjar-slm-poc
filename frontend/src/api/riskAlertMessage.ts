import type { RiskEventDto } from './risk'

interface RiskAlertMessageParts {
  prefix: string
  title: string
  message: string
  formula: string | null
}

export function buildRiskToastMessage(event: RiskEventDto): string {
  const { prefix, title, message, formula } = buildRiskAlertMessageParts(event)
  return [title ? `${prefix} ${title}` : `${prefix} ${event.rule}`, message, formula ? `수식: ${formula}` : null]
    .filter(Boolean)
    .join('\n')
}

export function buildRiskAlertMessageParts(event: RiskEventDto): RiskAlertMessageParts {
  const prefix = event.verdict === 'AUTO_PAUSED' ? '[자동 매수차단]' : event.verdict === 'BLOCKED' ? '[차단]' : `[${event.verdict}]`
  const title = event.alertTitle ?? fallbackTitle(event.rule)
  const message = event.alertMessage ?? fallbackMessage(event)
  const formula = event.alertFormula ?? fallbackFormula(event)

  return { prefix, title, message, formula }
}

function fallbackTitle(rule: string): string {
  switch (rule) {
    case 'POSITION_LIMIT':
      return 'A. 포지션 한도 초과'
    case 'DAILY_LOSS_LIMIT':
      return 'B. 일일 손실 한도 초과'
    case 'MAX_CONCURRENT_POSITIONS':
      return 'C. 동시 보유 종목 한도 도달'
    case 'TRADE_FREQUENCY_MINUTE':
      return 'D. 거래 빈도 한도 도달 - 분당 최대 거래'
    case 'TRADE_FREQUENCY_DAY':
      return 'D. 거래 빈도 한도 도달 - 일별 최대 거래'
    case 'KILL_SWITCH_ON':
      return 'KillSwitch ON'
    case 'SELL_QUANTITY_UNAVAILABLE':
      return '매도 가능 수량 부족 - 미체결 매도 주문 보호'
    case 'SELL_WITHOUT_POSITION':
      return '보유 수량 없는 매도 신호 차단'
    case 'RISK_PLAN_REQUIRED':
      return '손절/익절 RiskPlan 미설정'
    case 'INVALID_SIGNAL_PRICE':
      return '비정상 신호 가격 차단'
    case 'INVALID_ORDER_INTENT':
      return '주문 수량 계산 결과 0주 차단'
    case 'KIS_EXECUTION_SYNC_STALE':
      return 'KIS 체결 동기화 미완료 차단'
    default:
      return rule || '리스크 차단'
  }
}

function fallbackMessage(event: RiskEventDto): string {
  switch (event.rule) {
    case 'TRADE_FREQUENCY_DAY':
      return '오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.'
    case 'TRADE_FREQUENCY_MINUTE':
      return '최근 1분 안에 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.'
    case 'POSITION_LIMIT':
      return '종목당 최대 투자금액을 넘어서 자동 주문을 내보내지 않았습니다.'
    case 'MAX_CONCURRENT_POSITIONS':
      return '최대 보유 종목 수를 이미 채워서 추가 매수 주문을 내보내지 않았습니다.'
    case 'DAILY_LOSS_LIMIT':
      return '오늘 누적 손실이 설정한 DLL 한도에 도달해 신규 자동 주문을 내보내지 않았습니다.'
    case 'SELL_QUANTITY_UNAVAILABLE':
      return '기존 미체결 매도 주문이 보유 수량을 예약해 추가 매도 주문을 내보내지 않았습니다.'
    case 'SELL_WITHOUT_POSITION':
      return '보유 수량이 0주라 매도 주문을 내보내지 않았습니다.'
    case 'RISK_PLAN_REQUIRED':
      return '자동매매 매수 전에 손절과 익절 계획을 먼저 설정해야 합니다.'
    case 'INVALID_SIGNAL_PRICE':
      return '신호 가격이 0 이하라 주문 수량과 한도를 계산할 수 없습니다.'
    case 'INVALID_ORDER_INTENT':
      return '리스크 한도를 적용한 뒤 주문 가능 수량이 0주라 주문을 만들지 않았습니다.'
    case 'KIS_EXECUTION_SYNC_STALE':
      return '실계좌 체결 내역 동기화가 완료되지 않아 자동 주문을 보류했습니다.'
    default:
      return event.reason
  }
}

function fallbackFormula(event: RiskEventDto): string | null {
  const raw = event.rawReason ?? event.reason
  if (event.rule === 'POSITION_LIMIT') {
    const parsed = /Unit price ([\d.]+) exceeds remaining position budget ([\d.]+) \(budget=([\d.]+), existing=([\d.]+), min\(abs=([\d.]+), pct=([\d.]+)\)\)/.exec(raw)
    if (parsed) {
      const [, unitCost, remaining, budget, existing, absLimit, percentBudget] = parsed
      return [
        `신호 단가 ${formatWon(unitCost)} > 남은 종목 한도 ${formatWon(remaining)}`,
        `종목 한도 = min(절대 한도 ${formatWon(absLimit)}, 총 자산 비율 한도 ${formatWon(percentBudget)}) = ${formatWon(budget)}`,
        `남은 종목 한도 = ${formatWon(budget)} - 기존 노출 ${formatWon(existing)} = ${formatWon(remaining)}`,
      ].join('\n')
    }
  }
  if (event.rule === 'SELL_QUANTITY_UNAVAILABLE') {
    const parsed = /SELL signal has no sellable quantity for code=([A-Z0-9]+) \(holding=(\d+), reservedSell=(\d+)\)/.exec(raw)
    if (parsed) {
      const [, code, holding, reservedSell] = parsed
      const available = Math.max(0, Number(holding) - Number(reservedSell))
      return `종목 ${code} 매도 가능 수량 = 보유 ${holding}주 - 미체결 매도 ${reservedSell}주 = ${available}주`
    }
  }
  if (event.rule === 'TRADE_FREQUENCY_DAY') {
    const parsed = /Strategy daily trade count (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `오늘 허용 주문 수 ${parsed[1]}회 >= 일별 최대 거래 ${parsed[2]}회/일`
  }
  if (event.rule === 'TRADE_FREQUENCY_MINUTE') {
    const parsed = /Strategy minute trade count (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `최근 1분 허용 주문 수 ${parsed[1]}회 >= 분당 최대 거래 ${parsed[2]}회/분`
  }
  if (event.rule === 'MAX_CONCURRENT_POSITIONS') {
    const parsed = /Concurrent positions (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `현재 보유 종목 수 ${parsed[1]}개 >= 최대 보유 종목 수 ${parsed[2]}개`
  }
  return null
}

function formatWon(value: string): string {
  const n = Number(value)
  if (!Number.isFinite(n)) return `${value}원`
  return `${Math.floor(n).toLocaleString('ko-KR')}원`
}
