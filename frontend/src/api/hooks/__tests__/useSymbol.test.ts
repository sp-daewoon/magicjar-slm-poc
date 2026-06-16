import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@tanstack/react-query', () => ({
  useQuery: vi.fn((options) => ({
    _capturedOptions: options,
    data: undefined,
    isLoading: false,
    isError: false,
  })),
}))

import { useQuery } from '@tanstack/react-query'
import {
  SYMBOL_FALLBACK_POLLING_INTERVAL_MS,
  SYMBOL_FALLBACK_STALE_TIME_MS,
  useSymbol,
} from '../useSymbol'

const mockUseQuery = vi.mocked(useQuery)

describe('useSymbol', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('기본값은 최초 스냅샷만 조회하고 polling은 꺼둔다', () => {
    useSymbol('005930')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const options = mockUseQuery.mock.calls[0][0] as {
      refetchInterval?: number | false
      refetchIntervalInBackground?: boolean
      staleTime?: number
    }

    expect(options.refetchInterval).toBe(false)
    expect(options.refetchIntervalInBackground).toBe(false)
    expect(options.staleTime).toBe(SYMBOL_FALLBACK_STALE_TIME_MS)
  })

  it('fallback이 켜진 경우에만 30초 polling을 사용한다', () => {
    useSymbol('005930', { pollingEnabled: true })

    const options = mockUseQuery.mock.calls[0][0] as {
      refetchInterval?: number | false
      refetchOnWindowFocus?: boolean
    }

    expect(options.refetchInterval).toBe(SYMBOL_FALLBACK_POLLING_INTERVAL_MS)
    expect(options.refetchOnWindowFocus).toBe(true)
  })

  it('code가 없거나 enabled=false이면 query를 비활성화한다', () => {
    useSymbol('', { enabled: true })
    useSymbol('005930', { enabled: false })

    const emptyCodeOptions = mockUseQuery.mock.calls[0][0] as { enabled?: boolean }
    const disabledOptions = mockUseQuery.mock.calls[1][0] as { enabled?: boolean }

    expect(emptyCodeOptions.enabled).toBe(false)
    expect(disabledOptions.enabled).toBe(false)
  })
})
