/**
 * BlockTree 타입 정의 — DSL AST 1:1 매핑
 *
 * ADR 0055 B+C-lite 하이브리드: 트리 폼 빌더 + DSL 텍스트 토글
 * backend DslStrategyDefinition / KisYamlDslParser 6단 파싱 구조와 1:1 정합
 *
 * 8종 discriminated union:
 *   IndicatorBlock  — ma(14), rsi(14), bb(20,2) 등 지표 호출
 *   CandleBlock     — open, high, low, close, prev_close, volume 직접 참조
 *   PatternBlock    — pattern_detected("hammer") 등 패턴 조건
 *   LiteralBlock    — 숫자·문자열·boolean 리터럴
 *   ComparisonBlock — left op right (>, <, >=, <=, ==, !=, crosses_above, crosses_below)
 *   LogicalBlock    — AND | OR 이항 논리 결합
 *   NotBlock        — NOT 단항 부정
 *   ArithmeticBlock — +, -, *, / 산술 표현식
 *
 * 룰 5: LLM provider import 0건
 */

/** 지표 파라미터 — key:value (예: { period: "14" }) */
export type IndicatorParams = Record<string, string | number>

/** IndicatorBlock — 지표 함수 호출 (예: rsi(14), ma(5)) */
export interface IndicatorBlock {
  kind: 'indicator'
  /** INDICATOR_CATALOG.id (예: "rsi", "ma", "bb") */
  id: string
  params: IndicatorParams
  /**
   * multi-output 지표에서 특정 output 선택
   * (예: bb → "upper" | "middle" | "lower")
   */
  outputKey?: string
}

/** backend KisYamlDslParser의 캔들 직접 접근 토큰 */
export type CandleField = 'open' | 'high' | 'low' | 'close' | 'prev_close' | 'volume'

/** CandleBlock — 캔들 직접 참조 */
export interface CandleBlock {
  kind: 'candle'
  /** "open" | "high" | "low" | "close" | "prev_close" | "volume" */
  field: CandleField
}

/** PatternBlock — 패턴 감지 조건 */
export interface PatternBlock {
  kind: 'pattern'
  /** PATTERN_CATALOG.id (예: "hammer", "doji") */
  patternId: string
  /**
   * 패턴 함수 형태:
   *   "pattern_detected" → pattern_detected("hammer")
   *   "consecutive_up"   → consecutive("up")
   *   "consecutive_down" → consecutive("down")
   */
  func: 'pattern_detected' | 'consecutive_up' | 'consecutive_down'
  /** consecutive인 경우 최소 횟수 (예: >= 3) */
  minCount?: number
}

/** LiteralBlock — 상수값 */
export interface LiteralBlock {
  kind: 'literal'
  /** "integer" | "float" | "string" | "boolean" */
  valueType: 'integer' | 'float' | 'string' | 'boolean'
  value: string
}

/** 비교 연산자 */
export type ComparisonOperator =
  | '>'
  | '<'
  | '>='
  | '<='
  | '=='
  | '!='
  | 'crosses_above'
  | 'crosses_below'

/** ComparisonBlock — 비교 조건 노드 */
export interface ComparisonBlock {
  kind: 'comparison'
  op: ComparisonOperator
  left?: BlockNode
  right?: BlockNode
}

/** 논리 연산자 */
export type LogicalOperator = 'AND' | 'OR'

/** LogicalBlock — AND | OR 이항 결합 */
export interface LogicalBlock {
  kind: 'logical'
  op: LogicalOperator
  left?: BlockNode
  right?: BlockNode
}

/** NotBlock — NOT 단항 부정 */
export interface NotBlock {
  kind: 'not'
  operand?: BlockNode
}

/** 산술 연산자 */
export type ArithmeticOperator = '+' | '-' | '*' | '/'

/** ArithmeticBlock — 산술 표현식 */
export interface ArithmeticBlock {
  kind: 'arithmetic'
  op: ArithmeticOperator
  left?: BlockNode
  right?: BlockNode
}

/** BlockNode — 8종 discriminated union */
export type BlockNode =
  | IndicatorBlock
  | CandleBlock
  | PatternBlock
  | LiteralBlock
  | ComparisonBlock
  | LogicalBlock
  | NotBlock
  | ArithmeticBlock

