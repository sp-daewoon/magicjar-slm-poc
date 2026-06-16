/**
 * DSL 텍스트 → BlockTree 파서
 *
 * backend KisYamlDslParser 6단 재귀 하강 정합:
 *   or → and → comparison → arithmetic → term → factor
 *
 * 화이트리스트 가드:
 *   - 허용 토큰 외 식별자 → ParseError throw
 *   - 최대 깊이 50 (무한재귀 방지)
 *
 * 실패 시: try/catch 래퍼 → caller가 DslTextEditor에서 안내
 *
 * 룰 5: LLM provider import 0건
 */

import type {
  ArithmeticOperator,
  BlockNode,
  BlockTree,
  CandleBlock,
  ComparisonOperator,
  IndicatorBlock,
  IndicatorParams,
  LiteralBlock,
  LogicalOperator,
} from './blockTree'
import { INDICATOR_CATALOG_IDS } from './catalogIds'
import { PATTERN_CATALOG_IDS } from './catalogIds'

// ──────────────────────────────────────────────
// 토큰 정의
// ──────────────────────────────────────────────

type TokenType =
  | 'NUMBER'
  | 'STRING'
  | 'BOOLEAN'
  | 'IDENT'
  | 'OP'
  | 'LPAREN'
  | 'RPAREN'
  | 'COMMA'
  | 'EOF'

interface Token {
  type: TokenType
  value: string
}

const COMPARISON_OPS = new Set(['>', '<', '>=', '<=', '==', '!=', 'crosses_above', 'crosses_below'])
const CANDLE_FIELDS = new Set(['open', 'high', 'low', 'close', 'prev_close', 'volume'])
const LOGICAL_KEYWORDS = new Set(['AND', 'OR', 'NOT'])

/** 멀티-output alias → (지표id, outputKey) 역매핑 */
const ALIAS_MAP: Record<string, { id: string; outputKey: string }> = {
  bb_upper: { id: 'bb', outputKey: 'upper' },
  bb_middle: { id: 'bb', outputKey: 'middle' },
  bb_lower: { id: 'bb', outputKey: 'lower' },
  macd_signal: { id: 'macd', outputKey: 'signal' },
  macd_hist: { id: 'macd', outputKey: 'histogram' },
  stoch_k: { id: 'stochastic_d', outputKey: 'k' },
  stoch_d: { id: 'stochastic_d', outputKey: 'd' },
  fvg_active: { id: 'fvg', outputKey: 'fvg' },
  fvg_bullish_active: { id: 'fvg', outputKey: 'bullish_active' },
  fvg_bearish_active: { id: 'fvg', outputKey: 'bearish_active' },
  fvg_bullish_top: { id: 'fvg', outputKey: 'bullish_top' },
  fvg_bullish_bottom: { id: 'fvg', outputKey: 'bullish_bottom' },
  fvg_bullish_mid: { id: 'fvg', outputKey: 'bullish_mid' },
  fvg_bullish_fill_pct: { id: 'fvg', outputKey: 'bullish_fill_pct' },
  fvg_bearish_top: { id: 'fvg', outputKey: 'bearish_top' },
  fvg_bearish_bottom: { id: 'fvg', outputKey: 'bearish_bottom' },
  fvg_bearish_mid: { id: 'fvg', outputKey: 'bearish_mid' },
  fvg_bearish_fill_pct: { id: 'fvg', outputKey: 'bearish_fill_pct' },
  ob_active: { id: 'order_block', outputKey: 'order_block' },
  ob_bullish_active: { id: 'order_block', outputKey: 'bullish_active' },
  ob_bearish_active: { id: 'order_block', outputKey: 'bearish_active' },
  ob_bullish_top: { id: 'order_block', outputKey: 'bullish_top' },
  ob_bullish_bottom: { id: 'order_block', outputKey: 'bullish_bottom' },
  ob_bullish_mid: { id: 'order_block', outputKey: 'bullish_mid' },
  ob_bullish_fill_pct: { id: 'order_block', outputKey: 'bullish_fill_pct' },
  ob_bearish_top: { id: 'order_block', outputKey: 'bearish_top' },
  ob_bearish_bottom: { id: 'order_block', outputKey: 'bearish_bottom' },
  ob_bearish_mid: { id: 'order_block', outputKey: 'bearish_mid' },
  ob_bearish_fill_pct: { id: 'order_block', outputKey: 'bearish_fill_pct' },
  ob_bos_direction: { id: 'order_block', outputKey: 'bos_direction' },
}

// ──────────────────────────────────────────────
// 렉서 (Tokenizer)
// ──────────────────────────────────────────────

class Lexer {
  private tokens: Token[] = []
  private current = 0

