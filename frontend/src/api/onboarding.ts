/**
 * Onboarding REST API 클라이언트
 *
 * spec §4-1 REST 시그니처 LOCK 1:1 정합
 * 5 endpoint: status / kis/validate / kis/mock / system-config / complete
 */
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import apiClient from '../lib/axios'
import { useOnboardingStore } from '../stores/onboarding'

// ──────────────────────────────────────────────
// Response / Request 타입 정의 (spec §4-1 LOCK)
// ──────────────────────────────────────────────

/** GET /api/onboarding/status (ADR 0089 Phase 2 — userId number 통일) */
export interface OnboardingStatus {
  hasUserAccount: boolean
  hasMockAccount: boolean
  hasDartKey: boolean
  hasSlackWebhook: boolean
  /** KIS 실전 계좌번호 (8자리 number) — 단일 사용자 시스템. hasUserAccount=false 시 null. store auto-sync 용. */
  userId: number | null
}

/** POST /api/onboarding/kis/validate — 요청 */
export interface KisValidateRequest {
  accountNumber: string // 8자리 KIS 실전 계좌번호 (input — 사용자 타이핑 형식 보존)
  displayName?: string // UI 닉네임 (선택, 최대 100자)
  appKey: string // KIS 콘솔 발급
  secretKey: string
}

/** POST /api/onboarding/kis/validate — 응답 (200) (ADR 0089 — Long → JS number) */
export interface KisValidateResponse {
  userId: number // 8자리 (Long)
  maskedAccount: string // "7468****"
  displayName?: string
  status: 'VALIDATED'
  validatedAt: string // ISO 8601
}

/** POST /api/onboarding/kis/mock — 요청 (ADR 0089) */
export interface KisMockRequest {
  userId: number // 실전 계좌번호 (이미 등록되어 있어야 함)
  mockAccountNumber: string // KIS 모의 발급 계좌번호 (8자리, input format string)
  appKey: string
  secretKey: string
}

/** POST /api/onboarding/kis/mock — 응답 (200) (ADR 0089) */
export interface KisMockResponse {
  userId: number
  mockAccountNumber: string
  status: 'VALIDATED'
}

/** POST /api/onboarding/system-config — 요청 */
export interface SystemConfigRequest {
  dartApiKey?: string // null = 변경 안 함, "" = 삭제, 비어있지 않음 = 등록·갱신
  slackWebhookUrl?: string // 동일 정책 + URL validation
  realtimeFallbackBuyEnabled?: boolean
}

/** POST /api/onboarding/system-config — 응답 (200) */
export interface SystemConfigResponse {
  hasDartKey: boolean
  hasSlackWebhook: boolean
  dartApiKey?: string | null
  slackWebhookUrl?: string | null
  realtimeFallbackBuyEnabled: boolean
  updatedAt: string // ISO 8601
}

/** POST /api/onboarding/complete — 요청 (ADR 0089) */
export interface OnboardingCompleteRequest {
  userId: number
}

/** POST /api/onboarding/complete — 응답 (200) (ADR 0089) */
export interface OnboardingCompleteResponse {
  userId: number
  maskedAccount: string
  redirectTo: string // "/"
}

/** 공통 에러 응답 포맷 (spec §4-2) */
export interface ErrorResponse {
  code: string
  message: string
}

/**
 * axios 에러에서 backend ErrorResponse 메시지를 추출하는 헬퍼
 * 실패 시 fallback 메시지 반환
 */
export function extractErrorMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as Partial<ErrorResponse> | undefined
    return data?.message ?? fallback
  }
  return fallback
}

// ──────────────────────────────────────────────
// TanStack Query hooks
// ──────────────────────────────────────────────

/**
 * 온보딩 상태 조회 + store auto-sync
 *
 * staleTime=0: 마법사 완료 후 즉시 재조회
 *
 * store sync 흐름:
 *   server 응답 userId → useOnboardingStore.registeredUserId 자동 주입.
 *   새 브라우저/시크릿 모드/localStorage 클리어 후에도 즉시 복원 (dogfooding qa #2·#3 fix).
 */
export function useOnboardingStatus() {
  const storeUserId = useOnboardingStore((s) => s.registeredUserId)
  const setRegistered = useOnboardingStore((s) => s.setRegistered)

  const query = useQuery<OnboardingStatus>({
    queryKey: ['onboarding', 'status'],
    queryFn: async () => {
      const res = await apiClient.get<OnboardingStatus>('/onboarding/status')
      return res.data
    },
    retry: 1,
    staleTime: 0,
  })

  useEffect(() => {
    const serverUserId = query.data?.userId
    if (serverUserId && serverUserId !== storeUserId) {
      setRegistered(serverUserId)
    }
  }, [query.data?.userId, storeUserId, setRegistered])

  return query
}

/**
 * KIS 실전 키 검증 + DB 영속 mutation
 * Step 2에서 사용 — 성공 시 KisValidateResponse 반환
 */
export function useValidateKisReal() {
  const queryClient = useQueryClient()
  return useMutation<KisValidateResponse, unknown, KisValidateRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<KisValidateResponse>('/onboarding/kis/validate', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
    },
  })
}

/**
 * KIS 모의계좌 추가 mutation
 *
 * Step 3 (SetupWizard) + SettingsModal MOCK 신규 등록 양쪽에서 사용.
 * SettingsModal에서는 hasKey=false 분기에서 호출 (T-20260519-2301 hotfix —
 * 기존엔 무조건 PUT /kis/{accountType} → 404 발생).
 *
 * onSuccess 시 settings.kisCredentials 쿼리도 invalidate 하여 SettingsModal
 * KIS 키 관리 섹션이 즉시 refresh 되도록 함.
 */
export function useAddMockAccount() {
  const queryClient = useQueryClient()
  return useMutation<KisMockResponse, unknown, KisMockRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<KisMockResponse>('/onboarding/kis/mock', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
      // SettingsModal KisCredentialSection의 useKisCredentials와 동일 키
      void queryClient.invalidateQueries({ queryKey: ['settings', 'kisCredentials'] })
    },
  })
}

/**
 * 시스템 설정 갱신 mutation
 * Step 4에서 사용 (선택)
 */
export function useUpdateSystemConfig() {
  const queryClient = useQueryClient()
  return useMutation<SystemConfigResponse, unknown, SystemConfigRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<SystemConfigResponse>('/onboarding/system-config', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
      void queryClient.invalidateQueries({ queryKey: ['settings', 'systemConfig'] })
    },
  })
}

/**
 * 시스템 설정 조회.
 *
 * 설정 화면 재진입 시 DART API 키와 Slack webhook URL을 기존 입력값으로 복원한다.
 */
export function useSystemConfig() {
  return useQuery<SystemConfigResponse>({
    queryKey: ['settings', 'systemConfig'],
    queryFn: async () => {
      const res = await apiClient.get<SystemConfigResponse>('/system-config')
      return res.data
    },
    retry: 1,
    staleTime: 0,
  })
}

/**
 * 온보딩 완료 mutation
 * Step 5 "시작하기" 버튼에서 사용
 */
export function useCompleteOnboarding() {
  const queryClient = useQueryClient()
  return useMutation<OnboardingCompleteResponse, unknown, OnboardingCompleteRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<OnboardingCompleteResponse>('/onboarding/complete', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
    },
  })
}
