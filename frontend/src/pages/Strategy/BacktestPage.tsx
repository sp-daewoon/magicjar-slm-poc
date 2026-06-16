/**
 * BacktestPage — /strategy/backtest 단일 페이지 (T-BT R3)
 *
 * 레이아웃:
 *   상단: 탭 네비게이션 (신규 실행 | 실행 이력)
 *   신규 실행 탭: BacktestForm
 *   실행 이력 탭: BacktestList + (선택 시) BacktestDetail
 *
 * D-1 결정 정합: /strategy/backtest 단일 페이지
 * ADR 0061 §4 C-1 정합: 5 endpoint 활용
 * feedback_no_mock_data.md 정합: userId 없으면 셋업 안내
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { BacktestForm } from '../../components/backtest/BacktestForm'
import { BacktestList } from '../../components/backtest/BacktestList'
import { BacktestDetail } from '../../components/backtest/BacktestDetail'
import type { BacktestRunPersistResponse } from '../../api/backtest'

type ActiveTab = 'new' | 'history'

export function BacktestPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [activeTab, setActiveTab] = useState<ActiveTab>('new')
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
  const [lastResult, setLastResult] = useState<BacktestRunPersistResponse | null>(null)

  // 사용자 미등록 시 셋업 안내
  if (!userId) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <p className="text-sm text-gray-400">사용자 정보가 없습니다</p>
          <p className="mt-1 text-xs text-gray-600">
            셋업 마법사를 완료한 후 이용해주세요
          </p>
          <Link
            to="/setup"
            className="mt-4 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            셋업 시작
          </Link>
        </div>
      </div>
    )
  }

  const handleFormSuccess = (res: BacktestRunPersistResponse) => {
    setLastResult(res)
    // 실행 완료 후 이력 탭으로 이동 + 상세 보기
    setSelectedRunId(res.runId)
    setActiveTab('history')
  }

  const handleSelectRun = (runId: number) => {
    setSelectedRunId(runId)
  }

  const handleBackToList = () => {
    setSelectedRunId(null)
  }

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
      {/* 페이지 헤더 */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-gray-100">백테스트</h1>
          <p className="mt-0.5 text-xs text-gray-500">
            저장된 전략을 과거 데이터로 시뮬레이션합니다 (ADR 0061)
          </p>
        </div>
        <Link
          to="/strategy/builder"
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
        >
          전략 빌더
        </Link>
      </div>

      {/* 탭 네비게이션 */}
      <div className="mb-4 flex gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1">
        <button
          className={[
            'flex-1 rounded-md py-1.5 text-sm font-medium transition-colors',
            activeTab === 'new'
              ? 'bg-gray-800 text-white'
              : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => {
            setActiveTab('new')
            setSelectedRunId(null)
          }}
        >
          신규 실행
        </button>
        <button
          className={[
            'flex-1 rounded-md py-1.5 text-sm font-medium transition-colors',
            activeTab === 'history'
              ? 'bg-gray-800 text-white'
              : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => {
            setActiveTab('history')
            setSelectedRunId(null)
          }}
        >
          실행 이력
        </button>
      </div>

      {/* 신규 실행 탭 */}
      {activeTab === 'new' && (
        <div className="space-y-4">
          <BacktestForm userId={userId} onSuccess={handleFormSuccess} />

          {/* 마지막 실행 결과 요약 */}
          {lastResult && (
            <div className="rounded-xl border border-emerald-900/50 bg-emerald-900/10 p-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-semibold text-emerald-400">
                    백테스트 완료 — Run #{lastResult.runId}
                  </p>
                  <p className="mt-0.5 text-xs text-emerald-600">
                    상태: {lastResult.status} · 소요시간: {lastResult.durationMs.toLocaleString('ko-KR')}ms
                  </p>
                </div>
                <button
                  className="rounded-lg border border-emerald-700/50 px-3 py-1.5 text-xs text-emerald-400 hover:bg-emerald-900/30"
                  onClick={() => {
                    setSelectedRunId(lastResult.runId)
                    setActiveTab('history')
                  }}
                >
                  결과 보기
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 실행 이력 탭 */}
      {activeTab === 'history' && (
        <div>
          {selectedRunId !== null ? (
            <BacktestDetail
              runId={selectedRunId}
              userId={userId}
              onBack={handleBackToList}
            />
          ) : (
            <BacktestList userId={userId} onSelect={handleSelectRun} />
          )}
        </div>
      )}
    </div>
  )
}
