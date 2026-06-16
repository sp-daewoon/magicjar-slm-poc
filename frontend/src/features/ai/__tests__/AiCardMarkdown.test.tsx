/**
 * AiCardMarkdown 단위 테스트 — T-AI R2 frontend.
 *
 * light markdown:
 * - ## 헤더
 * - **bold**
 * - - 불릿 리스트
 * - [link](url)
 * - 빈 줄 단락 분리
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AiCardMarkdown } from '../components/AiCardMarkdown'

describe('AiCardMarkdown', () => {
  it('단순 단락 — text 그대로 표시', () => {
    render(<AiCardMarkdown content="안녕하세요." />)
    expect(screen.getByText('안녕하세요.')).toBeInTheDocument()
  })

  it('## 헤더 → h3', () => {
    render(<AiCardMarkdown content="## 분석 결과" />)
    const h = screen.getByText('분석 결과')
    expect(h.tagName).toBe('H3')
  })

  it('**bold** → strong', () => {
    const { container } = render(<AiCardMarkdown content="이건 **중요한** 부분입니다." />)
    const strong = container.querySelector('strong')
    expect(strong).not.toBeNull()
    expect(strong!.textContent).toBe('중요한')
  })

  it('불릿 리스트 — - 항목', () => {
    render(<AiCardMarkdown content={`- 첫째\n- 둘째\n- 셋째`} />)
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('첫째')
    expect(items[2]).toHaveTextContent('셋째')
  })

  it('빈 줄 단락 분리 — 두 단락 모두 표시', () => {
    render(<AiCardMarkdown content={`단락1\n\n단락2`} />)
    expect(screen.getByText('단락1')).toBeInTheDocument()
    expect(screen.getByText('단락2')).toBeInTheDocument()
  })

  it('[link](url) — 외부 링크 (target=_blank + rel=noopener)', () => {
    render(<AiCardMarkdown content="자세한 내용은 [공시](https://dart.fss.or.kr)에서." />)
    const a = screen.getByRole('link', { name: '공시' })
    expect(a.getAttribute('href')).toBe('https://dart.fss.or.kr')
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toContain('noopener')
  })

  it('빈 content → 단락 0건 (graceful)', () => {
    const { container } = render(<AiCardMarkdown content="" />)
    expect(container.querySelectorAll('p, h3, ul').length).toBe(0)
  })
})
