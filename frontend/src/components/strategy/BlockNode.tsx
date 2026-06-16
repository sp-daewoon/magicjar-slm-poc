/**
 * BlockNode — 트리 노드 재귀 컴포넌트
 *
 * 8 분기:
 *   indicator  — 지표 함수 호출 (파라미터 인라인 편집)
 *   candle     — 캔들 직접 참조
 *   pattern    — 패턴 감지 조건
 *   literal    — 상수값 인라인 편집
 *   comparison — 비교 조건 (left op right)
 *   logical    — AND | OR 이항 결합
 *   not        — NOT 단항 부정
 *   arithmetic — 산술 표현식
 *
 * 키보드 접근성 (DoD #25):
 *   Tab 탐색 + Enter/Space → 편집 모드 + Delete 제거
 *
 * ADR 0055 B+C-lite 하이브리드 — 의존 추가 0건
 * 룰 5: LLM provider import 0건
 */
import { useState, useRef, type KeyboardEvent } from 'react'
import type {
  ArithmeticOperator,
  BlockNode as BlockNodeType,
  ComparisonOperator,
  LogicalOperator,
} from '../../lib/dsl/blockTree'
import { INDICATOR_CATALOG_MAP } from '../../api/indicator'
import { PATTERN_CATALOG_MAP } from '../../api/patterns'

interface BlockNodeProps {
  node: BlockNodeType
  onChange: (updated: BlockNodeType) => void
  onRemove: () => void
  /** 재귀 깊이 (들여쓰기용) */
  depth?: number
}

const DEPTH_INDENT = 16 // px per depth level

const NODE_BASE =
  'group relative rounded-lg border bg-gray-900 text-sm transition-colors focus-within:border-blue-500 focus-within:ring-1 focus-within:ring-blue-500'
const REMOVE_BTN =
  'absolute right-1 top-1 hidden rounded p-0.5 text-gray-600 hover:bg-gray-800 hover:text-red-400 group-hover:block focus:block focus:outline-none'
const PLACEHOLDER_BTN =
  'flex min-h-[32px] cursor-pointer items-center justify-center rounded-lg border border-dashed border-gray-700 px-3 py-1 text-xs text-gray-500 hover:border-gray-500 hover:text-gray-400 focus:outline-none focus:ring-1 focus:ring-blue-400'

function PlaceholderSlot({
  label,
  onKeyDown,
}: {
  label: string
  onKeyDown?: (e: KeyboardEvent<HTMLButtonElement>) => void
}) {
  return (
    <button
      className={PLACEHOLDER_BTN}
      tabIndex={0}
      aria-label={`${label} 슬롯 비어있음 — Enter 또는 팔레트에서 선택`}
      onKeyDown={onKeyDown}
    >
      {label}
    </button>
  )
}

