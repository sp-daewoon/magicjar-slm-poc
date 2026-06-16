/**
 * 전략빌더 AI 전략 생성 mutation hook
 *
 * ADR 0112 사용자 트리거만 — 자동 호출 0
 * 재시도 0 — backend가 Claude 호출·검증·1회 재생성 담당
 *
 * 룰 5: LLM provider import 0건
 */
import { useMutation } from '@tanstack/react-query'
import {
  strategyBuilderAiGenerateApi,
  type StrategyBuilderAiGenerateRequest,
  type StrategyBuilderAiGenerateResponse,
} from '../strategyBuilderAiGenerate'

export function useStrategyBuilderAiGenerate() {
  return useMutation<StrategyBuilderAiGenerateResponse, Error, StrategyBuilderAiGenerateRequest>({
    mutationFn: (req) => strategyBuilderAiGenerateApi.generate(req),
    retry: 0,
  })
}
