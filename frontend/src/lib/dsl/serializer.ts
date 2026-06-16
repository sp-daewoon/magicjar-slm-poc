/**
 * DslSerializer — BlockTree → DSL 텍스트 직렬화
 *
 * ADR 0022 §2-1·§2-2·§2-3·§2-4 정합:
 *   - KIS 호환 토큰만 사용 (§6 화이트리스트 매트릭스 LOCK)
 *   - 1줄 표현식 (multi-line 0)
 *   - 화이트리스트 외 토큰 생성 금지
 *   - backend KisYamlDslParser 토큰 1:1
 *
 * multi-output 지표 alias (T-DSL DslIdentifierMapper LOCK):
 *   bb → bb_upper, bb_lower, bb_middle
 *   macd → macd_signal, macd_hist
 *   stochastic_d → stoch_k, stoch_d
 *
 * 룰 5: LLM provider import 0건
 */

import type {
  ArithmeticBlock,
  BlockNode,
  BlockTree,
  CandleBlock,
  ComparisonBlock,
  IndicatorBlock,
  LiteralBlock,
  LogicalBlock,
  NotBlock,
  PatternBlock,
} from './blockTree'

// ──────────────────────────────────────────────
// DSL 토큰 화이트리스트 (§6 매트릭스 LOCK)
// ──────────────────────────────────────────────

/** KIS 호환 비교 연산자 */
const COMPARISON_OPS = new Set(['>', '<', '>=', '<=', '==', '!=', 'crosses_above', 'crosses_below'])

/** KIS 호환 산술 연산자 */
const ARITHMETIC_OPS = new Set(['+', '-', '*', '/'])

/** KIS 호환 논리 연산자 */
const LOGICAL_OPS = new Set(['AND', 'OR'])

/** backend KisYamlDslParser 캔들 직접 참조 토큰 */
const CANDLE_FIELDS = new Set(['open', 'high', 'low', 'close', 'prev_close', 'volume'])

/**
 * multi-output 지표의 outputKey → DSL alias 매핑
 * T-DSL DslIdentifierMapper LOCK
 */
const MULTI_OUTPUT_ALIAS: Record<string, Record<string, string>> = {
  bb: {
    upper: 'bb_upper',
    middle: 'bb_middle',
    lower: 'bb_lower',
  },
  macd: {
    signal: 'macd_signal',
    histogram: 'macd_hist',
    // macd 자체는 그대로
  },
  stochastic_d: {
    k: 'stoch_k',
    d: 'stoch_d',
  },
  fvg: {
    fvg: 'fvg_active',
    bullish_active: 'fvg_bullish_active',
    bearish_active: 'fvg_bearish_active',
    bullish_top: 'fvg_bullish_top',
    bullish_bottom: 'fvg_bullish_bottom',
    bullish_mid: 'fvg_bullish_mid',
    bullish_fill_pct: 'fvg_bullish_fill_pct',
    bearish_top: 'fvg_bearish_top',
    bearish_bottom: 'fvg_bearish_bottom',
    bearish_mid: 'fvg_bearish_mid',
    bearish_fill_pct: 'fvg_bearish_fill_pct',
  },
  order_block: {
    order_block: 'ob_active',
    bullish_active: 'ob_bullish_active',
    bearish_active: 'ob_bearish_active',
    bullish_top: 'ob_bullish_top',
    bullish_bottom: 'ob_bullish_bottom',
    bullish_mid: 'ob_bullish_mid',
    bullish_fill_pct: 'ob_bullish_fill_pct',
    bearish_top: 'ob_bearish_top',
    bearish_bottom: 'ob_bearish_bottom',
    bearish_mid: 'ob_bearish_mid',
    bearish_fill_pct: 'ob_bearish_fill_pct',
    bos_direction: 'ob_bos_direction',
  },
}

// ──────────────────────────────────────────────
// 내부 직렬화 함수
// ──────────────────────────────────────────────

function serializeIndicator(node: IndicatorBlock): string {
  const id = node.id
  const alias = node.outputKey ? MULTI_OUTPUT_ALIAS[id]?.[node.outputKey] : undefined

  if (alias) {
    // bb_upper(20, 2.0) 형태
    const paramStr = Object.values(node.params).join(', ')
    return paramStr ? `${alias}(${paramStr})` : alias
  }

  // volume_ma 처리: volume_ma(period) 단순 pass-through
  const paramStr = Object.values(node.params).join(', ')
  return paramStr ? `${id}(${paramStr})` : id
}

