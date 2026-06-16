/**
 * useCandleRefresh — 캔들 갱신 useMutation hook
 *
 * POST /api/symbols/{code}/candles/refresh?intervals=...
 * 성공 시 TanStack Query의 symbolCandles 캐시를 invalidate → 차트 자동 새로고침.
 *
 * 사용처: SymbolChart 헤더 "갱신" 버튼 onClick
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useStompTopic } from '../../lib/stomp'
import {
  symbolApi,
  type CandleRecollectJobResponseDto,
  type CandleRecollectOptions,
  type CandleRecollectProgressDto,
} from '../symbol'

export interface UseCandleRefreshOptions {
  code: string
  /** 갱신할 인터벌 목록 (미지정 시 backend default: 1d·1wk·1mo·1y) */
  intervals?: string[]
  /** 성공 시 콜백 (added 건수 전달) */
  onSuccess?: (added: number) => void
  /** 실패 시 콜백 */
  onError?: (msg: string) => void
}

export function useCandleRefresh({
  code,
  intervals,
  onSuccess,
  onError,
}: UseCandleRefreshOptions) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => symbolApi.refreshCandles(code, intervals),
    onSuccess: (data) => {
      // symbolCandles 캐시 invalidate → useSymbolCandles re-fetch → 차트 자동 갱신
      void queryClient.invalidateQueries({ queryKey: ['symbolCandles', code] })
      const added = data.refreshedIntervals.reduce((sum, item) => sum + item.insertedCount, 0)
      onSuccess?.(added)
    },
    onError: (err) => {
      const msg =
        err instanceof Error ? err.message : '캔들 갱신 중 오류가 발생했습니다.'
      onError?.(msg)
    },
  })

  return {
    refresh: mutation.mutate,
    isPending: mutation.isPending,
    isError: mutation.isError,
  }
}

export interface UseCandleRecollectOptions {
  code: string
  intervals: string[]
  recollectOptions?: CandleRecollectOptions
  onSuccess?: (deleted: number, inserted: number) => void
  onError?: (msg: string) => void
}

export function useCandleRecollect({
  code,
  intervals,
  recollectOptions,
  onSuccess,
  onError,
}: UseCandleRecollectOptions) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => symbolApi.recollectCandles(code, intervals, recollectOptions),
    onSuccess: (data) => {
      void queryClient.invalidateQueries({ queryKey: ['symbolCandles', code] })
      const inserted =
        data.refreshedIntervals.reduce((sum, item) => sum + item.insertedCount, 0) +
        data.rebuiltIntervals.reduce((sum, item) => sum + item.insertedCount, 0)
      onSuccess?.(data.deletedCount, inserted)
    },
    onError: (err) => {
      const msg =
        err instanceof Error ? err.message : '캔들 재수집 중 오류가 발생했습니다.'
      onError?.(msg)
    },
  })

  return {
    recollect: mutation.mutate,
    isPending: mutation.isPending,
    isError: mutation.isError,
  }
}

export interface UseCandleRecollectJobOptions {
  code: string
  intervals: string[]
  recollectOptions?: CandleRecollectOptions
  onStarted?: (job: CandleRecollectJobResponseDto) => void
  onProgress?: (event: CandleRecollectProgressDto) => void
  onSuccess?: (deleted: number, inserted: number, event: CandleRecollectProgressDto) => void
  onError?: (msg: string, event?: CandleRecollectProgressDto) => void
}

/**
 * recollect 런타임 override (ADR 0120 §6 재수집 모달).
 *
 * hook 생성 시점의 intervals/recollectOptions 대신, mutate 호출 시점에 사용자 선택값을 전달.
 * 미지정 시 hook 옵션 fallback (기존 단일 interval 호출 회귀 안전).
 */
export interface CandleRecollectRunInput {
  intervals?: string[]
  options?: CandleRecollectOptions
}

function isTerminal(status: string | undefined | null): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED'
}

function isRunning(status: string | undefined | null): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

export function useCandleRecollectJob({
  code,
  intervals,
  recollectOptions,
  onStarted,
  onProgress,
  onSuccess,
  onError,
}: UseCandleRecollectJobOptions) {
  const queryClient = useQueryClient()
  const [job, setJob] = useState<CandleRecollectJobResponseDto | null>(null)
  const [progress, setProgress] = useState<CandleRecollectProgressDto | null>(null)
  const completedJobIdsRef = useRef<Set<string>>(new Set())

  const finishJob = useCallback(
    (event: CandleRecollectProgressDto) => {
      if (completedJobIdsRef.current.has(event.jobId)) return
      completedJobIdsRef.current.add(event.jobId)
      void queryClient.invalidateQueries({ queryKey: ['symbolCandles', code], exact: false })
      void queryClient.refetchQueries({ queryKey: ['symbolCandles', code], exact: false, type: 'active' })
      if (event.status === 'SUCCEEDED') {
        onSuccess?.(event.deletedCount, event.insertedCount + event.rebuiltCount, event)
      } else {
        onError?.(event.error ?? event.message ?? '캔들 재수집 중 오류가 발생했습니다.', event)
      }
    },
    [code, onError, onSuccess, queryClient],
  )

  const applyProgress = useCallback(
    (event: CandleRecollectProgressDto) => {
      if (event.code !== code) return
      setProgress(event)
      onProgress?.(event)
      if (isTerminal(event.status)) {
        finishJob(event)
      }
    },
    [code, finishJob, onProgress],
  )

  const mutation = useMutation({
    mutationFn: (input: CandleRecollectRunInput | void) =>
      symbolApi.startCandleRecollectJob(
        code,
        input?.intervals ?? intervals,
        input?.options ?? recollectOptions,
      ),
    onSuccess: (data) => {
      completedJobIdsRef.current.delete(data.jobId)
      setJob(data)
      const queued: CandleRecollectProgressDto = {
        jobId: data.jobId,
        code: data.code,
        intervals: data.intervals,
        status: data.status,
        stage: 'QUEUED',
        processedDays: null,
        totalDays: null,
        deletedCount: 0,
        insertedCount: 0,
        rebuiltCount: 0,
        message: '캔들 재수집 대기 중',
        elapsedMs: null,
        error: null,
        updatedAt: data.submittedAt,
      }
      setProgress(queued)
      onStarted?.(data)
    },
    onError: (err) => {
      const msg =
        err instanceof Error ? err.message : '캔들 재수집 중 오류가 발생했습니다.'
      onError?.(msg)
    },
  })

  useStompTopic<CandleRecollectProgressDto>(
    job?.topic ?? '',
    applyProgress,
    Boolean(job && !isTerminal(progress?.status)),
  )

  useEffect(() => {
    if (!job || !isRunning(progress?.status)) return
    const timer = window.setInterval(() => {
      void symbolApi
        .getCandleRecollectJob(code, job.jobId)
        .then(applyProgress)
        .catch(() => {
          // STOMP가 주 경로다. poll 실패 1회는 다음 tick에서 재시도한다.
        })
    }, 1500)
    return () => window.clearInterval(timer)
  }, [applyProgress, code, job, progress?.status])

  return {
    recollect: mutation.mutate,
    isPending: mutation.isPending || isRunning(progress?.status),
    isError: mutation.isError || progress?.status === 'FAILED',
    job,
    progress,
  }
}
