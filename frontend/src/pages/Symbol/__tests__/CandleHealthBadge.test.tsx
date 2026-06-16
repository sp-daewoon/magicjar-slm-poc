/**
 * CandleHealthBadge — 캔들 수집 건강도 1줄 표시 테스트 (readiness P1)
 *
 * 검증 범위:
 * - 각 reason별 렌더 (reasonDetail 1줄 + tone)
 * - MARKET_CLOSED는 STALE이어도 중립 tone (정상 장외)
 * - NO_DATA → red / STALE → amber / FRESH → 작은 점
 * - 방어적 파싱: 모르는 reason이어도 reasonDetail 원문 표시
 * - 조회 실패(isError) → "확인 불가" (가짜 FRESH 금지)
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CandleHealthBadge } from '../CandleHealthBadge'
import type { CandleHealthDto } from '../../../api/symbol'

function makeHealth(overrides: Partial<CandleHealthDto>): CandleHealthDto {
  return {
    code: '005930',
    interval: '1m',
    lastCandleAt: '2026-06-05T05:00:00+09:00',
    staleSeconds: 0,
    status: 'FRESH',
    reason: 'OK_REALTIME',
    reasonDetail: '실시간 수집 정상',
    checkedAt: '2026-06-05T05:01:00+09:00',
    ...overrides,
  }
}

describe('CandleHealthBadge', () => {
  it('FRESH면 작은 점 + "실시간 수집 정상" 표시 (fresh tone)', () => {
    render(<CandleHealthBadge data={makeHealth({ status: 'FRESH', reason: 'OK_REALTIME' })} />)
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveAttribute('data-tone', 'fresh')
    expect(badge).toHaveTextContent('캔들 실시간 수집 정상')
  })

  it('STALE + NO_TICK이면 amber tone + reasonDetail 1줄 표시', () => {
    render(
      <CandleHealthBadge
        data={makeHealth({
          status: 'STALE',
          reason: 'NO_TICK',
          reasonDetail: '최근 5분간 체결이 들어오지 않았습니다',
          staleSeconds: 320,
        })}
      />,
    )
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveAttribute('data-tone', 'amber')
    expect(badge).toHaveAttribute('data-reason', 'NO_TICK')
    expect(badge).toHaveTextContent('최근 5분간 체결이 들어오지 않았습니다')
  })

  it('NO_DATA + BACKFILL_PERMANENT_GAP이면 red tone', () => {
    render(
      <CandleHealthBadge
        data={makeHealth({
          status: 'NO_DATA',
          reason: 'BACKFILL_PERMANENT_GAP',
          reasonDetail: '해당 구간은 영구 갭으로 수집할 수 없습니다',
          lastCandleAt: null,
        })}
      />,
    )
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveAttribute('data-tone', 'red')
    expect(badge).toHaveTextContent('해당 구간은 영구 갭으로 수집할 수 없습니다')
  })

  it('MARKET_CLOSED는 STALE이어도 중립(neutral) tone — 정상 장외', () => {
    render(
      <CandleHealthBadge
        data={makeHealth({
          status: 'STALE',
          reason: 'MARKET_CLOSED',
          reasonDetail: '장 마감 — 다음 거래일 개장 시 재개됩니다',
        })}
      />,
    )
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveAttribute('data-tone', 'neutral')
    expect(badge).toHaveTextContent('장 마감 — 다음 거래일 개장 시 재개됩니다')
  })

  it('WS_NOT_SUBSCRIBED / INSERT_FAILED / BACKFILL_FAILED 각 reasonDetail 렌더', () => {
    const cases: Array<{ reason: CandleHealthDto['reason']; status: CandleHealthDto['status']; detail: string }> = [
      { reason: 'WS_NOT_SUBSCRIBED', status: 'STALE', detail: '실시간 구독이 활성화되지 않았습니다' },
      { reason: 'INSERT_FAILED', status: 'STALE', detail: '캔들 적재 중 오류가 발생했습니다' },
      { reason: 'BACKFILL_FAILED', status: 'NO_DATA', detail: '과거 캔들 백필이 실패했습니다' },
    ]
    for (const c of cases) {
      const { unmount } = render(
        <CandleHealthBadge data={makeHealth({ reason: c.reason, status: c.status, reasonDetail: c.detail })} />,
      )
      expect(screen.getByTestId('candle-health-badge')).toHaveTextContent(c.detail)
      unmount()
    }
  })

  it('방어적 파싱: 모르는 reason이어도 throw 없이 reasonDetail 원문 표시', () => {
    render(
      <CandleHealthBadge
        data={makeHealth({
          status: 'STALE',
          // 계약 밖 신규 reason — UI가 깨지면 안 됨
          reason: 'SOME_FUTURE_REASON' as CandleHealthDto['reason'],
          reasonDetail: '백엔드가 새로 추가한 사유 설명',
        })}
      />,
    )
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveTextContent('백엔드가 새로 추가한 사유 설명')
  })

  it('reasonDetail이 비어도 reason 코드 기반 fallback 라벨 표시', () => {
    render(
      <CandleHealthBadge
        data={makeHealth({ status: 'STALE', reason: 'NO_TICK', reasonDetail: '' })}
      />,
    )
    expect(screen.getByTestId('candle-health-badge')).toHaveTextContent('체결(tick) 미수신')
  })

  it('조회 실패(isError) 시 "확인 불가" 표시 — 가짜 FRESH 금지', () => {
    render(<CandleHealthBadge isError />)
    const badge = screen.getByTestId('candle-health-badge')
    expect(badge).toHaveAttribute('data-tone', 'neutral')
    expect(badge).toHaveTextContent('캔들 수집 상태 확인 불가')
    expect(badge).not.toHaveTextContent('정상')
  })

  it('로딩 중(data 없음 + isLoading)에는 아무것도 렌더하지 않음', () => {
    const { container } = render(<CandleHealthBadge isLoading />)
    expect(container.firstChild).toBeNull()
  })
})