function serializeCandle(node: CandleBlock): string {
  if (!CANDLE_FIELDS.has(node.field)) {
    throw new Error(`Invalid candle field: ${node.field}`)
  }
  return node.field
}

function serializePattern(node: PatternBlock): string {
  if (node.func === 'pattern_detected') {
    return `pattern_detected("${node.patternId}")`
  }
  if (node.func === 'consecutive_up') {
    const min = node.minCount ?? 3
    return `consecutive("up") >= ${min}`
  }
  if (node.func === 'consecutive_down') {
    const min = node.minCount ?? 3
    return `consecutive("down") >= ${min}`
  }
  return `pattern_detected("${node.patternId}")`
}

function serializeLiteral(node: LiteralBlock): string {
  if (node.valueType === 'string') {
    return `"${node.value}"`
  }
  return node.value
}

/**
 * 노드 직렬화 — precedence(우선순위) 인자로 괄호 추가 여부 결정
 * AND/OR는 우선순위가 낮으므로 상위 연산 시 괄호 추가
 */
function serializeNode(node: BlockNode, parentPrec: number = 0): string {
  switch (node.kind) {
    case 'indicator':
      return serializeIndicator(node)
    case 'candle':
      return serializeCandle(node)
    case 'pattern':
      return serializePattern(node)
    case 'literal':
      return serializeLiteral(node)
    case 'comparison':
      return serializeComparison(node, parentPrec)
    case 'logical':
      return serializeLogical(node, parentPrec)
    case 'not':
      return serializeNot(node)
    case 'arithmetic':
      return serializeArithmetic(node, parentPrec)
    default:
      throw new Error(`Unknown block kind: ${(node as BlockNode).kind}`)
  }
}

function serializeComparison(node: ComparisonBlock, parentPrec: number): string {
  if (!COMPARISON_OPS.has(node.op)) {
    throw new Error(`Invalid comparison operator: ${node.op}`)
  }
  const left = node.left ? serializeNode(node.left, 3) : '_'
  const right = node.right ? serializeNode(node.right, 3) : '_'
  const expr = `${left} ${node.op} ${right}`
  // 비교 연산은 우선순위 3 — 논리 연산자(1,2) 내에서는 괄호로 감쌈
  return parentPrec > 3 ? `(${expr})` : expr
}

/**
 * 논리 연산자 우선순위:
 *   AND = 2, OR = 1
 * 부모 우선순위보다 낮으면 괄호 추가
 */
function getLogicalPrec(op: string): number {
  return op === 'AND' ? 2 : 1
}

function serializeLogical(node: LogicalBlock, parentPrec: number): string {
  if (!LOGICAL_OPS.has(node.op)) {
    throw new Error(`Invalid logical operator: ${node.op}`)
  }
  const prec = getLogicalPrec(node.op)
  const left = node.left ? serializeNode(node.left, prec) : '_'
  const right = node.right ? serializeNode(node.right, prec) : '_'
  const expr = `(${left}) ${node.op} (${right})`
  return parentPrec > prec ? `(${expr})` : expr
}

function serializeNot(node: NotBlock): string {
  const inner = node.operand ? serializeNode(node.operand, 4) : '_'
  return `NOT (${inner})`
}

/**
 * 산술 우선순위:
 *   +, - = 10
 *   *, / = 20
 */
function getArithPrec(op: string): number {
  return op === '+' || op === '-' ? 10 : 20
}

function serializeArithmetic(node: ArithmeticBlock, parentPrec: number): string {
  if (!ARITHMETIC_OPS.has(node.op)) {
    throw new Error(`Invalid arithmetic operator: ${node.op}`)
  }
  const prec = getArithPrec(node.op)
  const left = node.left ? serializeNode(node.left, prec) : '_'
  const right = node.right ? serializeNode(node.right, prec) : '_'
  const expr = `${left} ${node.op} ${right}`
  return parentPrec > prec ? `(${expr})` : expr
}

// ──────────────────────────────────────────────
// 공개 API
// ──────────────────────────────────────────────

/**
 * BlockNode → DSL 텍스트 (1줄 string)
 * 빈 트리 → 빈 문자열
 */
export function serializeNode_(node: BlockNode): string {
  return serializeNode(node, 0)
}

/**
 * BlockTree → { entry, exit } DSL 텍스트 쌍
 * entry/exit 중 없는 것은 undefined
 */
export function serializeBlockTree(tree: BlockTree): { entry: string; exit: string } {
  return {
    entry: tree.entry ? serializeNode(tree.entry, 0) : '',
    exit: tree.exit ? serializeNode(tree.exit, 0) : '',
  }
}
