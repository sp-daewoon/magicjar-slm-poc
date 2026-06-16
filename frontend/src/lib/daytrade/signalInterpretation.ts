/**
 * 단타 발굴 후보의 DSL 평가 사유를 사람이 읽는 코멘트로 풀어내는 순수 유틸.
 *
 * 백엔드 reason은 JvmStrategyEvaluator의 Map.toString() 형태라 완전한 JSON은 아니다.
 * 여기서는 안전한 휴리스틱으로 핵심 trigger/operator/lhs/rhs만 추출하고,
 * 실패해도 원문과 점수 구성 기반 해석은 항상 제공한다.
 */

export interface DaytradeSignalScoreComponents {
  signal: number
  volume: number
  atr: number
  cap: number
}

export interface DaytradeSignalMatchLike {
  rank: number
  instrumentCode: string
  instrumentName: string
  score: number
  scoreComponents: DaytradeSignalScoreComponents
  reason: string
}

export interface DaytradeSignalContext {
  source: 'DAYTRADE_DISCOVER'
  code: string
  name: string
  rank: number
  score: number
  scoreComponents: DaytradeSignalScoreComponents
  reason: string
  presetKey: string | null
  presetTitle: string | null
  summary: string
  reasonComments: string[]
  scoreComments: string[]
}

export interface DaytradeSignalNavigationState {
  daytradeSignal?: DaytradeSignalContext
}

const SCORE_WEIGHTS: Record<keyof DaytradeSignalScoreComponents, number> = {
  signal: 0.4,
  volume: 0.3,
  atr: 0.2,
  cap: 0.1,
}

const SCORE_LABELS: Record<keyof DaytradeSignalScoreComponents, string> = {
  signal: '시그널',
  volume: '거래대금',
  atr: '변동성',
  cap: '시총 안정성',
}

const FIELD_LABELS: Record<string, string> = {
  open: '시가',
  high: '고가',
  low: '저가',
  close: '종가',
  prev_close: '전일 종가',
  volume: '거래량',
  volume_ma: '평균 거래량',
  macd_signal: 'MACD 시그널',
  macd_hist: 'MACD 히스토그램',
  bb_upper: '볼린저 상단',
  bb_middle: '볼린저 중심선',
  bb_lower: '볼린저 하단',
  stoch_k: '스토캐스틱 %K',
  stoch_d: '스토캐스틱 %D',
}

function clamp01(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

function pct(value: number): string {
  return `${Math.round(clamp01(value) * 100)}%`
}

function scorePoint(value: number): string {
  return `${(clamp01(value) * 100).toFixed(1)}점`
}

function formatNumber(raw: string): string {
  const normalized = raw.trim()
  const value = Number(normalized)
  if (!Number.isFinite(value)) return normalized
  if (Math.abs(value) >= 1000) return Math.round(value).toLocaleString()
  if (Math.abs(value) >= 10) return value.toFixed(2).replace(/\.?0+$/, '')
  return value.toFixed(4).replace(/\.?0+$/, '')
}

function labelOf(raw: string): string {
  const token = raw.trim()
  const direct = FIELD_LABELS[token]
  if (direct) return direct

  const ma = token.match(/^ma\((\d+)\)$/i)
  if (ma) return `${ma[1]}일 이동평균`

  const high = token.match(/^high\((\d+)\)$/i)
  if (high) return `최근 ${high[1]}봉 고점`

  const low = token.match(/^low\((\d+)\)$/i)
  if (low) return `최근 ${low[1]}봉 저점`

  const rsi = token.match(/^rsi\((\d+)\)$/i)
  if (rsi) return `RSI(${rsi[1]})`

  const natr = token.match(/^natr\((\d+)\)$/i)
  if (natr) return `NATR(${natr[1]})`

  return token
}

function operatorLabel(operator: string): string {
  switch (operator.trim()) {
    case '>':
      return '보다 높음'
    case '>=':
      return '이상'
    case '<':
      return '보다 낮음'
    case '<=':
      return '이하'
    case '==':
    case '=':
      return '같음'
    case '!=':
      return '다름'
    case 'CROSSES_ABOVE':
    case 'crosses_above':
      return '상향 돌파'
    case 'CROSSES_BELOW':
    case 'crosses_below':
      return '하향 이탈'
    default:
      return operator.trim()
  }
}

function parsePreset(reason: string): {
  presetKey: string | null
  presetTitle: string | null
  detail: string
} {
  const matched = reason.match(/^\[([^\]]+)]\s*([^—]+?)\s*—\s*(.*)$/)
  if (!matched) return { presetKey: null, presetTitle: null, detail: reason }
  return {
    presetKey: matched[1]?.trim() || null,
    presetTitle: matched[2]?.trim() || null,
    detail: matched[3]?.trim() || reason,
  }
}

