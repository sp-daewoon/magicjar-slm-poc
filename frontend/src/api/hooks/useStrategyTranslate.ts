/**
 * DSL 자연어 변환 mutation hook
 *
 * ADR 0023 §1 사용자 트리거만 — 자동 호출 0
 * 재시도 0 — backend가 AI 처리 담당
 *
 * 룰 5: LLM provider import 0건
 */
import { useMutation } from '@tanstack/react-query'
import {
  dslTranslateApi,
  type TranslateFromNaturalRequest,
  type TranslateFromNaturalResponse,
} from '../dslTranslate'

/**
 * 자연어 → DSL 변환 mutation
 * 재시도 0 (AI 응답은 backend가 처리)
 */
export function useStrategyTranslate() {
  return useMutation<TranslateFromNaturalResponse, Error, TranslateFromNaturalRequest>({
    mutationFn: (req) => dslTranslateApi.translateFromNatural(req),
    retry: 0,
  })
}