  constructor(private input: string) {
    this.tokenize()
  }

  private tokenize(): void {
    const src = this.input
    let i = 0
    while (i < src.length) {
      const ch = src[i]

      // 공백 스킵
      if (/\s/.test(ch)) {
        i++
        continue
      }

      // 숫자
      if (/[0-9]/.test(ch) || (ch === '-' && i + 1 < src.length && /[0-9]/.test(src[i + 1]))) {
        let num = ch
        i++
        while (i < src.length && /[0-9.]/.test(src[i])) {
          num += src[i++]
        }
        this.tokens.push({ type: 'NUMBER', value: num })
        continue
      }

      // 문자열 리터럴
      if (ch === '"') {
        i++
        let str = ''
        while (i < src.length && src[i] !== '"') {
          str += src[i++]
        }
        i++ // 닫는 "
        this.tokens.push({ type: 'STRING', value: str })
        continue
      }

      // 2자 연산자
      const two = src.slice(i, i + 2)
      if (two === '>=' || two === '<=' || two === '==' || two === '!=') {
        this.tokens.push({ type: 'OP', value: two })
        i += 2
        continue
      }

      // 1자 연산자·구분자
      if ('><+-*/'.includes(ch)) {
        this.tokens.push({ type: 'OP', value: ch })
        i++
        continue
      }
      if (ch === '(') {
        this.tokens.push({ type: 'LPAREN', value: '(' })
        i++
        continue
      }
      if (ch === ')') {
        this.tokens.push({ type: 'RPAREN', value: ')' })
        i++
        continue
      }
      if (ch === ',') {
        this.tokens.push({ type: 'COMMA', value: ',' })
        i++
        continue
      }

      // 식별자·키워드
      if (/[a-zA-Z_]/.test(ch)) {
        let ident = ''
        while (i < src.length && /[a-zA-Z0-9_]/.test(src[i])) {
          ident += src[i++]
        }
        // 식별자가 끝나고 바로 공백 없이 crosses_ 같은 긴 키워드 처리
        if (ident === 'crosses_above' || ident === 'crosses_below') {
          this.tokens.push({ type: 'OP', value: ident })
          continue
        }
        if (ident === 'true' || ident === 'false') {
          this.tokens.push({ type: 'BOOLEAN', value: ident })
          continue
        }
        this.tokens.push({ type: 'IDENT', value: ident })
        continue
      }

      // unknown — skip
      i++
    }
    this.tokens.push({ type: 'EOF', value: '' })
  }

  peek(): Token {
    return this.tokens[this.current] ?? { type: 'EOF', value: '' }
  }

  consume(): Token {
    const t = this.tokens[this.current]
    this.current++
    return t ?? { type: 'EOF', value: '' }
  }

  back(): void {
    if (this.current > 0) this.current--
  }
}

// ──────────────────────────────────────────────
// 파서
// ──────────────────────────────────────────────

class ParseError extends Error {
  constructor(msg: string) {
    super(msg)
    this.name = 'ParseError'
  }
}

class Parser {
  private depth = 0
  private readonly MAX_DEPTH = 50

  constructor(private lex: Lexer) {}

  private checkDepth(): void {
    if (++this.depth > this.MAX_DEPTH) {
      throw new ParseError('DSL 표현식이 너무 복잡합니다 (최대 깊이 초과)')
    }
  }

  parse(): BlockNode {
    const node = this.parseOr()
    if (this.lex.peek().type !== 'EOF') {
      // 남은 토큰이 있으면 경고하지만 파싱 결과 반환
    }
    return node
  }

  // 1단계: OR
  private parseOr(): BlockNode {
    this.checkDepth()
    let left = this.parseAnd()

    while (this.lex.peek().type === 'IDENT' && this.lex.peek().value === 'OR') {
      this.lex.consume() // OR
      const right = this.parseAnd()
      left = {
        kind: 'logical',
        op: 'OR' as LogicalOperator,
        left,
        right,
      }
    }
    this.depth--
    return left
  }

  // 2단계: AND
  private parseAnd(): BlockNode {
    this.checkDepth()
    let left = this.parseNot()

    while (this.lex.peek().type === 'IDENT' && this.lex.peek().value === 'AND') {
      this.lex.consume() // AND
      const right = this.parseNot()
      left = {
        kind: 'logical',
        op: 'AND' as LogicalOperator,
        left,
        right,
      }
    }
    this.depth--
    return left
  }

