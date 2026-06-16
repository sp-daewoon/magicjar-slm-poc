import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { RiskConfigForm } from '../RiskConfigForm'
import { ToastProvider } from '../../ui/ToastProvider'
import type { RiskConfigDto, RiskConfigUpdateRequest } from '../../../api/risk'

let config: RiskConfigDto
let updateBodies: RiskConfigUpdateRequest[] = []

const baseConfig: RiskConfigDto = {
  userId: 74683223,
  maxPositionAmount: 1_000_000,
  maxPositionPercent: 10,
  dllAbs: null,
  dllPct: 3,
  isDailyLossLimitActive: true,
  maxConcurrentPositions: 5,
  maxTradesPerMinute: 3,
  maxTradesPerDay: 50,
  autoPauseThreshold: 10,
  updatedAt: '2026-06-02T00:00:00Z',
}

const server = setupServer(
  http.get('/api/risk-config', () => HttpResponse.json(config)),
  http.put('/api/risk-config', async ({ request }) => {
    const body = await request.json() as RiskConfigUpdateRequest
    updateBodies.push(body)
    config = {
      ...config,
      ...body,
      updatedAt: '2026-06-02T00:01:00Z',
    }
    return HttpResponse.json(config)
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  config = { ...baseConfig }
  updateBodies = []
})
afterAll(() => server.close())

function renderForm() {
  config = { ...baseConfig }
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RiskConfigForm userId={74683223} />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('RiskConfigForm', () => {
  it('DLL을 끄면 금액과 비율을 모두 null로 저장한다', async () => {
    renderForm()

    const dllSwitch = await screen.findByRole('switch')
    fireEvent.click(dllSwitch)
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    await waitFor(() => expect(updateBodies).toHaveLength(1))
    expect(updateBodies[0]).toMatchObject({
      isDailyLossLimitActive: false,
      dllAbs: null,
      dllPct: null,
    })
  })

  it('금액 기준 DLL은 금액만 보내고 비율은 보내지 않는다', async () => {
    renderForm()

    await screen.findByText('DLL 활성화')
    fireEvent.click(screen.getByRole('button', { name: '금액 기준' }))
    fireEvent.change(screen.getByLabelText('절대 DLL (원)'), { target: { value: '50,000' } })
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }))

    await waitFor(() => expect(updateBodies).toHaveLength(1))
    expect(updateBodies[0]).toMatchObject({
      isDailyLossLimitActive: true,
      dllAbs: 50_000,
      dllPct: null,
    })
  })
})
