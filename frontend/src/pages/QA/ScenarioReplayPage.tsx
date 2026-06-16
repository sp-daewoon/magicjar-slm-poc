import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import type { ScenarioRunDetailDto } from '../../api/scenarioReplay'
import { ScenarioReplayForm } from '../../components/scenario/ScenarioReplayForm'
import { ScenarioReplayList } from '../../components/scenario/ScenarioReplayList'
import { ScenarioReplayDetail } from '../../components/scenario/ScenarioReplayDetail'

type ActiveTab = 'new' | 'history'

export function ScenarioReplayPage() {
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const [activeTab, setActiveTab] = useState<ActiveTab>('new')
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
  const [lastResult, setLastResult] = useState<ScenarioRunDetailDto | null>(null)

  if (!registeredUserId) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <p className="text-sm text-gray-400">사용자 정보가 없습니다</p>
          <p className="mt-1 text-xs text-gray-600">셋업 마법사를 완료한 후 이용해주세요</p>
          <Link
            to="/setup"
            className="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            셋업 시작
          </Link>
        </div>
      </div>
    )
  }

  const userId = String(registeredUserId)

  const handleSuccess = (result: ScenarioRunDetailDto) => {
    setLastResult(result)
    setSelectedRunId(result.run.id)
    setActiveTab('history')
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-gray-100">시나리오 리플레이</h1>
          <p className="mt-0.5 text-xs text-gray-500">전략 신호·리스크·가상 주문 타임라인</p>
        </div>
        <Link
          to="/strategy/builder"
          className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
        >
          전략 빌더
        </Link>
      </div>

      <div className="mb-4 grid gap-2 rounded-lg border border-sky-900/60 bg-sky-950/20 p-3 text-xs text-sky-200 sm:grid-cols-3">
        <div>
          <span className="font-semibold text-sky-100">리플레이 전용</span>
          <span className="ml-2 text-sky-400">/strategy/scenario-replay</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">가상 주문</span>
          <span className="ml-2 text-sky-400">KIS 주문 미발행</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">저장 위치</span>
          <span className="ml-2 text-sky-400">scenario_* only</span>
        </div>
      </div>

      <div className="mb-4 flex gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1">
        <button
          type="button"
          className={[
            'flex-1 rounded py-1.5 text-sm font-medium transition-colors',
            activeTab === 'new' ? 'bg-gray-800 text-white' : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => {
            setActiveTab('new')
            setSelectedRunId(null)
          }}
        >
          신규 실행
        </button>
        <button
          type="button"
          className={[
            'flex-1 rounded py-1.5 text-sm font-medium transition-colors',
            activeTab === 'history' ? 'bg-gray-800 text-white' : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => {
            setActiveTab('history')
            setSelectedRunId(null)
          }}
        >
          실행 이력
        </button>
      </div>

      {activeTab === 'new' && (
        <div className="space-y-4">
          <ScenarioReplayForm userId={registeredUserId} onSuccess={handleSuccess} />
          {lastResult && (
            <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/20 p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-emerald-300">
                    리플레이 완료 · Run #{lastResult.run.id}
                  </p>
                  <p className="mt-0.5 text-xs text-emerald-600">
                    가상 이벤트 {lastResult.run.eventCount.toLocaleString('ko-KR')}건
                  </p>
                </div>
                <button
                  type="button"
                  className="rounded border border-emerald-800 px-3 py-1.5 text-xs text-emerald-300 hover:bg-emerald-950"
                  onClick={() => {
                    setSelectedRunId(lastResult.run.id)
                    setActiveTab('history')
                  }}
                >
                  타임라인 보기
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {activeTab === 'history' && (
        selectedRunId !== null ? (
          <ScenarioReplayDetail
            runId={selectedRunId}
            userId={userId}
            onBack={() => setSelectedRunId(null)}
          />
        ) : (
          <ScenarioReplayList userId={userId} onSelect={setSelectedRunId} />
        )
      )}
    </div>
  )
}