/**
 * BlockTree — entry / exit 두 루트로 구성
 * entry: 매수 조건 (buy_condition)
 * exit:  매도 조건 (sell_condition) — optional
 */
export interface BlockTree {
  entry?: BlockNode
  exit?: BlockNode
}

// ──────────────────────────────────────────────
// 트리 조작 헬퍼
// ──────────────────────────────────────────────

/**
 * BlockNode 깊은 복사 (불변 상태 업데이트용)
 */
export function cloneNode(node: BlockNode): BlockNode {
  return JSON.parse(JSON.stringify(node)) as BlockNode
}

/**
 * 트리에서 특정 노드를 새 노드로 교체
 * targetNode: 참조 비교로 찾음
 * 반환: 변경된 루트 (원본 불변)
 */
export function replaceNode(
  root: BlockNode,
  targetNode: BlockNode,
  replacement: BlockNode,
): BlockNode {
  if (root === targetNode) return replacement

  switch (root.kind) {
    case 'comparison':
      return {
        ...root,
        left: root.left ? replaceNode(root.left, targetNode, replacement) : undefined,
        right: root.right ? replaceNode(root.right, targetNode, replacement) : undefined,
      }
    case 'logical':
      return {
        ...root,
        left: root.left ? replaceNode(root.left, targetNode, replacement) : undefined,
        right: root.right ? replaceNode(root.right, targetNode, replacement) : undefined,
      }
    case 'not':
      return {
        ...root,
        operand: root.operand ? replaceNode(root.operand, targetNode, replacement) : undefined,
      }
    case 'arithmetic':
      return {
        ...root,
        left: root.left ? replaceNode(root.left, targetNode, replacement) : undefined,
        right: root.right ? replaceNode(root.right, targetNode, replacement) : undefined,
      }
    default:
      return root
  }
}

/**
 * 트리에서 특정 노드를 제거 (undefined로 교체)
 */
export function removeNode(root: BlockNode, targetNode: BlockNode): BlockNode | undefined {
  if (root === targetNode) return undefined

  switch (root.kind) {
    case 'comparison':
      return {
        ...root,
        left:
          root.left
            ? (root.left === targetNode ? undefined : removeNode(root.left, targetNode))
            : undefined,
        right:
          root.right
            ? (root.right === targetNode ? undefined : removeNode(root.right, targetNode))
            : undefined,
      }
    case 'logical':
      return {
        ...root,
        left:
          root.left
            ? (root.left === targetNode ? undefined : removeNode(root.left, targetNode))
            : undefined,
        right:
          root.right
            ? (root.right === targetNode ? undefined : removeNode(root.right, targetNode))
            : undefined,
      }
    case 'not':
      return {
        ...root,
        operand:
          root.operand
            ? (root.operand === targetNode ? undefined : removeNode(root.operand, targetNode))
            : undefined,
      }
    case 'arithmetic':
      return {
        ...root,
        left:
          root.left
            ? (root.left === targetNode ? undefined : removeNode(root.left, targetNode))
            : undefined,
        right:
          root.right
            ? (root.right === targetNode ? undefined : removeNode(root.right, targetNode))
            : undefined,
      }
    default:
      return root
  }
}

/**
 * 빈 트리 여부 확인
 */
export function isEmptyTree(tree: BlockTree): boolean {
  return tree.entry === undefined && tree.exit === undefined
}

/**
 * 새 ComparisonBlock 템플릿 생성 (초기 빈 상태)
 */
export function newComparisonBlock(op: ComparisonOperator = '>'): ComparisonBlock {
  return { kind: 'comparison', op }
}

/**
 * 새 LogicalBlock 템플릿 생성
 */
export function newLogicalBlock(op: LogicalOperator = 'AND'): LogicalBlock {
  return { kind: 'logical', op }
}

/**
 * 새 NotBlock 템플릿 생성
 */
export function newNotBlock(): NotBlock {
  return { kind: 'not' }
}

/**
 * 새 ArithmeticBlock 템플릿 생성
 */
export function newArithmeticBlock(op: ArithmeticOperator = '*'): ArithmeticBlock {
  return { kind: 'arithmetic', op }
}