function extractThresholdComments(detail: string): string[] {
  const comments: string[] = []
  const regex =
    /trigger=THRESHOLD,\s*operator=([^,}]+),\s*lhs=([^,}]+),\s*lhsValue=([^,}]+),\s*rhs=(.*?),\s*rhsValue=([^,}]+)/g
  for (const match of detail.matchAll(regex)) {
    const [, operator, lhs, lhsValue, rhs, rhsValue] = match
    comments.push(
      `${labelOf(lhs)}가 ${formatNumber(lhsValue)}로, 기준 ${labelOf(rhs)} ${formatNumber(
        rhsValue,
      )} ${operatorLabel(operator)} 조건을 통과했습니다.`,
    )
  }
  return comments
}

function extractCrossoverComments(detail: string): string[] {
  const comments: string[] = []
  const regex =
    /trigger=CROSSOVER,\s*operator=([^,}]+),\s*lhs=([^,}]+),\s*rhs=([^,}]+),.*?lhsPrevValue=([^,}]+),\s*rhsPrevValue=([^,}]+)/g
  for (const match of detail.matchAll(regex)) {
    const [, operator, lhs, rhs, lhsPrevValue, rhsPrevValue] = match
    comments.push(
      `${labelOf(lhs)}가 ${labelOf(rhs)}를 ${operatorLabel(
        operator,
      )}했습니다. 직전 값은 각각 ${formatNumber(lhsPrevValue)} / ${formatNumber(
        rhsPrevValue,
      )}였습니다.`,
    )
  }
  return comments
}

function extractBreakoutComments(detail: string): string[] {
  const comments: string[] = []
  const regex =
    /trigger=BREAKOUT,\s*lhs=([^,}]+),\s*lhsValue=([^,}]+),\s*rhs=(.*?),\s*rhsValue=([^,}]+)/g
  for (const match of detail.matchAll(regex)) {
    const [, lhs, lhsValue, rhs, rhsValue] = match
    comments.push(
      `${labelOf(lhs)} ${formatNumber(lhsValue)}가 돌파 기준 ${labelOf(rhs)} ${formatNumber(
        rhsValue,
      )}를 넘어섰습니다.`,
    )
  }
  return comments
}

function extractPatternComments(detail: string): string[] {
  const comments: string[] = []

  const consecutive = detail.match(
    /trigger=PATTERN,\s*patternType=consecutive,\s*direction=([^,}]+),\s*count=([^,}]+),\s*operator=([^,}]+),\s*target=([^,}]+)/,
  )
  if (consecutive) {
    const [, direction, count, operator, target] = consecutive
    comments.push(
      `${direction === 'up' ? '연속 상승' : '연속 하락'} 패턴이 ${count}회 관측되어 기준 ${target}회 ${operatorLabel(
        operator,
      )} 조건을 만족했습니다.`,
    )
  }

  const named = detail.match(/trigger=PATTERN,.*?patternName=([^,}]+),\s*matched=([^,}]+)/)
  if (named) {
    const [, patternName, matched] = named
    comments.push(
      `${patternName} 캔들 패턴이 ${matched === 'true' ? '감지되어' : '감지되지 않았지만'} 평가 사유에 포함됐습니다.`,
    )
  }

  return comments
}

