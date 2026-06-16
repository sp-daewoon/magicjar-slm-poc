/**
 * KisCredentialSection — KIS 키 관리 섹션 (ADR 0074 §2 submit 활성화 실구현)
 *
 * ADR 0069 §3 Section 2: SettingsModal 내 mount 위치.
 *
 * 이전 Option A stub 해소 — 풀세트 실구현:
 * - GET /api/onboarding/kis/credentials → 마스킹된 현재 키 표시 (useKisCredentials hook)
 * - REAL/MOCK 둘 다 표시 (hasKey=false 시 "등록된 KIS 키 없음" 표시)
 * - "변경" 버튼 → 입력 폼 펼치기 (input 빈 상태 — 기존 키 자동 채움 금지, ADR 0074 §8-3 정합)
 * - submit → PUT /api/onboarding/kis/{accountType} → 성공 시 toast + useKisCredentials refetch
 * - MOCK에 "삭제" 버튼 추가 → DELETE → ConfirmDangerToggle 이중 confirm (accountType=REAL 삭제 불가)
 * - REAL DELETE 불가: 백엔드 403 + UI에서 삭제 버튼 미노출
 *
 * 보안 룰 (메모리 feedback_secret_handling.md 정합):
 * - 응답 마스킹 표시 후 "변경" 클릭 시 빈 input (기존 키 자동 채움 금지)
 * - submit 성공 후 SecretKeyInput unmount → zero-fill (ADR 0046 §2-5)
 *
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 * - hasKey=false → "등록된 KIS 키 없음" 표시 (placeholder mask 금지)
 *
 * AI 호출 0 (개발 룰 5 + ADR 0074 §11-2 정합)
 *
 * MOCK 신규 등록 분기 (T-20260519-2301 hotfix):
 * - hasMock=false 시 PUT /onboarding/kis/MOCK → 404 root cause (등록된 키 없음)
 * - 신규 등록은 POST /onboarding/kis/mock (OnboardingController)
 * - 변경은 PUT /onboarding/kis/{accountType} (KisCredentialChangeController)
 * - REAL은 SetupWizard에서 사전 등록 강제 → 항상 PUT
 */
import axios from 'axios'
import { useState } from 'react'
import { SettingsSection } from '../SettingsSection'
import type { SectionStatus } from '../SettingsSection'
import { KisCredentialForm } from '../forms/KisCredentialForm'
import { ConfirmDangerToggle } from '../forms/ConfirmDangerToggle'
import { useKisCredentials } from '../../../api/queries/useKisCredentials'
import { useUpdateKisCredential } from '../../../api/queries/useUpdateKisCredential'
import { useDeleteKisCredential } from '../../../api/queries/useDeleteKisCredential'
import { useAddMockAccount } from '../../../api/onboarding'
import { useToast } from '../../ui/ToastProvider'
import type { KisCredentialFormValues } from '../forms/KisCredentialForm'

type ActiveForm = 'REAL' | 'MOCK' | null

function formatKst(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function extractErrorMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined
    return data?.message ?? fallback
  }
  if (err instanceof Error) return err.message
  return fallback
}

