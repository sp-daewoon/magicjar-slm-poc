/**
 * OrderTriggerBadge 단위 테스트 (ADR 0115 §2-B 키 사슬 무결성 — 불변식 #1·#4)
 *
 * 검증:
 *  1. 6 trigger 값 각각 한국어 라벨 + 색 토큰 매핑
 *  2. legacy(null) → "미분류" 점선 회색
 *  3. triggerRefId hover title 노출 (사인·전략 역추적)
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { OrderTriggerBadge } from '../OrderTriggerBadge'

describe('OrderTriggerBadge', () => {
  it('STRATEGY_SIGNAL → "전략 사인" 파랑 + 사인 ref', () => {
    render(<OrderTriggerBadge trigger="STRATEGY_SIGNAL" triggerRefId={709} />)
    const el = screen.getByText('전략 사인')
    expect(el.className).toContain('bg-blue-100')
    // ref 키 노출
    expect(screen.getByText('#709')).toBeInTheDocument()
    expect(el.closest('span')).toHaveAttribute('title', '사인 #709')
  })

  it('AUTO_EXIT → "자동 익절" 노랑', () => {
    render(<OrderTriggerBadge trigger="AUTO_EXIT" />)
    expect(screen.getByText('자동 익절').className).toContain('bg-yellow-100')
  })

  it('STOP_LOSS → "손절" 빨강', () => {
    render(<OrderTriggerBadge trigger="STOP_LOSS" />)
    expect(screen.getByText('손절').className).toContain('bg-red-100')
  })

  it('VI_AUTO_EXIT → "VI 자동청산" 자주', () => {
    render(<OrderTriggerBadge trigger="VI_AUTO_EXIT" />)
    expect(screen.getByText('VI 자동청산').className).toContain('bg-rose-100')
  })

  it('PARTIAL_TAKE_PROFIT → "분할 익절" 주황', () => {
    render(<OrderTriggerBadge trigger="PARTIAL_TAKE_PROFIT" />)
    expect(screen.getByText('분할 익절').className).toContain('bg-orange-100')
  })

  it('MANUAL → "수동" 회색', () => {
    render(<OrderTriggerBadge trigger="MANUAL" />)
    expect(screen.getByText('수동').className).toContain('bg-gray-100')
  })

  it('EXTERNAL → "외부" 보라', () => {
    render(<OrderTriggerBadge trigger="EXTERNAL" />)
    expect(screen.getByText('외부').className).toContain('bg-purple-100')
  })

  it('null trigger → "미분류" 점선 회색 + legacy title', () => {
    render(<OrderTriggerBadge trigger={null} />)
    const el = screen.getByText('미분류')
    expect(el.className).toContain('border-dashed')
    expect(el).toHaveAttribute('title', '발사 출처 미분류 (V80 이전 legacy 주문)')
  })

  it('refLabel 우선 — title 에 전략명·사인 id 노출', () => {
    render(
      <OrderTriggerBadge trigger="STRATEGY_SIGNAL" triggerRefId={42} refLabel="이평선 돌파 · 사인 #42" />,
    )
    expect(screen.getByText('전략 사인')).toHaveAttribute('title', '이평선 돌파 · 사인 #42')
  })
})