export function BlockNode({ node, onChange, onRemove, depth = 0 }: BlockNodeProps) {
  const paddingLeft = depth * DEPTH_INDENT

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if ((e.target as HTMLElement).tagName !== 'INPUT') {
        e.preventDefault()
        onRemove()
      }
    }
  }

  return (
    <div
      style={{ paddingLeft }}
      className="relative"
      onKeyDown={handleKeyDown}
      role="treeitem"
      aria-label={`블록 노드: ${node.kind}`}
    >
      {node.kind === 'indicator' && (
        <IndicatorNodeView
          node={node}
          onChange={onChange}
          onRemove={onRemove}
        />
      )}
      {node.kind === 'candle' && (
        <CandleNodeView node={node} onChange={onChange} onRemove={onRemove} />
      )}
      {node.kind === 'pattern' && (
        <PatternNodeView node={node} onChange={onChange} onRemove={onRemove} />
      )}
      {node.kind === 'literal' && (
        <LiteralNodeView node={node} onChange={onChange} onRemove={onRemove} />
      )}
      {node.kind === 'comparison' && (
        <ComparisonNodeView
          node={node}
          onChange={onChange}
          onRemove={onRemove}
          depth={depth}
        />
      )}
      {node.kind === 'logical' && (
        <LogicalNodeView
          node={node}
          onChange={onChange}
          onRemove={onRemove}
          depth={depth}
        />
      )}
      {node.kind === 'not' && (
        <NotNodeView node={node} onChange={onChange} onRemove={onRemove} depth={depth} />
      )}
      {node.kind === 'arithmetic' && (
        <ArithmeticNodeView
          node={node}
          onChange={onChange}
          onRemove={onRemove}
          depth={depth}
        />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// 지표 노드
// ─────────────────────────────────────────────

function IndicatorNodeView({
  node,
  onChange,
  onRemove,
}: {
  node: Extract<BlockNodeType, { kind: 'indicator' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
}) {
  const meta = INDICATOR_CATALOG_MAP.get(node.id)
  const [editingParams, setEditingParams] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const paramStr = Object.values(node.params).join(', ')
  const outputKey = node.outputKey ?? meta?.outputKeys[0]
  const displayStr = paramStr ? `${node.id}(${paramStr})` : node.id

  return (
    <div className={[NODE_BASE, 'border-blue-900/50 px-3 py-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="flex items-center gap-2">
        <span className="rounded bg-blue-900/40 px-1.5 py-0.5 font-mono text-xs text-blue-300">
          지표
        </span>
        {editingParams ? (
          <input
            ref={inputRef}
            autoFocus
            defaultValue={paramStr}
            className="flex-1 rounded border border-blue-700 bg-gray-900 px-2 py-0.5 font-mono text-xs text-white focus:outline-none"
            onBlur={(e) => {
              const parts = e.target.value.split(',').map((s) => s.trim()).filter(Boolean)
              const newParams = Object.fromEntries(parts.map((v, i) => [`p${i}`, v]))
              onChange({ ...node, params: newParams })
              setEditingParams(false)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') inputRef.current?.blur()
              if (e.key === 'Escape') setEditingParams(false)
            }}
          />
        ) : (
          <button
            className="flex-1 text-left font-mono text-sm text-white hover:text-blue-300 focus:outline-none"
            onClick={() => setEditingParams(true)}
            onKeyDown={(e) => e.key === 'Enter' && setEditingParams(true)}
            tabIndex={0}
            aria-label={`${displayStr} 파라미터 편집`}
          >
            {displayStr}
          </button>
        )}
        {meta && (
          <span className="text-xs text-gray-500">{meta.name}</span>
        )}
        {meta?.multiOutput && (
          <select
            value={outputKey}
            onChange={(e) => onChange({ ...node, outputKey: e.target.value })}
            className="rounded border border-gray-700 bg-gray-950 px-1.5 py-0.5 text-xs text-gray-200 focus:border-blue-500 focus:outline-none"
            aria-label={`${node.id} 출력 선택`}
          >
            {meta.outputKeys.map((key) => (
              <option key={key} value={key}>
                {key}
              </option>
            ))}
          </select>
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 캔들 노드
// ─────────────────────────────────────────────

function CandleNodeView({
  node,
  onRemove,
}: {
  node: Extract<BlockNodeType, { kind: 'candle' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
}) {
  const LABEL = {
    close: '종가',
    prev_close: '전일 종가',
    open: '시가',
    high: '고가',
    low: '저가',
    volume: '거래량',
  }
  return (
    <div className={[NODE_BASE, 'border-green-900/50 px-3 py-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="flex items-center gap-2">
        <span className="rounded bg-green-900/40 px-1.5 py-0.5 font-mono text-xs text-green-300">
          캔들
        </span>
        <span className="font-mono text-sm text-white">{node.field}</span>
        <span className="text-xs text-gray-500">{LABEL[node.field]}</span>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 패턴 노드
// ─────────────────────────────────────────────

function PatternNodeView({
  node,
  onRemove,
}: {
  node: Extract<BlockNodeType, { kind: 'pattern' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
}) {
  const meta = PATTERN_CATALOG_MAP.get(node.patternId)
  const label =
    node.func === 'pattern_detected'
      ? `pattern_detected("${node.patternId}")`
      : `consecutive("${node.func === 'consecutive_up' ? 'up' : 'down'}") >= ${node.minCount ?? 3}`

  return (
    <div className={[NODE_BASE, 'border-purple-900/50 px-3 py-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="flex items-center gap-2">
        <span className="rounded bg-purple-900/40 px-1.5 py-0.5 font-mono text-xs text-purple-300">
          패턴
        </span>
        <span className="font-mono text-sm text-white">{label}</span>
        {meta && <span className="text-xs text-gray-500">{meta.nameKo}</span>}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 리터럴 노드
// ─────────────────────────────────────────────

function LiteralNodeView({
  node,
  onChange,
  onRemove,
}: {
  node: Extract<BlockNodeType, { kind: 'literal' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
}) {
  const [editing, setEditing] = useState(false)

  return (
    <div className={[NODE_BASE, 'border-yellow-900/50 px-3 py-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="flex items-center gap-2">
        <span className="rounded bg-yellow-900/40 px-1.5 py-0.5 font-mono text-xs text-yellow-300">
          상수
        </span>
        {editing ? (
          <input
            autoFocus
            defaultValue={node.value}
            className="w-24 rounded border border-yellow-700 bg-gray-900 px-2 py-0.5 font-mono text-xs text-white focus:outline-none"
            onBlur={(e) => {
              const v = e.target.value.trim()
              const isFloat = v.includes('.')
              onChange({
                ...node,
                value: v,
                valueType: isFloat ? 'float' : 'integer',
              })
              setEditing(false)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
              if (e.key === 'Escape') setEditing(false)
            }}
          />
        ) : (
          <button
            className="font-mono text-sm text-yellow-200 hover:text-yellow-100 focus:outline-none"
            onClick={() => setEditing(true)}
            tabIndex={0}
            aria-label={`상수값 ${node.value} 편집`}
          >
            {node.value}
          </button>
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 비교 노드
// ─────────────────────────────────────────────

const COMPARISON_OPS: ComparisonOperator[] = ['>', '<', '>=', '<=', '==', '!=', 'crosses_above', 'crosses_below']

function ComparisonNodeView({
  node,
  onChange,
  onRemove,
  depth,
}: {
  node: Extract<BlockNodeType, { kind: 'comparison' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
  depth: number
}) {
  return (
    <div className={[NODE_BASE, 'border-orange-900/50 p-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="mb-1 flex items-center gap-2 px-1">
        <span className="rounded bg-orange-900/40 px-1.5 py-0.5 font-mono text-xs text-orange-300">
          비교
        </span>
        <select
          value={node.op}
          onChange={(e) => onChange({ ...node, op: e.target.value as ComparisonOperator })}
          className="rounded border border-gray-700 bg-gray-900 px-2 py-0.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-400"
          aria-label="비교 연산자 선택"
        >
          {COMPARISON_OPS.map((op) => (
            <option key={op} value={op}>{op}</option>
          ))}
        </select>
      </div>
      <div className="space-y-1 pl-2">
        <div className="text-xs text-gray-500">Left</div>
        {node.left ? (
          <BlockNode
            node={node.left}
            onChange={(updated) => onChange({ ...node, left: updated })}
            onRemove={() => onChange({ ...node, left: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Left 슬롯" />
        )}
        <div className="text-xs text-gray-500">Right</div>
        {node.right ? (
          <BlockNode
            node={node.right}
            onChange={(updated) => onChange({ ...node, right: updated })}
            onRemove={() => onChange({ ...node, right: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Right 슬롯" />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 논리 노드 (AND|OR)
// ─────────────────────────────────────────────

function LogicalNodeView({
  node,
  onChange,
  onRemove,
  depth,
}: {
  node: Extract<BlockNodeType, { kind: 'logical' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
  depth: number
}) {
  return (
    <div className={[NODE_BASE, 'border-cyan-900/50 p-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="mb-1 flex items-center gap-2 px-1">
        <span className="rounded bg-cyan-900/40 px-1.5 py-0.5 font-mono text-xs text-cyan-300">
          논리
        </span>
        <select
          value={node.op}
          onChange={(e) => onChange({ ...node, op: e.target.value as LogicalOperator })}
          className="rounded border border-gray-700 bg-gray-900 px-2 py-0.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-400"
          aria-label="논리 연산자 선택"
        >
          <option value="AND">AND</option>
          <option value="OR">OR</option>
        </select>
      </div>
      <div className="space-y-1 pl-2">
        <div className="text-xs text-gray-500">Left 조건</div>
        {node.left ? (
          <BlockNode
            node={node.left}
            onChange={(updated) => onChange({ ...node, left: updated })}
            onRemove={() => onChange({ ...node, left: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Left 조건" />
        )}
        <div className="text-xs text-gray-500">Right 조건</div>
        {node.right ? (
          <BlockNode
            node={node.right}
            onChange={(updated) => onChange({ ...node, right: updated })}
            onRemove={() => onChange({ ...node, right: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Right 조건" />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// NOT 노드
// ─────────────────────────────────────────────

function NotNodeView({
  node,
  onChange,
  onRemove,
  depth,
}: {
  node: Extract<BlockNodeType, { kind: 'not' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
  depth: number
}) {
  return (
    <div className={[NODE_BASE, 'border-red-900/50 p-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="mb-1 flex items-center gap-2 px-1">
        <span className="rounded bg-red-900/40 px-1.5 py-0.5 font-mono text-xs text-red-300">
          NOT
        </span>
      </div>
      <div className="space-y-1 pl-2">
        {node.operand ? (
          <BlockNode
            node={node.operand}
            onChange={(updated) => onChange({ ...node, operand: updated })}
            onRemove={() => onChange({ ...node, operand: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="부정할 조건" />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// 산술 노드
// ─────────────────────────────────────────────

function ArithmeticNodeView({
  node,
  onChange,
  onRemove,
  depth,
}: {
  node: Extract<BlockNodeType, { kind: 'arithmetic' }>
  onChange: (n: BlockNodeType) => void
  onRemove: () => void
  depth: number
}) {
  return (
    <div className={[NODE_BASE, 'border-pink-900/50 p-2'].join(' ')} tabIndex={0}>
      <button className={REMOVE_BTN} onClick={onRemove} aria-label="노드 삭제" tabIndex={0}>
        ×
      </button>
      <div className="mb-1 flex items-center gap-2 px-1">
        <span className="rounded bg-pink-900/40 px-1.5 py-0.5 font-mono text-xs text-pink-300">
          산술
        </span>
        <select
          value={node.op}
          onChange={(e) => onChange({ ...node, op: e.target.value as ArithmeticOperator })}
          className="rounded border border-gray-700 bg-gray-900 px-2 py-0.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-400"
          aria-label="산술 연산자 선택"
        >
          {['+', '-', '*', '/'].map((op) => (
            <option key={op} value={op}>{op}</option>
          ))}
        </select>
      </div>
      <div className="space-y-1 pl-2">
        <div className="text-xs text-gray-500">Left</div>
        {node.left ? (
          <BlockNode
            node={node.left}
            onChange={(updated) => onChange({ ...node, left: updated })}
            onRemove={() => onChange({ ...node, left: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Left" />
        )}
        <div className="text-xs text-gray-500">Right</div>
        {node.right ? (
          <BlockNode
            node={node.right}
            onChange={(updated) => onChange({ ...node, right: updated })}
            onRemove={() => onChange({ ...node, right: undefined })}
            depth={depth + 1}
          />
        ) : (
          <PlaceholderSlot label="Right" />
        )}
      </div>
    </div>
  )
}