export function KisCredentialSection() {
  const { data: credData, isLoading, isError } = useKisCredentials()
  const { mutate: updateCred, isPending: isUpdating } = useUpdateKisCredential()
  const { mutate: addMockAccount, isPending: isAddingMock } = useAddMockAccount()
  const { mutate: deleteCred, isPending: isDeleting } = useDeleteKisCredential()
  const { showToast } = useToast()

  const [activeForm, setActiveForm] = useState<ActiveForm>(null)
  const [sectionStatus, setSectionStatus] = useState<SectionStatus>('idle')
  const [sectionError, setSectionError] = useState<string | null>(null)
  const [serverError, setServerError] = useState<string | null>(null)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  const credentials = credData?.credentials ?? []
  const realCred = credentials.find((c) => c.accountType === 'REAL')
  const mockCred = credentials.find((c) => c.accountType === 'MOCK')
  const hasReal = realCred?.hasKey ?? false
  const hasMock = mockCred?.hasKey ?? false
  const userId = credData?.userId ?? null

  // MOCK 신규 등록 시에도 spinner/disable 통합
  const isSubmitting = isUpdating || isAddingMock

  function handleSubmit(accountType: 'REAL' | 'MOCK') {
    return (values: KisCredentialFormValues) => {
      setSectionStatus('saving')
      setSectionError(null)
      setServerError(null)

      // MOCK 신규 등록 분기 — hasMock=false 시 POST /onboarding/kis/mock
      // (KisCredentialChangeController PUT은 등록된 키 없으면 404 반환)
      if (accountType === 'MOCK' && !hasMock) {
        if (!userId) {
          const message = '실전 계좌가 등록되지 않아 모의 키를 등록할 수 없습니다. SetupWizard에서 실전 계좌를 먼저 등록해 주세요.'
          setSectionStatus('error')
          setSectionError(message)
          setServerError(message)
          return
        }
        if (!/^\d{8}$/.test(values.accountNumber)) {
          const message = '모의 계좌번호는 숫자 8자리여야 합니다.'
          setSectionStatus('error')
          setSectionError(message)
          setServerError(message)
          return
        }

        addMockAccount(
          {
            userId,
            mockAccountNumber: values.accountNumber,
            appKey: values.appKey,
            secretKey: values.secretKey,
          },
          {
            onSuccess: () => {
              setSectionStatus('saved')
              setActiveForm(null)
              showToast('모의계좌 KIS 키가 등록되었습니다', 'success')
              setTimeout(() => setSectionStatus('idle'), 3000)
            },
            onError: (err) => {
              const message = extractErrorMessage(err, 'KIS 모의 키 등록에 실패했습니다.')
              setSectionStatus('error')
              setSectionError(message)
              setServerError(message)
            },
          },
        )
        return
      }

      // 기존 키 변경 (REAL 항상 / MOCK hasKey=true) — PUT /onboarding/kis/{accountType}
      updateCred(
        {
          accountType,
          request: { appKey: values.appKey, secretKey: values.secretKey },
        },
        {
          onSuccess: () => {
            setSectionStatus('saved')
            setActiveForm(null)
            const msg = accountType === 'REAL'
              ? 'KIS 키 변경 완료 — 새 토큰 발급 중 (5초 후 자동 재개)'
              : 'KIS 모의 키 변경 완료'
            showToast(msg, 'success')
            setTimeout(() => setSectionStatus('idle'), 3000)
          },
          onError: (err) => {
            const message = extractErrorMessage(err, 'KIS 키 변경에 실패했습니다.')
            setSectionStatus('error')
            setSectionError(message)
            setServerError(message)
          },
        },
      )
    }
  }

  function handleDeleteMock(_reason: string) {
    deleteCred(
      { accountType: 'MOCK' },
      {
        onSuccess: () => {
          setShowDeleteConfirm(false)
          setActiveForm(null)
          showToast('KIS 모의 키 삭제 완료', 'info')
        },
        onError: (err) => {
          const message = extractErrorMessage(err, 'KIS 모의 키 삭제에 실패했습니다.')
          showToast(`삭제 실패: ${message}`, 'error')
        },
      },
    )
  }

  // 로딩 스켈레톤
  if (isLoading) {
    return (
      <SettingsSection title="KIS 키 관리" description="KIS 실전·모의 계좌 API 키를 관리합니다.">
        <div className="space-y-2">
          <div className="h-14 animate-pulse rounded-lg bg-gray-800" />
          <div className="h-14 animate-pulse rounded-lg bg-gray-800" />
        </div>
      </SettingsSection>
    )
  }

  // 에러 (페이지 전체 중단 금지)
  if (isError) {
    return (
      <SettingsSection
        title="KIS 키 관리"
        description="KIS 실전·모의 계좌 API 키를 관리합니다."
        status="error"
        errorMessage="KIS 키 정보를 불러오지 못했습니다"
      >
        <p className="text-xs text-red-400">
          서버와 연결에 실패했습니다. 페이지를 새로 고치거나 잠시 후 다시 시도해 주세요.
        </p>
      </SettingsSection>
    )
  }

  return (
    <SettingsSection
      title="KIS 키 관리"
      description="KIS 실전·모의 계좌 API 키를 관리합니다."
      status={sectionStatus}
      errorMessage={sectionError ?? undefined}
    >
      {/* 현재 등록 상태 */}
      <div className="mb-4 space-y-2">
        {/* REAL 키 행 */}
        <div className="rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-3">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0 flex-1">
              <span className="text-sm font-medium text-gray-300">실전 계좌 (REAL)</span>
              {hasReal && realCred ? (
                <div className="mt-1 space-y-0.5 text-xs text-gray-500">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span>
                      계좌번호: <code className="text-gray-400">{realCred.accountNumber}</code>
                    </span>
                    <span>
                      App Key: <code className="text-gray-400">{realCred.appKeyMasked}</code>
                    </span>
                    <span>
                      Secret: <code className="text-gray-400">{realCred.secretKeyMasked}</code>
                    </span>
                  </div>
                  {realCred.lastValidatedAt && (
                    <div className="text-gray-600">
                      마지막 검증: {formatKst(realCred.lastValidatedAt)}
                    </div>
                  )}
                </div>
              ) : (
                <div className="mt-1 text-xs text-gray-600">등록된 KIS 키 없음</div>
              )}
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              {hasReal ? (
                <span className="rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">
                  등록됨
                </span>
              ) : (
                <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">
                  미등록
                </span>
              )}
              {/* REAL은 변경만 가능 — 삭제 불가 (ADR 0074 §2-2-1 LOCK)
                  REAL 신규 등록은 SetupWizard 흐름에서만 가능. */}
              <button
                type="button"
                onClick={() => setActiveForm((prev) => (prev === 'REAL' ? null : 'REAL'))}
                disabled={isSubmitting || !hasReal}
                title={!hasReal ? '실전 키는 SetupWizard에서 먼저 등록해 주세요' : undefined}
                className="rounded-lg border border-gray-700 px-3 py-1 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
              >
                {activeForm === 'REAL' ? '접기' : '변경'}
              </button>
            </div>
          </div>
        </div>

        {/* MOCK 키 행 */}
        <div className="rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-3">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0 flex-1">
              <span className="text-sm font-medium text-gray-300">모의 계좌 (MOCK)</span>
              {hasMock && mockCred ? (
                <div className="mt-1 space-y-0.5 text-xs text-gray-500">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span>
                      계좌번호: <code className="text-gray-400">{mockCred.accountNumber}</code>
                    </span>
                    <span>
                      App Key: <code className="text-gray-400">{mockCred.appKeyMasked}</code>
                    </span>
                    <span>
                      Secret: <code className="text-gray-400">{mockCred.secretKeyMasked}</code>
                    </span>
                  </div>
                  {mockCred.lastValidatedAt && (
                    <div className="text-gray-600">
                      마지막 검증: {formatKst(mockCred.lastValidatedAt)}
                    </div>
                  )}
                </div>
              ) : (
                <div className="mt-1 text-xs text-gray-600">등록된 KIS 키 없음</div>
              )}
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              {hasMock ? (
                <span className="rounded-full bg-blue-900/40 px-2 py-0.5 text-xs text-blue-400">
                  등록됨
                </span>
              ) : (
                <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">
                  미등록
                </span>
              )}
              {/* MOCK: 삭제 버튼 (hasKey=true 시만 노출) */}
              {hasMock && (
                <button
                  type="button"
                  onClick={() => setShowDeleteConfirm((prev) => !prev)}
                  disabled={isDeleting}
                  className="rounded-lg border border-red-900/60 px-3 py-1 text-xs text-red-400 transition-colors hover:bg-red-950/30 hover:text-red-300 disabled:opacity-50"
                >
                  삭제
                </button>
              )}
              <button
                type="button"
                onClick={() => setActiveForm((prev) => (prev === 'MOCK' ? null : 'MOCK'))}
                disabled={isSubmitting}
                className="rounded-lg border border-gray-700 px-3 py-1 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
              >
                {activeForm === 'MOCK' ? '접기' : hasMock ? '변경' : '추가'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* MOCK 삭제 ConfirmDangerToggle */}
      {showDeleteConfirm && hasMock && (
        <div className="mb-4">
          <ConfirmDangerToggle
            actionLabel="삭제 확인"
            warningMessage={
              'KIS 모의 키를 삭제합니다.\n' +
              '삭제 후 모의 거래 기능을 사용하려면 키를 다시 등록해야 합니다.\n' +
              '이 작업은 되돌릴 수 없습니다.'
            }
            reasonPlaceholder="삭제 사유 (예: 모의 키 재발급 예정)"
            onConfirm={handleDeleteMock}
            isPending={isDeleting}
            buttonVariant="danger"
            cooldownSeconds={0}
          />
        </div>
      )}

      {/* 서버 에러 표시 */}
      {serverError && (
        <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{serverError}</p>
        </div>
      )}

      {/* REAL 키 변경 폼 */}
      {activeForm === 'REAL' && (
        <div className="mt-2 rounded-lg border border-gray-800 bg-gray-900/30 p-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
            실전 키 변경
          </h4>
          <p className="mb-3 text-xs text-amber-400/80 rounded-lg border border-amber-800/30 bg-amber-950/20 px-3 py-2">
            입력 필드는 비어 있습니다. 보안 정책상 기존 키가 자동으로 채워지지 않습니다.
            새 키를 입력해 주세요.
          </p>
          <KisCredentialForm
            accountType="REAL"
            initialAccountNumber={realCred?.accountNumber ?? ''}
            accountNumberReadOnly
            onSubmit={handleSubmit('REAL')}
            isSubmitting={isUpdating}
            submitLabel={isUpdating ? '검증 중...' : '변경 저장'}
          />
        </div>
      )}

      {/* MOCK 키 추가/변경 폼 */}
      {activeForm === 'MOCK' && (
        <div className="mt-2 rounded-lg border border-gray-800 bg-gray-900/30 p-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
            {hasMock ? '모의 키 변경' : '모의 키 추가'}
          </h4>
          {hasMock && (
            <p className="mb-3 text-xs text-amber-400/80 rounded-lg border border-amber-800/30 bg-amber-950/20 px-3 py-2">
              입력 필드는 비어 있습니다. 보안 정책상 기존 키가 자동으로 채워지지 않습니다.
              새 키를 입력해 주세요.
            </p>
          )}
          <KisCredentialForm
            accountType="MOCK"
            initialAccountNumber={mockCred?.accountNumber ?? ''}
            accountNumberReadOnly={hasMock}
            onSubmit={handleSubmit('MOCK')}
            isSubmitting={isSubmitting}
            submitLabel={
              isSubmitting
                ? hasMock ? '저장 중...' : '등록 중...'
                : hasMock ? '변경 저장' : '등록'
            }
          />
        </div>
      )}
    </SettingsSection>
  )
}
