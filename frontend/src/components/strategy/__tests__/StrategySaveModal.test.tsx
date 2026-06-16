import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  createMutate: vi.fn(),
  updateMutate: vi.fn(),
}))

vi.mock('../../../hooks/useModalBehavior', () => ({
  useModalBehavior: vi.fn(),
}))

vi.mock('../../../api/hooks/useSigStrategy', () => ({
  useCreateSigStrategy: () => ({
    mutate: mocks.createMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
  useUpdateSigStrategy: () => ({
    mutate: mocks.updateMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
}))

vi.mock('../../InstrumentSearchInput', () => ({
  InstrumentSearchInput: () => <input aria-label="종목 검색" readOnly />,
}))

vi.mock('../RiskPlanPreviewPanel', () => ({
  RiskPlanPreviewPanel: () => null,
}))

vi.mock('../ScaleInExitPanel', () => ({
  ScaleInExitPanel: () => null,
}))

import { StrategySaveModal } from '../StrategySaveModal'

describe('StrategySaveModal', () => {
  beforeEach(() => {
    mocks.createMutate.mockClear()
    mocks.updateMutate.mockClear()
  })

  it('전략 식별자에 한글·공백·가운뎃점·대괄호를 허용해 저장 payload로 보낸다', async () => {
    const user = userEvent.setup()
    const name = '[페이퍼] 장전 갭상승·RVOL·거래대금 setup'

    render(
      <StrategySaveModal
        userId={74683223}
        entry="close > ma(20)"
        initialCodes={['005930']}
        onSaved={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    fireEvent.change(screen.getByPlaceholderText('예: [페이퍼] 장전 갭상승·RVOL·거래대금 setup'), {
      target: { value: name },
    })
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(mocks.createMutate).toHaveBeenCalledTimes(1)
    expect(mocks.createMutate.mock.calls[0][0]).toMatchObject({
      userId: 74683223,
      name,
      codes: ['005930'],
    })
  })
})
