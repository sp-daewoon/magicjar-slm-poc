/**
 * KisRealCredentialBanner unit test — backlog §58 / ADR 0077 K-1
 *
 * 4 시나리오:
 *   1. REAL hasKey=false → 배너 표시 + onOpenSettings 콜백 호출
 *   2. REAL hasKey=true → 배너 미표시
 *   3. REAL credential row 미존재 → 배너 미표시 (OnboardingGuard가 /setup 라우팅)
 *   4. dismiss 클릭 → 배너 사라짐 (sessionStorage 'banner.kisReal.dismissed' = '1')
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { KisRealCredentialBanner } from '../KisRealCredentialBanner'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  sessionStorage.clear()
})
afterAll(() => server.close())

beforeEach(() => {
  sessionStorage.clear()
})

function renderBanner(onOpenSettings = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <KisRealCredentialBanner onOpenSettings={onOpenSettings} />
      </QueryClientProvider>,
    ),
    onOpenSettings,
  }
}

describe('KisRealCredentialBanner — backlog §58 / ADR 0077 K-1', () => {
  it('REAL hasKey=false 시 배너 표시 + 설정 열기 버튼 클릭 시 콜백 호출', async () => {
    server.use(
      http.get('/api/onboarding/kis/credentials', () => {
        return HttpResponse.json({
          userId: 74683223,
          credentials: [
            {
              accountType: 'REAL',
              accountNumber: '7468****',
              appKeyMasked: '',
              secretKeyMasked: '',
              lastValidatedAt: null,
              hasKey: false,
            },
          ],
        })
      }),
    )

    const { onOpenSettings } = renderBanner()

    await waitFor(() => {
      expect(
        screen.getByText(/KIS 실전 자격증명이 등록되지 않아/),
      ).toBeInTheDocument()
    })

    const openBtn = screen.getByRole('button', { name: /설정 모달 열기/ })
    fireEvent.click(openBtn)
    expect(onOpenSettings).toHaveBeenCalledTimes(1)
  })

  it('REAL hasKey=true 시 배너 표시 안 함', async () => {
    server.use(
      http.get('/api/onboarding/kis/credentials', () => {
        return HttpResponse.json({
          userId: 74683223,
          credentials: [
            {
              accountType: 'REAL',
              accountNumber: '7468****',
              appKeyMasked: 'PS01***wxyz',
              secretKeyMasked: '********',
              lastValidatedAt: '2026-05-18T00:00:00Z',
              hasKey: true,
            },
          ],
        })
      }),
    )

    renderBanner()

    // 데이터 로드 완료 대기 — query state idle 도달 보장 (act 경고 회피)
    await waitFor(() => {
      expect(
        screen.queryByText(/KIS 실전 자격증명이 등록되지 않아/),
      ).not.toBeInTheDocument()
    })
  })

  it('REAL credential row 미존재 시 배너 표시 안 함 (OnboardingGuard 위임)', async () => {
    server.use(
      http.get('/api/onboarding/kis/credentials', () => {
        return HttpResponse.json({
          userId: 74683223,
          credentials: [], // 빈 배열 — 신규 사용자
        })
      }),
    )

    renderBanner()

    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(
      screen.queryByText(/KIS 실전 자격증명이 등록되지 않아/),
    ).not.toBeInTheDocument()
  })

  it('dismiss 버튼 클릭 시 배너 사라짐 + sessionStorage 설정', async () => {
    server.use(
      http.get('/api/onboarding/kis/credentials', () => {
        return HttpResponse.json({
          userId: 74683223,
          credentials: [
            {
              accountType: 'REAL',
              accountNumber: '7468****',
              appKeyMasked: '',
              secretKeyMasked: '',
              lastValidatedAt: null,
              hasKey: false,
            },
          ],
        })
      }),
    )

    renderBanner()

    await waitFor(() => {
      expect(
        screen.getByText(/KIS 실전 자격증명이 등록되지 않아/),
      ).toBeInTheDocument()
    })

    const dismissBtn = screen.getByRole('button', { name: /배너 닫기/ })
    fireEvent.click(dismissBtn)

    await waitFor(() => {
      expect(
        screen.queryByText(/KIS 실전 자격증명이 등록되지 않아/),
      ).not.toBeInTheDocument()
    })

    expect(sessionStorage.getItem('banner.kisReal.dismissed')).toBe('1')
  })
})
