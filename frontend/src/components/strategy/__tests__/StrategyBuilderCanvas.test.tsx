/**
 * StrategyBuilderCanvas 단위 테스트
 *
 * 검증:
 * - 트리 없을 때 빈 상태 안내 메시지
 * - DSL 미리보기 갱신 (트리 → DSL 텍스트)
 * - entry/exit 탭 전환
 * - 검증 버튼 disabled 조건 (빈 entry)
 * - 저장 버튼 disabled 조건 (빈 entry)
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { StrategyBuilderCanvas } from '../StrategyBuilderCanvas'
import type { BlockTree } from '../../../lib/dsl/blockTree'
import { SMART_MONEY_STRATEGY_PRESETS } from '../../../lib/dsl/smartMoneyPresets'

const noop = () => {}

const mockTree: BlockTree = {
  entry: {
    kind: 'comparison',
    op: '<',
    left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
    right: { kind: 'literal', valueType: 'integer', value: '30' },
  },
}

describe('StrategyBuilderCanvas', () => {
  it('빈 트리 — 빈 상태 안내 표시', () => {
    render(
      <StrategyBuilderCanvas
        tree={{}}
        onTreeChange={noop}
      />,
    )
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('트리 있을 때 DSL 미리보기 표시', () => {
    render(
      <StrategyBuilderCanvas
        tree={mockTree}
        onTreeChange={noop}
      />,
    )
    // DSL 미리보기 textarea에 rsi 표현이 있어야 함
    const preview = screen.getByLabelText('DSL 미리보기 (읽기 전용)')
    expect((preview as HTMLTextAreaElement).value).toContain('rsi')
  })

  it('entry/exit 탭 전환', () => {
    render(
      <StrategyBuilderCanvas
        tree={mockTree}
        onTreeChange={noop}
      />,
    )
    const exitTab = screen.getByText('매도 조건 (Exit)')
    fireEvent.click(exitTab)
    // exit 탭으로 전환 후 빈 상태 표시
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('빈 entry 시 검증 버튼 disabled', () => {
    render(
      <StrategyBuilderCanvas
        tree={{}}
        onTreeChange={noop}
        onValidate={noop}
      />,
    )
    const validateBtn = screen.getByText('검증')
    expect(validateBtn).toBeDisabled()
  })

  it('검증 버튼 클릭 시 onValidate 호출', () => {
    const onValidate = vi.fn()
    render(
      <StrategyBuilderCanvas
        tree={mockTree}
        onTreeChange={noop}
        onValidate={onValidate}
      />,
    )
    const validateBtn = screen.getByText('검증')
    expect(validateBtn).not.toBeDisabled()
    fireEvent.click(validateBtn)
    expect(onValidate).toHaveBeenCalled()
  })

  it('strategyId 없을 때 평가 버튼 disabled', () => {
    render(
      <StrategyBuilderCanvas
        tree={mockTree}
        onTreeChange={noop}
      />,
    )
    const evalBtn = screen.getByText('평가')
    expect(evalBtn).toBeDisabled()
  })

  it('strategyId 있을 때 평가 버튼 활성화', () => {
    render(
      <StrategyBuilderCanvas
        tree={mockTree}
        onTreeChange={noop}
        strategyId="test-id-123"
      />,
    )
    const evalBtn = screen.getByText('평가')
    expect(evalBtn).not.toBeDisabled()
  })

  it('프리셋 후보 버튼 클릭 시 onPresetApply 호출', () => {
    const onPresetApply = vi.fn()
    render(
      <StrategyBuilderCanvas
        tree={{}}
        onTreeChange={noop}
        presets={SMART_MONEY_STRATEGY_PRESETS.slice(0, 1)}
        onPresetApply={onPresetApply}
      />,
    )

    fireEvent.click(screen.getByText('FVG 매수 후보'))
    expect(onPresetApply).toHaveBeenCalledWith(SMART_MONEY_STRATEGY_PRESETS[0])
  })
})
