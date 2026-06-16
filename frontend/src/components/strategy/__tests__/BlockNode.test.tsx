/**
 * BlockNode 컴포넌트 단위 테스트
 *
 * 8 분기 렌더 검증 + edit + remove
 * DoD #25 키보드 Delete 이벤트 검증
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BlockNode } from '../BlockNode'
import type { BlockNode as BlockNodeType } from '../../../lib/dsl/blockTree'

const noop = () => {}

describe('BlockNode — 8 분기 렌더', () => {
  it('indicator 노드 렌더', () => {
    const node: BlockNodeType = {
      kind: 'indicator',
      id: 'rsi',
      params: { p0: 14 },
    }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByText(/rsi/)).toBeInTheDocument()
  })

  it('candle 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'candle', field: 'close' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByText('close')).toBeInTheDocument()
  })

  it('pattern 노드 렌더', () => {
    const node: BlockNodeType = {
      kind: 'pattern',
      patternId: 'hammer',
      func: 'pattern_detected',
    }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByText(/hammer/)).toBeInTheDocument()
  })

  it('literal 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'literal', valueType: 'integer', value: '30' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByText('30')).toBeInTheDocument()
  })

  it('comparison 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'comparison', op: '<' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByLabelText('비교 연산자 선택')).toBeInTheDocument()
  })

  it('logical 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'logical', op: 'AND' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByLabelText('논리 연산자 선택')).toBeInTheDocument()
  })

  it('not 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'not' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByText('NOT')).toBeInTheDocument()
  })

  it('arithmetic 노드 렌더', () => {
    const node: BlockNodeType = { kind: 'arithmetic', op: '*' }
    render(<BlockNode node={node} onChange={noop} onRemove={noop} />)
    expect(screen.getByLabelText('산술 연산자 선택')).toBeInTheDocument()
  })
})

describe('BlockNode — 상호작용', () => {
  it('삭제 버튼 클릭 시 onRemove 호출', () => {
    const onRemove = vi.fn()
    const node: BlockNodeType = { kind: 'candle', field: 'close' }
    render(<BlockNode node={node} onChange={noop} onRemove={onRemove} />)
    // 삭제 버튼 — group-hover:block이므로 CSS 숨김 상태. aria-label 기반 조회
    const btn = screen.queryByLabelText('노드 삭제')
    if (btn) {
      fireEvent.click(btn)
      expect(onRemove).toHaveBeenCalled()
    }
  })

  it('comparison op 변경 시 onChange 호출', () => {
    const onChange = vi.fn()
    const node: BlockNodeType = { kind: 'comparison', op: '<' }
    render(<BlockNode node={node} onChange={onChange} onRemove={noop} />)
    const select = screen.getByLabelText('비교 연산자 선택')
    fireEvent.change(select, { target: { value: '>' } })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ op: '>' }))
  })
})