  // 2.5단계: NOT
  private parseNot(): BlockNode {
    if (this.lex.peek().type === 'IDENT' && this.lex.peek().value === 'NOT') {
      this.lex.consume() // NOT
      // 괄호가 있으면 소비
      const hasParen = this.lex.peek().type === 'LPAREN'
      if (hasParen) this.lex.consume()
      const operand = this.parseOr()
      if (hasParen) {
        if (this.lex.peek().type !== 'RPAREN') {
          throw new ParseError('NOT 이후 닫는 괄호가 없습니다')
        }
        this.lex.consume()
      }
      return { kind: 'not', operand }
    }
    return this.parseComparison()
  }

  // 3단계: 비교 연산
  private parseComparison(): BlockNode {
    this.checkDepth()
    const left = this.parseArithmetic()

    const tok = this.lex.peek()
    if (tok.type === 'OP' && COMPARISON_OPS.has(tok.value)) {
      this.lex.consume()
      const right = this.parseArithmetic()
      this.depth--
      return {
        kind: 'comparison',
        op: tok.value as ComparisonOperator,
        left,
        right,
      }
    }
    this.depth--
    return left
  }

  // 4단계: 덧셈/뺄셈
  private parseArithmetic(): BlockNode {
    this.checkDepth()
    let left = this.parseTerm()

    while (
      this.lex.peek().type === 'OP' &&
      (this.lex.peek().value === '+' || this.lex.peek().value === '-')
    ) {
      const op = this.lex.consume().value as ArithmeticOperator
      const right = this.parseTerm()
      left = { kind: 'arithmetic', op, left, right }
    }
    this.depth--
    return left
  }

  // 5단계: 곱셈/나눗셈
  private parseTerm(): BlockNode {
    this.checkDepth()
    let left = this.parseFactor()

    while (
      this.lex.peek().type === 'OP' &&
      (this.lex.peek().value === '*' || this.lex.peek().value === '/')
    ) {
      const op = this.lex.consume().value as ArithmeticOperator
      const right = this.parseFactor()
      left = { kind: 'arithmetic', op, left, right }
    }
    this.depth--
    return left
  }

  // 6단계: 원자 (리터럴·식별자·괄호)
  private parseFactor(): BlockNode {
    this.checkDepth()
    const tok = this.lex.peek()

    // 괄호 묶음
    if (tok.type === 'LPAREN') {
      this.lex.consume()
      const inner = this.parseOr()
      if (this.lex.peek().type !== 'RPAREN') {
        throw new ParseError('닫는 괄호가 없습니다')
      }
      this.lex.consume()
      this.depth--
      return inner
    }

    // 숫자 리터럴
    if (tok.type === 'NUMBER') {
      this.lex.consume()
      const isFloat = tok.value.includes('.')
      this.depth--
      return {
        kind: 'literal',
        valueType: isFloat ? 'float' : 'integer',
        value: tok.value,
      } satisfies LiteralBlock
    }

    // boolean 리터럴
    if (tok.type === 'BOOLEAN') {
      this.lex.consume()
      this.depth--
      return {
        kind: 'literal',
        valueType: 'boolean',
        value: tok.value,
      } satisfies LiteralBlock
    }

    // 식별자 (지표·캔들·패턴·논리 키워드)
    if (tok.type === 'IDENT') {
      this.lex.consume()
      const name = tok.value

      // pattern_detected("id") 또는 consecutive("up"/"down") >= N
      if (name === 'pattern_detected') {
        return this.parsePatternDetected()
      }
      if (name === 'consecutive') {
        return this.parseConsecutive()
      }

      // CANDLE 필드
      if (CANDLE_FIELDS.has(name)) {
        this.depth--
        return { kind: 'candle', field: name as CandleBlock['field'] }
      }

      // multi-output alias (bb_upper, macd_signal 등)
      if (ALIAS_MAP[name]) {
        const aliasInfo = ALIAS_MAP[name]
        // 파라미터 있으면 소비
        const params = this.lex.peek().type === 'LPAREN' ? this.parseCallParams() : {}
        this.depth--
        return {
          kind: 'indicator',
          id: aliasInfo.id,
          params,
          outputKey: aliasInfo.outputKey,
        } satisfies IndicatorBlock
      }

      // INDICATOR_CATALOG 소속 여부 확인
      if (INDICATOR_CATALOG_IDS.has(name)) {
        const params = this.lex.peek().type === 'LPAREN' ? this.parseCallParams() : {}
        this.depth--
        return { kind: 'indicator', id: name, params } satisfies IndicatorBlock
      }

      // 논리 키워드 (IDENT 토큰으로 잡힌 경우)
      if (LOGICAL_KEYWORDS.has(name)) {
        // 이미 상위 파서에서 처리해야 하는데 여기 도달한 경우 — 에러
        throw new ParseError(`예상치 못한 논리 키워드: ${name}`)
      }

      // 미정의 식별자 — DSL 화이트리스트 위반
      this.depth--
      throw new ParseError(
        `허용되지 않는 식별자: "${name}". KIS DSL 화이트리스트 위반 (지표·캔들·패턴만 허용)`,
      )
    }

    this.depth--
    throw new ParseError(`파싱 실패: 예상치 못한 토큰 "${tok.value}" (${tok.type})`)
  }

