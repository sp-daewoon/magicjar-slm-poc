/**
 * usePreferredAccountType — 대시보드/포지션 공통 계좌 탭 선호값.
 *
 * 우선순위:
 * 1. 사용자가 마지막으로 누른 계좌 탭(localStorage)
 * 2. 서버 런타임 모드(`/api/runtime/flags`) — MOCK이면 모의, REAL이면 실전
 * 3. 조회 실패 시 안전 기본값 MOCK
 */
import { useCallback, useEffect, useState } from 'react'
import type { AccountType } from '../account'
import { useRuntimeFlags } from './useRuntimeFlags'

const STORAGE_KEY = 'magicjar.preferredAccountType'

function parseAccountType(value: string | null): AccountType | null {
  return value === 'REAL' || value === 'MOCK' ? value : null
}

function readStoredAccountType(): AccountType | null {
  if (typeof window === 'undefined') return null
  return parseAccountType(window.localStorage.getItem(STORAGE_KEY))
}

function writeStoredAccountType(value: AccountType) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(STORAGE_KEY, value)
}

export function usePreferredAccountType() {
  const { data, isLoading, isError } = useRuntimeFlags()
  const [accountType, setAccountTypeState] = useState<AccountType | null>(() => readStoredAccountType())

  useEffect(() => {
    if (accountType !== null) return
    if (data?.mode) {
      setAccountTypeState(data.mode)
      return
    }
    if (isError) {
      setAccountTypeState('MOCK')
    }
  }, [accountType, data?.mode, isError])

  const setAccountType = useCallback((next: AccountType) => {
    setAccountTypeState(next)
    writeStoredAccountType(next)
  }, [])

  return {
    accountType: accountType ?? 'MOCK',
    setAccountType,
    isReady: accountType !== null || (!isLoading && (data?.mode !== undefined || isError)),
  }
}
