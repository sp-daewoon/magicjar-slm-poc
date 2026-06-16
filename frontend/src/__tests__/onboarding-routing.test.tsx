/**
 * smoke test — 온보딩 라우팅 가드
 *
 * spec §8-2 정합:
 * - hasUserAccount=false mock → /setup 라우팅 검증 (vitest + react-testing-library + msw)
 *
 * backend가 PR-6a 미머지 상태에서도 msw mock으로 독립 동작 가능
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeAll, afterAll, afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { App } from '../App'

// msw 서버 설정 — /api/onboarding/status를 mock
const server = setupServer(
  http.get('/api/onboarding/status', () => {
    return HttpResponse.json({
      hasUserAccount: false,
      hasMockAccount: false,
      hasDartKey: false,
      hasSlackWebhook: false,
    })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function renderApp(initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('온보딩 라우팅 가드', () => {
  it('hasUserAccount=false 시 / 진입하면 /setup으로 이동한다', async () => {
    renderApp('/')

    // SetupWizard 화면 요소가 나타날 때까지 대기 (복수 요소 허용 — AppLayout 추가로 중복 가능)
    await waitFor(
      () => {
        const elements = screen.getAllByText(/magicJar 셋업 마법사|셋업 마법사|KIS 실전 계좌 정보 입력/)
        expect(elements.length).toBeGreaterThan(0)
      },
      { timeout: 3000 },
    )
  })
})