function buildReasonComments(reason: string): {
  presetKey: string | null
  presetTitle: string | null
  comments: string[]
} {
  const { presetKey, presetTitle, detail } = parsePreset(reason)
  const comments = [
    ...extractThresholdComments(detail),
    ...extractCrossoverComments(detail),
    ...extractBreakoutComments(detail),
    ...extractPatternComments(detail),
  ]

  if (detail.includes('operator=AND')) {
    comments.unshift('여러 조건이 동시에 맞아야 하는 AND 조합에서 BUY 판정이 났습니다.')
  } else if (detail.includes('operator=OR')) {
    comments.unshift('여러 조건 중 하나 이상이 맞으면 되는 OR 조합에서 BUY 판정이 났습니다.')
  }

  if (comments.length === 0) {
    comments.push(
      reason.trim()
        ? '평가 엔진이 이 프리셋의 BUY 조건을 통과했다고 판단했습니다. 아래 원문 사유로 세부 조건을 확인할 수 있습니다.'
        : 'BUY 평가 사유가 비어 있어 점수 구성 중심으로 해석합니다.',
    )
  }

  return { presetKey, presetTitle, comments }
}

function buildScoreComments(scoreComponents: DaytradeSignalScoreComponents): string[] {
  const entries = (Object.keys(SCORE_WEIGHTS) as Array<keyof DaytradeSignalScoreComponents>)
    .map((key) => {
      const value = clamp01(scoreComponents[key])
      const weighted = value * SCORE_WEIGHTS[key]
      return { key, value, weighted }
    })
    .sort((a, b) => b.weighted - a.weighted)

  const top = entries[0]
  const low = entries.find((entry) => entry.value < 0.35)
  const comments = [
    `${SCORE_LABELS[top.key]} 축이 ${pct(top.value)}로 가장 크게 기여했습니다. 이 축의 랭킹 가중치는 ${Math.round(
      SCORE_WEIGHTS[top.key] * 100,
    )}%입니다.`,
    `거래대금은 유동성, 변동성은 ATR sweet spot, 시총 안정성은 너무 얇은 종목을 피하기 위한 보정값입니다.`,
  ]

  if (low) {
    comments.push(
      `${SCORE_LABELS[low.key]} 점수는 ${pct(
        low.value,
      )}로 낮은 편이라, 이 후보는 해당 축에서 보수적으로 봐야 합니다.`,
    )
  }

  return comments
}

export function buildDaytradeSignalContext(
  match: DaytradeSignalMatchLike,
): DaytradeSignalContext {
  const { presetKey, presetTitle, comments } = buildReasonComments(match.reason)
  const scoreComments = buildScoreComments(match.scoreComponents)
  const presetLabel = presetTitle ?? presetKey ?? '단타 발굴'
  return {
    source: 'DAYTRADE_DISCOVER',
    code: match.instrumentCode,
    name: match.instrumentName,
    rank: match.rank,
    score: match.score,
    scoreComponents: match.scoreComponents,
    reason: match.reason,
    presetKey,
    presetTitle,
    summary: `${match.instrumentName}은 ${presetLabel} 조건에서 BUY 후보로 잡혔고, 발굴 종합점수는 ${scorePoint(
      match.score,
    )}입니다.`,
    reasonComments: comments,
    scoreComments,
  }
}

export function buildDaytradeSignalNavigationState(
  match: DaytradeSignalMatchLike,
): DaytradeSignalNavigationState {
  return { daytradeSignal: buildDaytradeSignalContext(match) }
}

export function buildDaytradeSignalAiContext(
  signal: DaytradeSignalContext,
): Record<string, unknown> {
  const score = scorePoint(signal.score)
  const reasonLines = [signal.summary, ...signal.reasonComments, ...signal.scoreComments]
  return {
    code: signal.code,
    signalKind: '단타 발굴 BUY 후보',
    firedAt: `발굴 순위 ${signal.rank}위`,
    symbolState: [
      `종목명: ${signal.name}`,
      `발굴 점수: ${score}`,
      `프리셋: ${signal.presetTitle ?? signal.presetKey ?? '단타 발굴'}`,
      `점수 구성: 시그널 ${pct(signal.scoreComponents.signal)}, 거래대금 ${pct(
        signal.scoreComponents.volume,
      )}, 변동성 ${pct(signal.scoreComponents.atr)}, 시총 안정성 ${pct(signal.scoreComponents.cap)}`,
      `로컬 해석: ${reasonLines.join(' ')}`,
      `원문 사유: ${signal.reason}`,
    ].join('\n'),
    strategyName: signal.presetTitle
      ? `단타 발굴: ${signal.presetTitle}`
      : '단타 발굴 후보',
  }
}