  private parseCallParams(): IndicatorParams {
    if (this.lex.peek().type !== 'LPAREN') return {}
    this.lex.consume() // (
    const params: IndicatorParams = {}
    let idx = 0
    while (this.lex.peek().type !== 'RPAREN' && this.lex.peek().type !== 'EOF') {
      const t = this.lex.peek()
      if (t.type === 'NUMBER') {
        this.lex.consume()
        params[`p${idx}`] = t.value.includes('.') ? parseFloat(t.value) : parseInt(t.value)
        idx++
      } else if (t.type === 'IDENT') {
        // IDENTIFIER 인자 (order_block detection/range 등: engulfing·body·bos·wick)
        // backend KisYamlDslParser IDENTIFIER 인자 지원 정합 (up·down 선례)
        this.lex.consume()
        params[`p${idx}`] = t.value
        idx++
      } else if (t.type === 'STRING' || t.type === 'BOOLEAN') {
        // 문자열·불리언 리터럴 인자
        this.lex.consume()
        params[`p${idx}`] = t.value
        idx++
      } else if (t.type === 'COMMA') {
        this.lex.consume()
      } else {
        this.lex.consume() // skip unknown
      }
    }
    if (this.lex.peek().type === 'RPAREN') this.lex.consume()
    return params
  }

  private parsePatternDetected(): BlockNode {
    if (this.lex.peek().type !== 'LPAREN') {
      throw new ParseError('pattern_detected 다음에 "(" 가 필요합니다')
    }
    this.lex.consume() // (
    const strTok = this.lex.peek()
    if (strTok.type !== 'STRING') {
      throw new ParseError('pattern_detected 안에 문자열 패턴 ID가 필요합니다')
    }
    this.lex.consume()
    const patternId = strTok.value
    if (!PATTERN_CATALOG_IDS.has(patternId)) {
      throw new ParseError(
        `허용되지 않는 패턴 ID: "${patternId}". 57 패턴 카탈로그에 없습니다`,
      )
    }
    if (this.lex.peek().type !== 'RPAREN') {
      throw new ParseError('pattern_detected 닫는 괄호가 없습니다')
    }
    this.lex.consume()
    this.depth--
    return {
      kind: 'pattern',
      patternId,
      func: 'pattern_detected',
    }
  }

  private parseConsecutive(): BlockNode {
    if (this.lex.peek().type !== 'LPAREN') {
      throw new ParseError('consecutive 다음에 "(" 가 필요합니다')
    }
    this.lex.consume() // (
    const strTok = this.lex.peek()
    if (strTok.type !== 'STRING') {
      throw new ParseError('consecutive 안에 "up" 또는 "down" 이 필요합니다')
    }
    this.lex.consume()
    const dir = strTok.value
    if (dir !== 'up' && dir !== 'down') {
      throw new ParseError(`consecutive 방향이 올바르지 않습니다: "${dir}" (up 또는 down)`)
    }
    if (this.lex.peek().type !== 'RPAREN') {
      throw new ParseError('consecutive 닫는 괄호가 없습니다')
    }
    this.lex.consume()
    // >= N 처리 (있으면)
    let minCount = 3
    if (this.lex.peek().type === 'OP' && this.lex.peek().value === '>=') {
      this.lex.consume()
      const numTok = this.lex.peek()
      if (numTok.type === 'NUMBER') {
        this.lex.consume()
        minCount = parseInt(numTok.value)
      }
    }
    this.depth--
    return {
      kind: 'pattern',
      patternId: dir === 'up' ? 'consecutive_up' : 'consecutive_down',
      func: dir === 'up' ? 'consecutive_up' : 'consecutive_down',
      minCount,
    }
  }
}

// ──────────────────────────────────────────────
// 공개 API
// ──────────────────────────────────────────────

/**
 * DSL 텍스트 → BlockNode
 * 파싱 실패 시 ParseError throw
 */
export function parseDslText(text: string): BlockNode {
  const lex = new Lexer(text.trim())
  const parser = new Parser(lex)
  return parser.parse()
}

/**
 * entry/exit DSL 텍스트 쌍 → BlockTree
 * 빈 문자열은 undefined로 처리
 */
export function parseDslTextToTree(entry: string, exit?: string): BlockTree {
  return {
    entry: entry.trim() ? parseDslText(entry) : undefined,
    exit: exit?.trim() ? parseDslText(exit) : undefined,
  }
}
