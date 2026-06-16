/**
 * AiCard 상태 머신 분기 단위 테스트 — T-AI R2 frontend.
 *
 * 6 상태 분기:
 * - IDLE       → trigger 버튼만
 * - REQUESTING → loading
 * - WAITING    → "AI 응답 대기 중"
 * - RECEIVED   → markdown + meta + advisory only 안내
 * - REFUSED    → 한국어 사유
 * - ERROR      → 재시도 버튼
 * - TIMEOUT    → 재시도 버튼
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AiCard } from '../components/AiCard'
import type { AiResponseEnvelope } from '../../../api/ai'

function noop() {
  /* test stub */
}

const envelope: AiResponseEnvelope = {
  eventId: 'evt-1',
  requestId: 'req-12345678-9abc-def0',
  userId: 1,
  commentType: 'SYMBOL',
  provider: 'anthropic',
  model: 'claude-sonnet-4-5',
  promptTemplate: 'symbol_v1',
  content: `## 결론 요약\n\n**삼성전자**는 안정적입니다.`,
  tokensIn: 120,
  tokensOut: 200,
  latencyMs: 1500,
  costUsd: '0.012345',
  cached: false,
  graceful: false,
  occurredAt: '2026-05-24T10:00:00Z',
  schemaVersion: 1,
}

describe('AiCard 상태 분기', () => {
  it('IDLE — trigger 버튼만 표시 + 한국어 라벨', () => {
    const trigger = vi.fn()
    render(
      <AiCard type="SYMBOL" state={{ status: 'IDLE' }} onTrigger={trigger} onReset={noop} />,
    )
    const btn = screen.getByLabelText(/AI 종목 분석 요청/)
    fireEvent.click(btn)
    expect(trigger).toHaveBeenCalledTimes(1)
  })

  it('REQUESTING — "요청 중" 안내', () => {
    render(
      <AiCard
        type="MARKET"
        state={{ status: 'REQUESTING' }}
        onTrigger={noop}
        onReset={noop}
      />,
    )
    expect(screen.getByText(/요청 중/)).toBeInTheDocument()
  })

  it('WAITING — "AI 응답 대기 중" + requestId prefix', () => {
    render(
      <AiCard
        type="CHART"
        state={{ status: 'WAITING', requestId: 'req-12345678-abcd' }}
        onTrigger={noop}
        onReset={noop}
      />,
    )
    expect(screen.getByText(/대기 중/)).toBeInTheDocument()
    expect(screen.getByText(/req-1234/)).toBeInTheDocument()
  })

  it('RECEIVED — markdown 본문 + provider/model meta + advisory only 안내', () => {
    render(
      <AiCard
        type="SYMBOL"
        state={{ status: 'RECEIVED', envelope }}
        onTrigger={noop}
        onReset={noop}
      />,
    )
    // markdown rendered — h3 헤더
    expect(screen.getByRole('heading', { level: 3, name: '결론 요약' })).toBeInTheDocument()
    expect(screen.getByText(/삼성전자/)).toBeInTheDocument()
    // meta bar
    expect(screen.getByText(/anthropic/)).toBeInTheDocument()
    expect(screen.getByText(/claude-sonnet-4-5/)).toBeInTheDocument()
    // advisory only 한국어 안내
    expect(screen.getByText(/매매 결정은 직접 확인/)).toBeInTheDocument()
  })

  it('RECEIVED + cached=true → cache 배지', () => {
    render(
      <AiCard
        type="SYMBOL"
        state={{ status: 'RECEIVED', envelope: { ...envelope, cached: true } }}
        onTrigger={noop}
        onReset={noop}
      />,
    )
    expect(screen.getByText('cache')).toBeInTheDocument()
  })

  it('REFUSED — 한국어 메시지 + refuseCode 표시', () => {
    render(
      <AiCard
        type="STRATEGY"
        state={{
          status: 'REFUSED',
          refuseCode: 'AI_DISABLED',
          userMessage: 'AI 기능이 비활성 상태입니다.',
        }}
        onTrigger={noop}
        onReset={noop}
      />,
    )
    expect(screen.getByText(/비활성 상태/)).toBeInTheDocument()
    expect(screen.getByText(/AI_DISABLED/)).toBeInTheDocument()
  })

  it('ERROR — 메시지 + 재시도 버튼', () => {
    const trigger = vi.fn()
    render(
      <AiCard
        type="SIGNAL"
        state={{ status: 'ERROR', message: '네트워크 오류' }}
        onTrigger={trigger}
        onReset={noop}
      />,
    )
    expect(screen.getByText('네트워크 오류')).toBeInTheDocument()
    fireEvent.click(screen.getByText('다시 시도'))
    expect(trigger).toHaveBeenCalledTimes(1)
  })

  it('TIMEOUT — 장기 지연 안내 + 재시도', () => {
    const trigger = vi.fn()
    render(
      <AiCard
        type="MARKET"
        state={{ status: 'TIMEOUT', requestId: 'req-xyz' }}
        onTrigger={trigger}
        onReset={noop}
      />,
    )
    expect(screen.getByText(/5분 이상 지연/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('다시 시도'))
    expect(trigger).toHaveBeenCalledTimes(1)
  })

  it('IDLE + disabled=true → 버튼 비활성', () => {
    render(
      <AiCard
        type="SYMBOL"
        state={{ status: 'IDLE' }}
        onTrigger={noop}
        onReset={noop}
        disabled
        disabledMessage="셋업 완료 후 사용 가능"
      />,
    )
    const btn = screen.getByLabelText(/AI 종목 분석 요청/)
    expect(btn).toBeDisabled()
    expect(btn.getAttribute('title')).toBe('셋업 완료 후 사용 가능')
  })

  it('RECEIVED — close 버튼 클릭 시 reset 호출', () => {
    const reset = vi.fn()
    render(
      <AiCard
        type="SYMBOL"
        state={{ status: 'RECEIVED', envelope }}
        onTrigger={noop}
        onReset={reset}
      />,
    )
    fireEvent.click(screen.getByLabelText('AI 카드 닫기'))
    expect(reset).toHaveBeenCalledTimes(1)
  })
})
